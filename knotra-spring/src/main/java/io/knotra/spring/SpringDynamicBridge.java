package io.knotra.spring;

import io.knotra.ActivationContext;
import io.knotra.CapabilityKey;
import io.knotra.CapabilityRequirement;
import io.knotra.Component;
import io.knotra.ComponentDescriptor;
import io.knotra.ComponentFactory;
import io.knotra.ComponentHandle;
import io.knotra.ComponentState;
import io.knotra.ContextHandle;
import io.knotra.AsyncDynamicOperation;
import io.knotra.DynamicCapability;
import io.knotra.DynamicOperation;
import io.knotra.KnotraRuntime;
import io.knotra.NoConfig;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Host-side stable proxy for a dynamic Knotra capability.
 *
 * <p>The bridge component subscribes to {@code sourceKey} and publishes the Core-created
 * lease proxy under {@code bridgeKey}. Replacing the source does not restart the bridge or
 * its Spring child consumers; an in-flight call keeps its provider lease until completion.
 */
public final class SpringDynamicBridge<T> implements AutoCloseable {

    private final ContextHandle context;
    private final ComponentHandle<NoConfig> handle;
    private final CapabilityKey<BridgeAccess> accessKey;
    private final DynamicCapability<T> capability;
    private final T proxy;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicReference<CompletableFuture<Void>> closeFuture =
            new AtomicReference<>();

    private SpringDynamicBridge(
            ContextHandle context,
            ComponentHandle<NoConfig> handle,
            CapabilityKey<BridgeAccess> accessKey,
            DynamicCapability<T> capability,
            T proxy) {
        this.context = context;
        this.handle = handle;
        this.accessKey = accessKey;
        this.capability = capability;
        this.proxy = proxy;
    }

    public static <T> SpringDynamicBridge<T> mount(
            KnotraRuntime runtime,
            String mountId,
            CapabilityKey<T> sourceKey,
            CapabilityKey<T> bridgeKey) {
        return mount(runtime, runtime.root(), mountId, sourceKey, bridgeKey);
    }

    public static <T> SpringDynamicBridge<T> mount(
            KnotraRuntime runtime,
            ContextHandle context,
            String mountId,
            CapabilityKey<T> sourceKey,
            CapabilityKey<T> bridgeKey) {
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(context, "context");
        String safeMountId = requireMountId(mountId);
        Objects.requireNonNull(sourceKey, "sourceKey");
        Objects.requireNonNull(bridgeKey, "bridgeKey");
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
        ComponentFactory<NoConfig> factory = new BridgeFactory<>(
                componentId, sourceKey, bridgeKey, accessKey);
        ComponentHandle<NoConfig> handle = runtime.transact(transaction ->
                transaction.mount(context, safeMountId, factory)).value();

        try {
            ComponentState state = handle.whenSettled()
                    .toCompletableFuture()
                    .get(30, TimeUnit.SECONDS);
            if (state != ComponentState.ACTIVE) {
                throw new IllegalStateException(
                        "dynamic bridge did not become ACTIVE: " + state);
            }
            BridgeAccess access = context.view().require(accessKey);
            DynamicCapability<T> capability = cast(access.dynamicCapability());
            return new SpringDynamicBridge<>(
                    context, handle, accessKey, capability,
                    capability.proxy(sourceKey.type()));
        } catch (Exception error) {
            throw startupFailed(handle, error);
        }
    }

    private static RuntimeException startupFailed(
            ComponentHandle<NoConfig> handle,
            Exception error) {
        try {
            ComponentState state = handle.disposeAsync()
                    .toCompletableFuture()
                    .get(30, TimeUnit.SECONDS);
            return new IllegalStateException(
                    "dynamic bridge startup failed; disposed as " + state, error);
        } catch (Exception disposeError) {
            disposeError.addSuppressed(error);
            if (disposeError instanceof RuntimeException runtimeError) {
                return runtimeError;
            }
            return new IllegalStateException(
                    "dynamic bridge startup cleanup failed", disposeError);
        }
    }

    public T proxy() {
        rejectClosed();
        return proxy;
    }

    public boolean available() {
        return !closed.get() && capability.available();
    }

    public <R> R withCurrent(
            DynamicOperation<? super T, ? extends R> callback) {
        Objects.requireNonNull(callback, "callback");
        rejectClosed();
        return capability.call(callback);
    }

    public <R> CompletionStage<R> withCurrentAsync(
            AsyncDynamicOperation<? super T, R> callback) {
        Objects.requireNonNull(callback, "callback");
        rejectClosed();
        return capability.callAsync(callback);
    }

    DynamicCapability<T> capability() {
        return capability;
    }

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
                created.completeExceptionally(error);
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

    @Override
    public void close() {
        closeAsync().toCompletableFuture().join();
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
            CapabilityKey<BridgeAccess> accessKey) implements ComponentFactory<NoConfig> {

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
