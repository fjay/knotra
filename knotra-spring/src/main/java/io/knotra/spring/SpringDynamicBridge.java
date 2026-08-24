package io.knotra.spring;

import io.knotra.ActivationContext;
import io.knotra.CapabilityKey;
import io.knotra.CapabilityRequirement;
import io.knotra.Component;
import io.knotra.ComponentDescriptor;
import io.knotra.MountFactory;
import io.knotra.ComponentState;
import io.knotra.ContextHandle;
import io.knotra.AsyncDynamicOperation;
import io.knotra.DynamicCapability;
import io.knotra.DynamicOperation;
import io.knotra.KnotraRuntime;
import io.knotra.MountHandle;
import io.knotra.MountNotActiveException;
import io.knotra.NoConfig;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 动态 Knotra 能力及其方法租约代理的宿主端访问器。
 *
 * <p>桥接组件订阅 {@code sourceKey}，并在 {@code bridgeKey} 下发布 Core 创建的租约代理。
 * 替换数据源不会重启桥接器或其 Spring 子容器消费方；在途调用在完成前保持其提供方租约。
 * 使用 {@link #withCurrent(DynamicOperation)} 或 {@link #withCurrentAsync(AsyncDynamicOperation)} 为回调固定单个提供方，
 * 或在每个接口方法可独立选择当前提供方时使用 {@link #proxy()}。</p>
 */
public final class SpringDynamicBridge<T> implements AutoCloseable {

    /** 旧便利重载使用的默认启动与清理等待预算。 */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
    private final MountHandle handle;
    private final DynamicCapability<T> capability;
    private final T proxy;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicReference<CompletableFuture<Void>> closeFuture =
            new AtomicReference<>();

    private final Duration closeTimeout;

    private SpringDynamicBridge(
            MountHandle handle,
            DynamicCapability<T> capability,
            T proxy,
            Duration closeTimeout) {
        this.handle = handle;
        this.capability = capability;
        this.proxy = proxy;
        this.closeTimeout = closeTimeout;
    }

    /**
     * 使用 {@link #DEFAULT_TIMEOUT} 挂载动态桥接器。
     *
     * <p>该便利重载保留既有调用形态；生产环境如需收紧停机或启动预算，
     * 请显式调用带 {@code timeout} 的重载。</p>
     */
    public static <T> SpringDynamicBridge<T> mount(
            KnotraRuntime runtime,
            String mountId,
            CapabilityKey<T> sourceKey,
            CapabilityKey<T> bridgeKey) {
        return mount(runtime, runtime.root(), mountId, sourceKey, bridgeKey, DEFAULT_TIMEOUT);
    }

    /** 使用自定义等待预算在根上下文挂载动态桥接器。 */
    public static <T> SpringDynamicBridge<T> mount(
            KnotraRuntime runtime,
            String mountId,
            CapabilityKey<T> sourceKey,
            CapabilityKey<T> bridgeKey,
            Duration timeout) {
        return mount(runtime, runtime.root(), mountId, sourceKey, bridgeKey, timeout);
    }

    /**
     * 使用 {@link #DEFAULT_TIMEOUT} 在指定上下文挂载动态桥接器。
     *
     * <p>该便利重载保留既有调用形态；生产环境如需收紧停机或启动预算，
     * 请显式调用带 {@code timeout} 的重载。</p>
     */
    public static <T> SpringDynamicBridge<T> mount(
            KnotraRuntime runtime,
            ContextHandle context,
            String mountId,
            CapabilityKey<T> sourceKey,
            CapabilityKey<T> bridgeKey) {
        return mount(runtime, context, mountId, sourceKey, bridgeKey, DEFAULT_TIMEOUT);
    }

    /**
     * 使用自定义正等待预算在指定上下文挂载动态桥接器。
     *
     * <p>{@code timeout} 同时约束启动等待与启动失败后的同步清理等待。
     * 启动等待委托 {@link MountHandle#requireActive(Duration)}，因此超时、中断
     * 与非 ACTIVE 结算统一抛出 {@code MountNotActiveException}。</p>
     */
    public static <T> SpringDynamicBridge<T> mount(
            KnotraRuntime runtime,
            ContextHandle context,
            String mountId,
            CapabilityKey<T> sourceKey,
            CapabilityKey<T> bridgeKey,
            Duration timeout) {
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(context, "context");
        String safeMountId = requireMountId(mountId);
        Objects.requireNonNull(sourceKey, "sourceKey");
        Objects.requireNonNull(bridgeKey, "bridgeKey");
        requireTimeout(timeout);
        requireInterface(sourceKey);
        requireInterface(bridgeKey);
        if (!sourceKey.type().equals(bridgeKey.type())) {
            throw new IllegalArgumentException(
                    "source and bridge capability types must be identical");
        }

        String componentId = "spring-dynamic-bridge:" + safeMountId;
        CapabilityKey<BridgeAccess> accessKey = CapabilityKey.of(
                "knotra.spring.bridge.access:" + safeMountId,
                BridgeAccess.class);
        MountFactory factory = new BridgeFactory<>(
                componentId, sourceKey, bridgeKey, accessKey);
        MountHandle handle = runtime.advanced().transact(transaction ->
                transaction.mount(context, safeMountId, factory)).value();

        try {
            awaitStartup(handle, timeout);
            BridgeAccess access = context.view().require(accessKey);
            DynamicCapability<T> capability = cast(access.dynamicCapability());
            return new SpringDynamicBridge<>(
                    handle, capability,
                    capability.proxy(sourceKey.type()),
                    timeout);
        } catch (RuntimeException error) {
            throw startupFailed(handle, error, timeout);
        }
    }

    static void awaitStartup(MountHandle handle, Duration timeout) {
        handle.requireActive(timeout);
    }

    private static RuntimeException startupFailed(
            MountHandle handle,
            RuntimeException error,
            Duration timeout) {
        ComponentState state;
        try {
            state = awaitStage(handle.disposeAsync(), timeout,
                    "dynamic bridge startup cleanup",
                    "startup cleanup remains pending on the mount handle");
        } catch (RuntimeException cleanupError) {
            return new IllegalStateException(
                    "dynamic bridge startup failed: " + stableError(error)
                            + "; " + cleanupError.getMessage(), null);
        }
        if (state != ComponentState.DISPOSED) {
            return new IllegalStateException(
                    "dynamic bridge startup failed: " + stableError(error)
                            + "; cleanup observed " + state, null);
        }
        if (error instanceof MountNotActiveException) {
            return error;
        }
        return new IllegalStateException(
                "dynamic bridge startup failed: " + stableError(error), null);
    }

    /**
     * 返回具备方法级提供方租约的 {@code T} 代理。
     *
     * <p>每次接口方法调用都会独立选择并租用提供方。当多个方法调用必须观察同一个提供方时，
     * 请勿使用此对象；请改用 {@link #withCurrent(DynamicOperation)}。</p>
     */
    public T proxy() {
        rejectClosed();
        return proxy;
    }

    /** 返回当前动态能力是否存在可用提供方。 */
    public boolean available() {
        return !closed.get() && capability.available();
    }

    /**
     * 在回调执行期间针对单个提供方运行回调。
     *
     * <p>在回调开始前固定提供方，并保持其租约直至回调返回。这是针对同一提供方进行多次观察的安全方式。</p>
     */
    public <R> R withCurrent(
            DynamicOperation<? super T, ? extends R> callback) {
        Objects.requireNonNull(callback, "callback");
        rejectClosed();
        return capability.call(callback);
    }

    /**
     * 针对单个提供方运行异步回调，直至其 completion stage 结算。
     *
     * <p>在此方法返回后，提供方租约与 Knotra 的异步 stage 排空语义仍然生效。</p>
     */
    public <R> CompletionStage<R> withCurrentAsync(
            AsyncDynamicOperation<? super T, R> callback) {
        Objects.requireNonNull(callback, "callback");
        rejectClosed();
        return capability.callAsync(callback);
    }

    DynamicCapability<T> capability() {
        return capability;
    }

    /**
     * 请求关闭桥接器，并返回物理清理收敛阶段。
     *
     * <p>调用后访问方法立即进入 closed 状态；这不表示物理清理已完成。清理仍在等待时，
     * 重复调用会复用同一个 pending future；清理失败后再次调用则发起重试。</p>
     */
    public CompletionStage<Void> closeAsync() {
        closed.set(true);
        CompletableFuture<Void> created = new CompletableFuture<>();
        CompletableFuture<Void> existing = closeFuture.updateAndGet(current ->
                current != null && !current.isCompletedExceptionally() ? current : created);
        if (existing != created) {
            return existing;
        }
        CompletionStage<ComponentState> transition =
                handle.state() == ComponentState.FAILED
                        ? handle.retryAsync()
                        : handle.disposeAsync();
        transition.whenComplete((state, error) -> {
            if (error != null) {
                created.completeExceptionally(new IllegalStateException(
                        "dynamic bridge cleanup failed: " + stableError(error), null));
                return;
            }
            if (state == ComponentState.DISPOSED) {
                created.complete(null);
                return;
            }
            if (state == ComponentState.FAILED) {
                created.completeExceptionally(new IllegalStateException(
                        "dynamic bridge cleanup failed; retry closeAsync to retry"));
                return;
            }
            created.completeExceptionally(new IllegalStateException(
                    "dynamic bridge cleanup did not converge: " + state));
        });
        return created;
    }

    /**
     * 使用 {@link #DEFAULT_TIMEOUT} 等待清理完成。
     *
     * <p>该便利方法保留既有调用形态；生产环境应显式传入停机预算。</p>
     */
    @Override
    public void close() {
        close(closeTimeout);
    }

    /**
     * 使用自定义正预算同步等待清理完成。
     *
     * <p>超时或中断不会取消清理，也不会把已请求关闭的桥接器伪装成已物理关闭；
     * 后续 {@link #closeAsync()} 会继续返回同一个 pending future。等待异常只携带
     * 稳定错误文本，不保留底层 Throwable 引用。</p>
     */
    public void close(Duration timeout) {
        requireTimeout(timeout);
        awaitStage(closeAsync(), timeout,
                "dynamic bridge cleanup",
                "cleanup remains pending; retry closeAsync after inspecting pending operations");
    }

    private void rejectClosed() {
        if (closed.get()) {
            throw new IllegalStateException("dynamic bridge is closed");
        }
    }

    private static String requireMountId(String mountId) {
        Objects.requireNonNull(mountId, "mountId");
        String trimmed = mountId.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("mountId must not be blank");
        }
        return trimmed;
    }

    private static void requireInterface(CapabilityKey<?> key) {
        if (!key.type().isInterface()) {
            throw new IllegalArgumentException(
                    "dynamic bridge capability must be an interface: " + key.typeName());
        }
    }

    private static void requireTimeout(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
    }

    private static <R> R awaitStage(
            CompletionStage<R> stage,
            Duration timeout,
            String operation,
            String pendingMessage) {
        CompletableFuture<R> future = stage.toCompletableFuture();
        R result;
        try {
            result = future.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    operation + " wait was interrupted; " + pendingMessage, null);
        } catch (TimeoutException error) {
            throw new IllegalStateException(
                    operation + " timed out after " + timeout + "; " + pendingMessage, null);
        } catch (ExecutionException | CompletionException error) {
            throw new IllegalStateException(
                    operation + " failed: " + stableError(error), null);
        }
        return result;
    }

    private static String stableError(Throwable error) {
        try {
            Throwable cause = error instanceof CompletionException || error instanceof ExecutionException
                    ? error.getCause()
                    : error;
            if (cause == null) {
                cause = error;
            }
            String type = cause.getClass().getName();
            String message = cause.getMessage();
            String text = message == null || message.isBlank()
                    ? type
                    : type + ": " + message;
            return text.length() <= 500 ? text : text.substring(0, 500);
        } catch (Throwable ignored) {
            return "<invalid settlement failure>";
        }
    }

    interface BridgeAccess {
        DynamicCapability<?> dynamicCapability();
    }

    @SuppressWarnings("unchecked")
    private static <T> DynamicCapability<T> cast(
            DynamicCapability<?> capability) {
        return (DynamicCapability<T>) capability;
    }

    private record BridgeFactory<T>(
            String componentId,
            CapabilityKey<T> sourceKey,
            CapabilityKey<T> bridgeKey,
            CapabilityKey<BridgeAccess> accessKey) implements MountFactory {

        @Override
        public String factoryId() {
            return componentId;
        }

        @Override
        public Component<NoConfig> create() {
            return new BridgeComponent<>(componentId, sourceKey, bridgeKey, accessKey);
        }
    }

    private static final class BridgeComponent<T> implements Component<NoConfig> {
        private final String componentId;
        private final CapabilityKey<T> sourceKey;
        private final CapabilityKey<T> bridgeKey;
        private final CapabilityKey<BridgeAccess> accessKey;
        private final ComponentDescriptor descriptor;

        private BridgeComponent(
                String componentId,
                CapabilityKey<T> sourceKey,
                CapabilityKey<T> bridgeKey,
                CapabilityKey<BridgeAccess> accessKey) {
            this.componentId = componentId;
            this.sourceKey = sourceKey;
            this.bridgeKey = bridgeKey;
            this.accessKey = accessKey;
            this.descriptor = ComponentDescriptor.named(
                    componentId,
                    CapabilityRequirement.dynamicOptional(sourceKey));
        }

        @Override
        public ComponentDescriptor descriptor() {
            return descriptor;
        }

        @Override
        public void start(ActivationContext context, NoConfig config) {
            DynamicCapability<T> source = context.subscribe(sourceKey);
            T stableProxy = source.proxy(sourceKey.type());
            context.provide(bridgeKey, stableProxy);
            context.provide(accessKey, (BridgeAccess) () -> source);
        }
    }
}
