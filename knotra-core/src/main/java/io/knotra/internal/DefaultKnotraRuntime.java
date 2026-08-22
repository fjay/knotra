package io.knotra.internal;

import io.knotra.ActivationContext;
import io.knotra.ActivationState;
import io.knotra.CapabilityKey;
import io.knotra.CapabilityRequirement;
import io.knotra.CapabilityUnavailableException;
import io.knotra.ComponentFactory;
import io.knotra.ComponentGoal;
import io.knotra.ComponentHandle;
import io.knotra.ComponentNotActiveException;
import io.knotra.ComponentOrigin;
import io.knotra.ComponentState;
import io.knotra.ContextHandle;
import io.knotra.ContextInfo;
import io.knotra.ContextState;
import io.knotra.DiagnosticCode;
import io.knotra.DynamicCapabilityClosedException;
import io.knotra.KnotraConfig;
import io.knotra.KnotraRuntime;
import io.knotra.LifecycleState;
import io.knotra.MountOptions;
import io.knotra.Provided;
import io.knotra.RegistrationHandle;
import io.knotra.RuntimeDiagnostic;
import io.knotra.RuntimeSnapshot;
import io.knotra.RuntimeTransaction;
import io.knotra.TransactionReceipt;
import io.knotra.TransactionRejectedException;

import java.lang.reflect.InvocationTargetException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Runtime 内核的默认实现，拥有宿主事务、Activation 状态机和组件过渡调度。
 *
 * <p>已提交结构保存在 volatile 的 {@link RuntimeView} 中；所有草稿校验、代际发布和可执行索引同步
 * 都在 {@code coordinator} 临界区内完成。Factory、normalizer 和用户 {@code start()} 不持有协调器锁，因此
 * 慢用户代码只能阻塞自身 Activation，不能阻塞其他宿主事务或 Snapshot。Activation 提交前把注册和
 * 子挂载留在 {@link ActivationRuntime}，验证成功后随同一代际发布；stale 候选则回滚其 LifecycleScope，
 * 并按最新 BindingSet 重新调度。</p>
 *
 * <p>锁顺序约定：协调器锁优先；Context 处置临界区会在协调器内再取 {@code contextFutures}；
 * 完成组件过渡时协调器可嵌套 {@link ComponentRuntime} 的过渡链锁。LifecycleScope 释放器、
 * 用户回调和 Future 回调不得反向获取协调器锁。</p>
 */
public final class DefaultKnotraRuntime implements KnotraRuntime {
    private final KnotraConfig configuration;
    // 结构一致性主锁：保护视图草稿、代际发布、可执行索引同步和过渡状态裁决。
    private final Object coordinator = new Object();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    // 只在协调器内整体替换；无锁读取方始终解析某个完整代际，而不是混合结构。
    private volatile RuntimeView view = RuntimeView.initial();

    // 组件与激活索引的写入口在协调器内；并发读取用于驱动状态机或校验句柄归属。
    private final Map<String, ComponentRuntime> components = new ConcurrentHashMap<>();
    private final Map<String, ComponentHandleImpl<?>> componentHandles =
            new ConcurrentHashMap<>();
    // 组件移出主视图后仍需为旧句柄报告稳定的终态。
    private final Map<String, ComponentState> terminalComponents = new ConcurrentHashMap<>();
    private final Map<String, ActivationRuntime> activations = new ConcurrentHashMap<>();
    private final Map<String, RegistrationHandleImpl> registrationHandles =
            new ConcurrentHashMap<>();
    private final Map<String, ContextHandleImpl> contextHandles = new ConcurrentHashMap<>();
    // Context 处置去重在协调器内嵌套 contextFutures；单组件 dispose 用独立请求锁合并并发调用。
    private final Map<String, CompletableFuture<Void>> contextFutures =
            new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<ComponentState>> disposeRequests =
            new ConcurrentHashMap<>();
    // close 先于新事务置位；失败的未来可被替换以便重试关闭，成功后复用同一结果。
    private final AtomicBoolean closing = new AtomicBoolean();
    private final AtomicReference<CompletableFuture<Void>> closeFuture =
            new AtomicReference<>();

    public DefaultKnotraRuntime(KnotraConfig configuration) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        contextHandles.put("ctx-root", new ContextHandleImpl(this, "ctx-root"));
    }

    @Override
    public String runtimeId() {
        return configuration.runtimeId();
    }

    @Override
    public ContextHandle root() {
        return contextHandles.get("ctx-root");
    }

    @Override
    public <T> Provided<T> provide(CapabilityKey<T> key, T value) {
        TransactionReceipt<RegistrationHandle> receipt =
                transact(transaction -> transaction.provide(root(), key, value));
        return new ProvidedImpl<>(
                receipt.value(),
                key,
                receipt.settlement());
    }

    @Override
    public RuntimeSnapshot snapshot() {
        RuntimeSnapshot partial;
        Map<String, ActivationRuntime> activationCopy;
        synchronized (coordinator) {
            partial = view.snapshotWithoutScopes();
            activationCopy = new HashMap<>(activations);
        }
        // Scope 树有独立锁；先在协调器内复制激活集合，再在锁外生成稳定 DTO。
        List<RuntimeSnapshot.LifecycleScopeSnapshot> scopes = activationCopy.values().stream()
                .flatMap(activation ->
                        activation.scope.snapshots(activation.activationId).stream())
                .sorted(Comparator.comparing(
                        RuntimeSnapshot.LifecycleScopeSnapshot::scopeId))
                .toList();
        return new RuntimeSnapshot(
                partial.generation(),
                partial.contexts(),
                partial.components(),
                partial.activations(),
                partial.registrations(),
                scopes,
                partial.diagnostics());
    }

    @Override
    public void revoke(RegistrationHandle registration) {
        if (registration instanceof ProvidedImpl<?> provided) {
            if (provided.runtime() == this) {
                revoke(provided);
            } else {
                transact(transaction -> {
                    transaction.revoke(registration);
                    return null;
                });
            }
            return;
        }
        transact(transaction -> {
            transaction.revoke(registration);
            return null;
        });
    }

    <T> Provided<T> replace(ProvidedImpl<T> handle, T value) {
        handle.requireFresh("replace");
        TransactionReceipt<RegistrationHandle> receipt = transact(transaction -> {
            transaction.revoke(handle);
            return transaction.provide(root(), handle.capabilityKey(), value);
        });
        handle.markStale();
        return new ProvidedImpl<>(
                receipt.value(),
                handle.capabilityKey(),
                receipt.settlement());
    }

    <T> void revoke(ProvidedImpl<T> handle) {
        handle.requireFresh("revoke");
        transact(transaction -> {
            transaction.revoke(handle);
            return null;
        });
        handle.markStale();
    }

    @Override
    public <R> TransactionReceipt<R> transact(Function<RuntimeTransaction, R> action) {
        Objects.requireNonNull(action, "action");
        MutationRecorder recorder = new MutationRecorder();
        R callbackValue;
        try {
            callbackValue = action.apply(recorder);
        } catch (TransactionRejectedException rejection) {
            throw rejection;
        } catch (Reject rejection) {
            throw new TransactionRejectedException(List.of(rejection.diagnostic()));
        } catch (PreparedComponent.InvalidConfigException error) {
            throw new TransactionRejectedException(List.of(new RuntimeDiagnostic(
                    DiagnosticCode.INVALID_CONFIG,
                    configuration.runtimeId(),
                    LifecycleScopeImpl.safeError(error))));
        } catch (RuntimeException error) {
            throw new TransactionRejectedException(List.of(new RuntimeDiagnostic(
                    DiagnosticCode.INVALID_LIFECYCLE_OPERATION,
                    configuration.runtimeId(),
                    LifecycleScopeImpl.safeError(error))));
        }

        long committedGeneration;
        Set<String> postCommitDirty = new LinkedHashSet<>();
        Set<String> contextDisposals = new LinkedHashSet<>();
        List<CompletableFuture<Void>> registrationDrains = List.of();
        synchronized (coordinator) {
            if (closing.get()) {
                throw new TransactionRejectedException(List.of(new RuntimeDiagnostic(
                        DiagnosticCode.INVALID_LIFECYCLE_OPERATION,
                        configuration.runtimeId(),
                        "runtime is closing")));
            }
            if (recorder.intents.isEmpty()) {
                return new TransactionReceipt<>(
                        callbackValue,
                        view.generation,
                        CompletableFuture.completedFuture(null));
            }

            RuntimeView.Draft draft = new RuntimeView.Draft(view);
            ExecutableCommitPlan executable = new ExecutableCommitPlan();
            Set<String> dirty = new LinkedHashSet<>();
            boolean viewChanged = false;
            try {
                for (Intent intent : recorder.intents) {
                    viewChanged |= applyIntent(draft, intent, dirty, executable);
                }
                markBindingImpacts(draft, dirty, executable);
                refreshDiagnostics(draft);
                if (!viewChanged) {
                    return new TransactionReceipt<>(
                            callbackValue,
                            view.generation,
                            CompletableFuture.completedFuture(null));
                }
                RuntimeView next = draft.publishOnce();
                view = next;
                commitExecutable(next, recorder.intents, executable);
                registrationDrains = retireCommittedRegistrations(executable);
                committedGeneration = next.generation;
                postCommitDirty.addAll(dirty);
                contextDisposals.addAll(executable.contextDisposals);
            } catch (Reject rejection) {
                throw new TransactionRejectedException(List.of(rejection.diagnostic()));
            }
        }

        List<CompletableFuture<?>> componentSettlements =
                new ArrayList<>(schedule(postCommitDirty));
        CompletableFuture<Void> componentSettlement = componentSettlements.isEmpty()
                ? CompletableFuture.completedFuture(null)
                : CompletableFuture.allOf(componentSettlements.toArray(CompletableFuture[]::new));
        List<CompletableFuture<?>> settlements = new ArrayList<>(componentSettlements);
        settlements.addAll(registrationDrains);
        for (String contextId : outermostContextDisposals(contextDisposals)) {
            settlements.add(settleContextDisposal(contextId, componentSettlement));
        }
        CompletableFuture<Void> settlement = settlements.isEmpty()
                ? CompletableFuture.completedFuture(null)
                : CompletableFuture.allOf(settlements.toArray(CompletableFuture[]::new));
        return new TransactionReceipt<>(callbackValue, committedGeneration, settlement);
    }

    @Override
    public CompletionStage<Void> closeAsync() {
        CompletableFuture<Void> created = closeFuture.updateAndGet(existing ->
                existing != null && !existing.isCompletedExceptionally()
                        ? existing
                        : new CompletableFuture<>());
        closing.set(true);
        ContextHandleImpl root = contextHandles.get("ctx-root");
        // Runtime close 复用根 Context 处置路径；只有全树清理成功才关闭执行器。
        disposeContextInView(root, true).whenComplete((ignored, error) -> {
            if (error == null) {
                executor.shutdown();
                created.complete(null);
            } else {
                created.completeExceptionally(error);
            }
        });
        return created;
    }

    boolean hasLiveRegistration(String registrationId) {
        return view.registrations.containsKey(registrationId);
    }

    <T> Optional<T> findInContext(String contextId, CapabilityKey<T> key) {
        Objects.requireNonNull(key, "key");
        // 固定本地视图引用，同一次 require/find 不会被并发发布拆到两个代际。
        RuntimeView current = view;
        RuntimeView.RegistrationData registration =
                current.resolve(contextId, key).orElse(null);
        if (registration == null) {
            return Optional.empty();
        }
        if (!key.type().isInstance(registration.value())) {
            throw new IllegalStateException(
                    "capability registration type mismatch: " + key.name());
        }
        return Optional.of(key.type().cast(registration.value()));
    }

    <T> T requireInContext(String contextId, CapabilityKey<T> key) {
        return findInContext(contextId, key).orElseThrow(() ->
                new IllegalStateException("capability is not available: " + key.name()));
    }

    boolean isDynamicAvailable(ActivationRuntime activation, CapabilityKey<?> key) {
        synchronized (coordinator) {
            if (activation.dynamicCalls.isClosed()
                    || !dynamicConsumerActiveLocked(activation)) {
                return false;
            }
            RuntimeView current = view;
            return current.resolve(activation.owner.contextId, key).isPresent();
        }
    }

    <T> DynamicLease<T> acquireDynamic(ActivationRuntime activation, CapabilityKey<T> key) {
        Objects.requireNonNull(key, "key");
        synchronized (coordinator) {
            if (!activation.dynamicCalls.tryAcquire()) {
                throw new DynamicCapabilityClosedException(
                        "dynamic capability activation is closed: " + key.name());
            }
            boolean consumerActive = dynamicConsumerActiveLocked(activation);
            RuntimeView.RegistrationData registration =
                    consumerActive ? view.resolve(activation.owner.contextId, key).orElse(null) : null;
            if (registration == null
                    || registration.leases().isRetired()
                    || !registration.leases().tryAcquire()) {
                activation.dynamicCalls.release();
                throw new CapabilityUnavailableException(
                        "dynamic capability is not available: " + key.name());
            }
            Object value = registration.value();
            if (!key.type().isInstance(value)) {
                registration.leases().release();
                activation.dynamicCalls.release();
                throw new IllegalStateException(
                        "capability registration type mismatch: " + key.name());
            }
            return new DynamicLease<>(
                    key.type().cast(value),
                    registration.leases(),
                    activation.dynamicCalls);
        }
    }

    private boolean dynamicConsumerActiveLocked(ActivationRuntime activation) {
        RuntimeView current = view;
        RuntimeView.ComponentData component =
                current.components.get(activation.owner.handleId);
        if (component == null || !activation.activationId.equals(component.currentActivationId())) {
            return false;
        }
        RuntimeView.ActivationData data = current.activations.get(activation.activationId);
        return data != null && RuntimeView.activationTracksGraph(data.state());
    }

    RuntimeException uncheckedDynamicCallback(Throwable error) {
        if (error instanceof RuntimeException runtimeError) {
            return runtimeError;
        }
        if (error instanceof CompletionException completion
                && completion.getCause() instanceof RuntimeException runtimeError) {
            return runtimeError;
        }
        return new CompletionException(error);
    }

    RuntimeException uncheckedInvocation(InvocationTargetException error) {
        Throwable cause = error.getCause();
        if (cause == null) {
            return new CompletionException(error);
        }
        if (cause instanceof RuntimeException runtimeError) {
            return runtimeError;
        }
        if (cause instanceof Error fatal) {
            throw fatal;
        }
        return new CompletionException(cause);
    }

    record DynamicLease<T>(
            T provider,
            ProviderLeaseRuntime providerLease,
            DynamicCallGate consumerGate)
            implements AutoCloseable {

        @Override
        public void close() {
            providerLease.release();
            consumerGate.release();
        }
    }

    ContextInfo contextInfo(String contextId) {
        RuntimeView current = view;
        RuntimeView.ContextData data = current.contexts.get(contextId);
        if (data == null) {
            return new ContextInfo(
                    contextId,
                    null,
                    "unknown",
                    ContextState.DISPOSED,
                    "/" + contextId);
        }
        return new ContextInfo(
                data.contextId(),
                data.parentId(),
                data.name(),
                data.state(),
                current.canonicalPath(contextId));
    }

    ContextState contextState(String contextId) {
        RuntimeView.ContextData data = view.contexts.get(contextId);
        return data == null ? ContextState.DISPOSED : data.state();
    }

    String componentMountId(String handleId) {
        RuntimeView.ComponentData data = view.components.get(handleId);
        return data == null ? "" : data.mountId();
    }

    String componentField(
            String handleId,
            Function<RuntimeView.ComponentData, String> field) {
        RuntimeView.ComponentData data = view.components.get(handleId);
        return data == null ? "" : field.apply(data);
    }

    ComponentState componentState(String handleId) {
        RuntimeView.ComponentData data = view.components.get(handleId);
        if (data != null) {
            return data.state();
        }
        ComponentState terminal = terminalComponents.get(handleId);
        return terminal == null ? ComponentState.DISPOSED : terminal;
    }

    ComponentGoal componentGoal(String handleId) {
        RuntimeView.ComponentData data = view.components.get(handleId);
        return data == null ? ComponentGoal.DISPOSED : data.goal();
    }

    long componentConfigRevision(String handleId) {
        RuntimeView.ComponentData data = view.components.get(handleId);
        return data == null ? 0 : data.configRevision();
    }

    CompletionStage<ComponentState> whenSettled(String handleId) {
        ComponentRuntime runtime = components.get(handleId);
        if (runtime == null) {
            return CompletableFuture.completedFuture(componentState(handleId));
        }
        return runtime.enqueue(this, executor).thenCompose(state ->
                state == ComponentState.STOPPING
                        ? whenSettled(handleId)
                        : CompletableFuture.completedFuture(state));
    }

    <C> ComponentHandle<C> requireActive(ComponentHandleImpl<C> handle, Duration timeout) {
        if (timeout != null && (timeout.isZero() || timeout.isNegative())) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        ComponentState settled;
        try {
            if (timeout == null) {
                settled = handle.whenSettled().toCompletableFuture().get();
            } else {
                settled = handle.whenSettled().toCompletableFuture()
                        .get(timeout.toNanos(), TimeUnit.NANOSECONDS);
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw notActive(handle, timeout, error);
        } catch (TimeoutException error) {
            throw notActive(handle, timeout, null);
        } catch (ExecutionException | CompletionException error) {
            throw notActive(handle, timeout, error);
        }
        if (settled == ComponentState.ACTIVE) {
            return handle;
        }
        throw notActive(handle, timeout, null);
    }

    private <C> ComponentNotActiveException notActive(
            ComponentHandleImpl<C> handle,
            Duration timeout,
            Throwable cause) {
        String handleId = handle.handleId();
        List<RuntimeDiagnostic> diagnostics = view.diagnostics.stream()
                .filter(diagnostic -> handleId.equals(diagnostic.targetId()))
                .toList();
        return new ComponentNotActiveException(
                handle.state(),
                handleId,
                handle.mountId(),
                handle.componentId(),
                handle.factoryId(),
                handle.contextId(),
                timeout,
                diagnostics,
                cause);
    }
    <C> CompletionStage<ComponentState> reconfigure(
            ComponentHandleImpl<C> handle,
            C config) {
        try {
            transact(transaction -> {
                transaction.reconfigure(handle, config);
                return null;
            });
            return whenSettled(handle.handleId());
        } catch (TransactionRejectedException rejection) {
            return CompletableFuture.failedFuture(rejection);
        }
    }

    <C> CompletionStage<ComponentState> retry(ComponentHandleImpl<C> handle) {
        ComponentRuntime component = components.get(handle.handleId());
        if (component == null || componentHandles.get(handle.handleId()) != handle) {
            return failedFuture("handle does not belong to this runtime");
        }
        synchronized (coordinator) {
            RuntimeView.ComponentData data = view.components.get(handle.handleId());
            if (data == null || data.state() != ComponentState.FAILED) {
                return failedFuture("retry is only valid for a failed component");
            }
        }
        return component.enqueue(this, executor);
    }

    <C> CompletionStage<ComponentState> dispose(ComponentHandleImpl<C> handle) {
        if (handle.runtime == this
                && componentState(handle.handleId()) == ComponentState.DISPOSED
                && componentGoal(handle.handleId()) == ComponentGoal.DISPOSED) {
            return CompletableFuture.completedFuture(ComponentState.DISPOSED);
        }
        // 合并并发 dispose：所有调用方共享一个请求 Future，失败请求可被下一次尝试替换。
        synchronized (disposeRequests) {
            CompletableFuture<ComponentState> existing =
                    disposeRequests.get(handle.handleId());
            if (existing != null && !existing.isCompletedExceptionally()) {
                return existing;
            }
            CompletableFuture<ComponentState> request = new CompletableFuture<>();
            disposeRequests.put(handle.handleId(), request);
            try {
                transact(transaction -> {
                    transaction.dispose(handle);
                    return null;
                });
            } catch (TransactionRejectedException rejection) {
                disposeRequests.remove(handle.handleId(), request);
                request.completeExceptionally(rejection);
                return request;
            }
            settleDisposeRequest(handle.handleId(), request);
            return request;
        }
    }

    private void settleDisposeRequest(
            String handleId,
            CompletableFuture<ComponentState> request) {
        // whenSettled 可能观察到 STOPPING 的中间态；重新排队直到旧 Activation 清理完成。
        whenSettled(handleId).whenComplete((state, error) -> {
            if (state == ComponentState.STOPPING) {
                executor.execute(() -> settleDisposeRequest(handleId, request));
                return;
            }
            if (error != null || state == ComponentState.DISPOSED) {
                disposeRequests.remove(handleId, request);
            }
            if (error != null) {
                request.completeExceptionally(error);
            } else {
                request.complete(state);
            }
        });
    }

    CompletionStage<Void> disposeContext(ContextHandleImpl handle) {
        if (!contextHandles.containsValue(handle)) {
            CompletableFuture<Void> rejected = new CompletableFuture<>();
            rejected.completeExceptionally(new IllegalStateException(
                    "context handle does not belong to this runtime"));
            return rejected;
        }
        return disposeContextInView(handle, false);
    }

    Object registrationValue(String registrationId) {
        RuntimeView.RegistrationData committed =
                view.registrations.get(registrationId);
        if (committed != null) {
            return committed.value();
        }
        for (ActivationRuntime activation : activations.values()) {
            RuntimeView.RegistrationData staged =
                    activation.stagedRegistrations.get(registrationId);
            if (staged != null) {
                return staged.value();
            }
        }
        return null;
    }

    void validateCapabilityType(CapabilityKey<?> key) {
        RuntimeView current = view;
        Class<?> existing = current.capabilityTypes.get(key.name());
        if (existing != null && existing != key.type()) {
            throw new IllegalArgumentException(
                    "capability name already has type " + existing.getName());
        }
    }

    boolean mountIdReserved(String contextId, String mountId) {
        RuntimeView current = view;
        return current.components.values().stream().anyMatch(component ->
                component.contextId().equals(contextId)
                        && component.mountId().equals(mountId));
    }

    <C> ComponentHandleImpl<C> createProvisionalHandle() {
        return new ComponentHandleImpl<>(this, Sequences.handle());
    }

    private boolean applyIntent(
            RuntimeView.Draft draft,
            Intent intent,
            Set<String> dirty,
            ExecutableCommitPlan executable) {
        return switch (intent) {
            case ProvideIntent provide -> applyProvide(draft, provide);
            case RevokeIntent revoke -> applyRevoke(draft, revoke, dirty, executable);
            case ChildContextIntent child -> applyChildContext(draft, child);
            case MountIntent<?> mount -> applyMount(draft, mount, dirty, executable);
            case ReconfigureIntent<?> reconfigure ->
                    applyReconfigure(draft, reconfigure, dirty, executable);
            case DisposeIntent dispose ->
                    applyDispose(draft, dispose, dirty, executable);
            case ContextDisposeIntent dispose ->
                    applyContextDispose(draft, dispose, dirty, executable);
        };
    }

    private boolean applyProvide(RuntimeView.Draft draft, ProvideIntent intent) {
        ContextHandleImpl context = requireContext(draft, intent.context());
        ensureActiveContext(draft, context.contextId());
        CapabilityKey<?> key = intent.key();
        if (!key.type().isInstance(intent.value())) {
            throw reject(
                    DiagnosticCode.CAPABILITY_TYPE_CONFLICT,
                    key.name(),
                    "value is not an instance of " + key.typeName());
        }
        validateDraftCapabilityType(draft, key);
        boolean occupied = draft.registrations.values().stream().anyMatch(registration ->
                registration.contextId().equals(context.contextId())
                        && registration.key().name().equals(key.name()));
        if (occupied) {
            throw reject(
                    DiagnosticCode.CAPABILITY_SLOT_OCCUPIED,
                    key.name(),
                    "context capability slot is already occupied");
        }
        draft.registrations.put(
                intent.handle().registrationId(),
                new RuntimeView.RegistrationData(
                        intent.handle().registrationId(),
                        key,
                        context.contextId(),
                        RuntimeView.OwnerData.Host.INSTANCE,
                        intent.value(),
                        new ProviderLeaseRuntime(
                                intent.handle().registrationId())));
        draft.capabilityTypes.putIfAbsent(key.name(), key.type());
        return true;
    }

    private boolean applyRevoke(
            RuntimeView.Draft draft,
            RevokeIntent intent,
            Set<String> dirty,
            ExecutableCommitPlan executable) {
        RegistrationHandleImpl handle = requireRegistration(draft, intent.handle());
        RuntimeView.RegistrationData registration =
                draft.registrations.get(handle.registrationId());
        if (registration == null) {
            return false;
        }
        if (!(registration.owner() instanceof RuntimeView.OwnerData.Host)) {
            throw reject(
                    DiagnosticCode.INVALID_LIFECYCLE_OPERATION,
                    handle.registrationId(),
                    "component registration must be revoked through its component handle");
        }
        removeRegistrationInView(draft, handle.registrationId(), executable);
        Set<String> direct = componentsWithBinding(
                draft,
                Set.of(handle.registrationId()));
        Set<String> impacted = new LinkedHashSet<>();
        for (String handleId : direct) {
            RuntimeView.ComponentData component = draft.components.get(handleId);
            if (component == null || component.currentActivationId() == null) {
                impacted.add(handleId);
                continue;
            }
            impacted.addAll(disposeOwnershipForActivation(
                    draft,
                    handleId,
                    component.currentActivationId(),
                    executable));
        }
        detachInView(draft, impacted, dirty, executable);
        return true;
    }

    private boolean applyChildContext(
            RuntimeView.Draft draft,
            ChildContextIntent intent) {
        ContextHandleImpl parent = requireContext(draft, intent.parent());
        ensureActiveContext(draft, parent.contextId());
        if (intent.name() == null || intent.name().isBlank()
                || intent.name().equals(".") || intent.name().equals("..")
                || intent.name().chars().anyMatch(character -> character == '/'
                        || character == '\\'
                        || Character.isISOControl(character))) {
            throw reject(
                    DiagnosticCode.INVALID_LIFECYCLE_OPERATION,
                    parent.contextId(),
                    "context name must be a non-empty path segment");
        }
        String id = intent.handle().contextId();
        String path = draft.canonicalPath(parent.contextId()) + "/" + intent.name();
        boolean duplicateName = draft.contexts.values().stream().anyMatch(candidate ->
                parent.contextId().equals(candidate.parentId())
                        && intent.name().equals(candidate.name()));
        boolean pathCollision = draft.contexts.values().stream().anyMatch(candidate ->
                path.equals(draft.canonicalPath(candidate.contextId()))
                        || path.equals(candidate.canonicalPath()));
        if (duplicateName || pathCollision) {
            throw reject(
                    DiagnosticCode.INVALID_LIFECYCLE_OPERATION,
                    path,
                    "context canonical path is already in use");
        }
        draft.contexts.put(id, new RuntimeView.ContextData(
                id,
                parent.contextId(),
                intent.name(),
                ContextState.ACTIVE,
                path));
        return true;
    }

    private boolean applyMount(
            RuntimeView.Draft draft,
            MountIntent<?> intent,
            Set<String> dirty,
            ExecutableCommitPlan executable) {
        ContextHandleImpl context = requireContext(draft, intent.context());
        ensureActiveContext(draft, context.contextId());
        if (intent.mountId() == null || intent.mountId().isBlank()) {
            throw reject(
                    DiagnosticCode.INVALID_MOUNT_ID,
                    context.contextId(),
                    "mountId must not be blank");
        }
        boolean occupied = draft.components.values().stream().anyMatch(component ->
                component.contextId().equals(context.contextId())
                        && component.mountId().equals(intent.mountId()));
        if (occupied) {
            throw reject(
                    DiagnosticCode.INVALID_MOUNT_ID,
                    context.contextId() + "/" + intent.mountId(),
                    "mountId is already in use in this context");
        }
        for (CapabilityRequirement requirement
                : intent.prepared().descriptor().sortedRequirements()) {
            validateDraftCapabilityType(draft, requirement.key());
            draft.capabilityTypes.putIfAbsent(
                    requirement.key().name(),
                    requirement.key().type());
        }
        String handleId = intent.handle().handleId();
        draft.components.put(handleId, new RuntimeView.ComponentData(
                handleId,
                context.contextId(),
                intent.mountId(),
                intent.prepared().descriptor().componentId(),
                intent.prepared().factoryId(),
                intent.prepared().options().origin(),
                null,
                null,
                ComponentState.WAITING,
                ComponentGoal.RUNNING,
                1,
                null,
                null,
                intent.prepared().descriptor(),
                intent.prepared().options()));
        executable.mounts.put(handleId, intent);
        dirty.add(handleId);
        return true;
    }

    private boolean applyReconfigure(
            RuntimeView.Draft draft,
            ReconfigureIntent<?> intent,
            Set<String> dirty,
            ExecutableCommitPlan executable) {
        ComponentHandleImpl<?> handle = requireComponent(draft, intent.handle());
        RuntimeView.ComponentData data = draft.components.get(handle.handleId());
        if (data == null || data.goal() == ComponentGoal.DISPOSED) {
            throw reject(
                    DiagnosticCode.INVALID_LIFECYCLE_OPERATION,
                    handle.handleId(),
                    "disposed component cannot be reconfigured");
        }
        ensureActiveContext(draft, data.contextId());
        // 期望配置代际是事务内乐观锁：其他提交先改变同一句柄时，本事务整体拒绝。
        if (data.configRevision() != intent.expectedRevision()) {
            throw reject(
                    DiagnosticCode.INVALID_LIFECYCLE_OPERATION,
                    handle.handleId(),
                    "component configuration changed before transaction commit");
        }
        if (intent.equivalent()) {
            return false;
        }
        long nextRevision = data.configRevision() + 1;
        draft.components.put(
                handle.handleId(),
                data.withConfigRevision(nextRevision));
        executable.configs.put(
                handle.handleId(),
                new ExecutableCommitPlan.ConfigUpdate(
                        intent.config(),
                        nextRevision));
        if (data.currentActivationId() != null) {
            executable.staleActivations.add(data.currentActivationId());
            Set<String> impacted = disposeOwnershipForActivation(
                    draft,
                    handle.handleId(),
                    data.currentActivationId(),
                    executable);
            detachInView(draft, impacted, dirty, executable);
        }
        dirty.add(handle.handleId());
        return true;
    }

    private boolean applyDispose(
            RuntimeView.Draft draft,
            DisposeIntent intent,
            Set<String> dirty,
            ExecutableCommitPlan executable) {
        ComponentHandleImpl<?> handle = requireComponent(draft, intent.handle());
        RuntimeView.ComponentData parent = draft.components.get(handle.handleId());
        if (parent != null) {
            draft.components.put(
                    handle.handleId(),
                    parent.withGoal(ComponentGoal.DISPOSED));
        }
        // 先处置该 Activation 拥有的子树，再闭包到外部依赖方；二者共同决定 STOPPING 顺序。
        Set<String> live = disposeOwnershipForActivation(
                draft,
                handle.handleId(),
                parent == null ? null : parent.currentActivationId(),
                executable);
        Set<String> impacted = draft.dependentsClosure(live);
        detachInView(draft, impacted, dirty, executable);
        RuntimeView.ComponentData latest = draft.components.get(handle.handleId());
        if (latest != null && latest.currentActivationId() == null) {
            removeComponentInView(draft, handle.handleId());
            executable.removedComponents.add(handle.handleId());
            return true;
        }
        dirty.addAll(live);
        return true;
    }

    private boolean applyContextDispose(
            RuntimeView.Draft draft,
            ContextDisposeIntent intent,
            Set<String> dirty,
            ExecutableCommitPlan executable) {
        ContextHandleImpl handle = requireContext(draft, intent.handle());
        if (handle.contextId().equals("ctx-root")) {
            throw reject(
                    DiagnosticCode.INVALID_LIFECYCLE_OPERATION,
                    handle.contextId(),
                    "root context must be disposed through runtime close");
        }
        executable.contextDisposals.add(handle.contextId());
        Set<String> subtree = draft.contextSubtree(handle.contextId());
        for (String contextId : subtree) {
            RuntimeView.ContextData data = draft.contexts.get(contextId);
            draft.contexts.put(contextId, data.withState(ContextState.DISPOSING));
        }
        for (RuntimeView.RegistrationData registration :
                draft.registrations.values().stream()
                        .filter(registration -> subtree.contains(registration.contextId())
                                && registration.owner() instanceof RuntimeView.OwnerData.Host)
                        .toList()) {
            removeRegistrationInView(
                    draft,
                    registration.registrationId(),
                    executable);
        }
        // 处置范围包含子树内每个根组件拥有的后代，而不仅是直接位于这些 Context 中的组件。
        Set<String> handles = draft.components.values().stream()
                .filter(component -> subtree.contains(component.contextId()))
                .flatMap(component ->
                        draft.ownershipDescendants(component.handleId()).stream())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        for (String handleId : handles) {
            RuntimeView.ComponentData component = draft.components.get(handleId);
            if (component == null) {
                continue;
            }
            draft.components.put(
                    handleId,
                    component.withGoal(ComponentGoal.DISPOSED));
        }
        Set<String> live = handles.stream()
                .filter(handleId -> draft.components.get(handleId) != null
                        && draft.components.get(handleId).currentActivationId() != null)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> closure = draft.dependentsClosure(live);
        detachInView(draft, closure, dirty, executable);
        for (String handleId : handles) {
            RuntimeView.ComponentData component = draft.components.get(handleId);
            if (component != null && component.currentActivationId() == null) {
                removeComponentInView(draft, handleId);
                executable.removedComponents.add(handleId);
            } else if (component != null) {
                dirty.add(handleId);
            }
        }
        return true;
    }

    // 注册身份或 OPTIONAL 出现/消失都会改变 BindingSet；这里统一把受影响 Activation 标记 stale。
    private void markBindingImpacts(
            RuntimeView.Draft draft,
            Set<String> dirty,
            ExecutableCommitPlan executable) {
        Set<String> impacted = new LinkedHashSet<>();
        for (RuntimeView.ComponentData component : draft.components.values()) {
            if (component.currentActivationId() == null) {
                continue;
            }
            RuntimeView.ActivationData activation =
                    draft.activations.get(component.currentActivationId());
            if (activation == null
                    || !RuntimeView.activationTracksGraph(activation.state())) {
                continue;
            }
            Map<String, RuntimeView.BindingData> effective =
                    draft.effectiveBindings(component, Map.of());
            for (CapabilityRequirement requirement
                    : component.descriptor().sortedRequirements()) {
                RuntimeView.BindingData old =
                        activation.bindings().get(requirement.key().name());
                RuntimeView.BindingData next =
                        effective.get(requirement.key().name());
                if (!bindingIdentityEqual(old, next)) {
                    impacted.add(component.handleId());
                    executable.staleActivations.add(activation.activationId());
                    break;
                }
            }
        }
        if (!impacted.isEmpty()) {
            Set<String> detachTargets = new LinkedHashSet<>();
            for (String handleId : impacted) {
                RuntimeView.ComponentData component =
                        draft.components.get(handleId);
                if (component == null || component.currentActivationId() == null) {
                    detachTargets.add(handleId);
                    continue;
                }
                detachTargets.addAll(disposeOwnershipForActivation(
                        draft,
                        handleId,
                        component.currentActivationId(),
                        executable));
            }
            Set<String> closure = draft.dependentsClosure(detachTargets);
            detachInView(draft, closure, dirty, executable);
        }

        // 曾因拓扑失败而压制的 WAITING 组件，只有在相关拓扑确实变化后才能重置重试预算。
        RuntimeView old = view;
        for (RuntimeView.ComponentData component : draft.components.values()) {
            if (component.state() == ComponentState.WAITING
                    && component.goal() == ComponentGoal.RUNNING) {
                RuntimeView.ComponentData previous = old.components.get(
                        component.handleId());
                boolean topologyChanged = previous == null;
                if (!topologyChanged) {
                    Map<String, RuntimeView.BindingData> before =
                            old.effectiveBindings(previous, Map.of());
                    Map<String, RuntimeView.BindingData> after =
                            draft.effectiveBindings(component, Map.of());
                    topologyChanged = !bindingsEqual(before, after);
                }
                if (topologyChanged) {
                    executable.resetAutoRestart.add(component.handleId());
                }
                dirty.add(component.handleId());
            }
        }
    }

    private boolean bindingsEqual(
            Map<String, RuntimeView.BindingData> left,
            Map<String, RuntimeView.BindingData> right) {
        if (!left.keySet().equals(right.keySet())) {
            return false;
        }
        for (String name : left.keySet()) {
            if (!bindingIdentityEqual(left.get(name), right.get(name))) {
                return false;
            }
        }
        return true;
    }

    // 只比较注册身份和存在性；值 equals 不构成新 BindingSet，但新注册 ID 会构成新代际。
    private boolean bindingIdentityEqual(
            RuntimeView.BindingData left,
            RuntimeView.BindingData right) {
        if (left == null || right == null) {
            return left == right;
        }
        return left.present() == right.present()
                && Objects.equals(left.registrationId(), right.registrationId());
    }

    private Set<String> componentsWithBinding(
            RuntimeView.Draft draft,
            Set<String> registrationIds) {
        Set<String> result = new LinkedHashSet<>();
        for (RuntimeView.ComponentData component : draft.components.values()) {
            if (component.currentActivationId() == null) {
                continue;
            }
            RuntimeView.ActivationData activation =
                    draft.activations.get(component.currentActivationId());
            if (activation == null
                    || !RuntimeView.activationTracksGraph(activation.state())) {
                continue;
            }
            boolean bound = activation.bindings().values().stream()
                    .filter(RuntimeView.BindingData::present)
                    .map(RuntimeView.BindingData::registrationId)
                    .anyMatch(registrationIds::contains);
            if (bound) {
                result.add(component.handleId());
            }
        }
        return result;
    }
    // 换代只处置属于旧 Activation 的子挂载；其他 Activation 创建的同名层后代不能被误删。
    private Set<String> disposeOwnershipForActivation(
            RuntimeView.Draft draft,
            String parentHandleId,
            String ownerActivationId,
            ExecutableCommitPlan executable) {
        Set<String> ownership = draft.ownershipDescendantsForActivation(
                parentHandleId,
                ownerActivationId);
        Set<String> live = new LinkedHashSet<>();
        for (String handleId : ownership) {
            RuntimeView.ComponentData component = draft.components.get(handleId);
            if (component == null) {
                continue;
            }
            if (handleId.equals(parentHandleId)) {
                live.add(handleId);
                continue;
            }
            draft.components.put(handleId, component.withGoal(ComponentGoal.DISPOSED));
            if (component.currentActivationId() == null) {
                removeComponentInView(draft, handleId);
                executable.removedComponents.add(handleId);
            } else {
                live.add(handleId);
            }
        }
        return live;
    }

    // 视图中先脱离绑定并标记 STOPPING；实际 LifecycleScope teardown 延迟到依赖方清理完成后。
    private void detachInView(
            RuntimeView.Draft draft,
            Set<String> handles,
            Set<String> dirty,
            ExecutableCommitPlan executable) {
        Set<String> closure = draft.dependentsClosure(handles);
        List<String> ownedRegistrations = draft.registrationsOwnedBy(closure);
        for (String registrationId : ownedRegistrations) {
            removeRegistrationInView(draft, registrationId, executable);
        }
        for (String handleId : closure) {
            RuntimeView.ComponentData component = draft.components.get(handleId);
            if (component == null) {
                continue;
            }
            if (component.currentActivationId() != null) {
                RuntimeView.ActivationData activation = draft.activations.get(
                        component.currentActivationId());
                if (activation != null) {
                    draft.activations.put(
                            component.currentActivationId(),
                            activation.detached());
                }
                executable.staleActivations.add(component.currentActivationId());
                draft.components.put(
                        handleId,
                        component.withState(ComponentState.STOPPING));
            }
            dirty.add(handleId);
        }
    }

    private void removeComponentInView(RuntimeView.Draft draft, String handleId) {
        RuntimeView.ComponentData data = draft.components.remove(handleId);
        if (data != null && data.currentActivationId() != null) {
            draft.activations.remove(data.currentActivationId());
        }
    }

    private void validateDraftCapabilityType(
            RuntimeView.Draft draft,
            CapabilityKey<?> key) {
        Class<?> existing = draft.capabilityTypes.get(key.name());
        if (existing != null && existing != key.type()) {
            throw reject(
                    DiagnosticCode.CAPABILITY_TYPE_CONFLICT,
                    key.name(),
                    "capability name already has Java type " + existing.getName());
        }
    }

    private ContextHandleImpl requireContext(
            RuntimeView.Draft draft,
            ContextHandle candidate) {
        if (!(candidate instanceof ContextHandleImpl handle)
                || handle.runtime != this
                || !draft.contexts.containsKey(handle.contextId())) {
            throw reject(
                    DiagnosticCode.INVALID_LIFECYCLE_OPERATION,
                    candidate == null ? "unknown" : candidate.contextId(),
                    "context handle does not belong to an active transaction entity");
        }
        return handle;
    }

    private void ensureActiveContext(RuntimeView.Draft draft, String contextId) {
        RuntimeView.ContextData data = draft.contexts.get(contextId);
        if (data == null || data.state() != ContextState.ACTIVE) {
            throw reject(
                    DiagnosticCode.INVALID_LIFECYCLE_OPERATION,
                    contextId,
                    "context is not active");
        }
    }

    private RegistrationHandleImpl requireRegistration(
            RuntimeView.Draft draft,
            RegistrationHandle candidate) {
        if (!(candidate instanceof RegistrationHandleImpl handle)
                || handle.runtime != this
                || !draft.registrations.containsKey(handle.registrationId())) {
            throw reject(
                    DiagnosticCode.INVALID_LIFECYCLE_OPERATION,
                    candidate == null ? "unknown" : candidate.registrationId(),
                    "registration handle does not belong to a live registration");
        }
        return handle;
    }

    private ComponentHandleImpl<?> requireComponent(
            RuntimeView.Draft draft,
            ComponentHandle<?> candidate) {
        if (!(candidate instanceof ComponentHandleImpl<?> handle)
                || handle.runtime != this
                || !draft.components.containsKey(handle.handleId())) {
            throw reject(
                    DiagnosticCode.INVALID_LIFECYCLE_OPERATION,
                    candidate == null ? "unknown" : candidate.handleId(),
                    "component handle does not belong to a live component");
        }
        return handle;
    }

    private void removeRegistrationInView(
            RuntimeView.Draft draft,
            String registrationId,
            ExecutableCommitPlan executable) {
        RuntimeView.RegistrationData registration =
                draft.registrations.remove(registrationId);
        if (registration != null) {
            executable.retiredRegistrations.putIfAbsent(
                    registrationId,
                    registration.leases());
        }
    }

    private List<CompletableFuture<Void>> retireCommittedRegistrations(
            ExecutableCommitPlan executable) {
        return executable.retiredRegistrations.values().stream()
                .map(ProviderLeaseRuntime::retire)
                .toList();
    }

    // 该方法只在视图发布后、仍在协调器内调用；失败路径必须在进入前完成全部可预期校验。
    private void commitExecutable(
            RuntimeView next,
            List<Intent> intents,
            ExecutableCommitPlan executable) {
        for (String activationId : executable.staleActivations) {
            ActivationRuntime activation = activations.get(activationId);
            if (activation != null) {
                activation.markStale();
            }
        }
        for (String handleId : executable.resetAutoRestart) {
            ComponentRuntime runtime = components.get(handleId);
            if (runtime != null) {
                runtime.suppressAutoRestart = false;
                runtime.blockedNonConvergent = false;
                runtime.reconcileAttempts = 0;
            }
        }
        for (String handleId : executable.removedComponents) {
            components.remove(handleId);
            componentHandles.remove(handleId);
            terminalComponents.put(handleId, ComponentState.DISPOSED);
        }

        for (MountIntent<?> mount : executable.mounts.values()) {
            String handleId = mount.handle().handleId();
            if (!next.components.containsKey(handleId)) {
                terminalComponents.put(handleId, ComponentState.DISPOSED);
                continue;
            }
            ComponentRuntime runtime = new ComponentRuntime(
                    handleId,
                    mount.context().contextId(),
                    mount.mountId(),
                    mount.prepared());
            components.put(handleId, runtime);
            componentHandles.put(handleId, mount.handle());
        }
        for (Map.Entry<String, ExecutableCommitPlan.ConfigUpdate> entry
                : executable.configs.entrySet()) {
            ComponentRuntime runtime = components.get(entry.getKey());
            if (runtime != null) {
                runtime.updateConfig(
                        entry.getValue().config(),
                        entry.getValue().revision());
            }
        }

        for (Intent intent : intents) {
            switch (intent) {
                case ProvideIntent provide -> {
                    if (next.registrations.containsKey(
                            provide.handle().registrationId())) {
                        registrationHandles.put(
                                provide.handle().registrationId(),
                                provide.handle());
                    }
                }
                case RevokeIntent revoke ->
                        registrationHandles.remove(revoke.handle().registrationId());
                case ChildContextIntent child -> {
                    if (next.contexts.containsKey(child.handle().contextId())) {
                        contextHandles.put(child.handle().contextId(), child.handle());
                    }
                }
                case ContextDisposeIntent dispose -> {
                    if (!next.contexts.containsKey(dispose.handle().contextId())) {
                        contextHandles.remove(dispose.handle().contextId());
                    }
                }
                default -> {
                }
            }
        }
        registrationHandles.keySet().retainAll(next.registrations.keySet());
    }

    // 先在协调器内按最新视图预约并合并过渡，再离开锁提交虚拟线程执行用户代码。
    private List<CompletableFuture<ComponentState>> schedule(Set<String> dirty) {
        Set<String> stopping = new LinkedHashSet<>();
        Set<String> starting = new LinkedHashSet<>();
        List<ComponentRuntime.Reservation> reservations = new ArrayList<>();
        synchronized (coordinator) {
            for (String handleId : dirty) {
                RuntimeView.ComponentData data = view.components.get(handleId);
                if (data == null) {
                    continue;
                }
                if (data.state() == ComponentState.STOPPING) {
                    stopping.add(handleId);
                } else if ((data.state() == ComponentState.WAITING
                        || data.state() == ComponentState.FAILED)
                        && data.goal() == ComponentGoal.RUNNING) {
                    starting.add(handleId);
                }
            }
            for (String handleId : orderForStop(view, stopping)) {
                reserve(handleId, reservations);
            }
            for (String handleId : starting.stream().sorted().toList()) {
                reserve(handleId, reservations);
            }
        }
        for (ComponentRuntime.Reservation reservation : reservations) {
            if (reservation.created()) {
                reservation.component().executeReserved(
                        this,
                        executor,
                        reservation.future());
            }
        }
        return reservations.stream()
                .map(ComponentRuntime.Reservation::future)
                .collect(Collectors.toList());
    }

    private void reserve(
            String handleId,
            List<ComponentRuntime.Reservation> reservations) {
        ComponentRuntime runtime = components.get(handleId);
        if (runtime != null) {
            reservations.add(runtime.reserveTransition());
        }
    }

    // Kahn 拓扑排序先排无提供方的组件，再整体反转，得到依赖方先于提供方的停止顺序。
    private List<String> orderForStop(RuntimeView current, Set<String> handles) {
        if (handles.isEmpty()) {
            return List.of();
        }
        Map<String, Set<String>> providers = stopProviders(current, handles);
        Map<String, Integer> degree = new TreeMap<>();
        Map<String, Set<String>> dependents = new TreeMap<>();
        for (String handleId : handles) {
            Set<String> targets = providers.getOrDefault(handleId, Set.of());
            degree.put(handleId, targets.size());
            for (String provider : targets) {
                dependents.computeIfAbsent(provider, ignored -> new LinkedHashSet<>())
                        .add(handleId);
            }
        }
        List<String> ready = degree.entrySet().stream()
                .filter(entry -> entry.getValue() == 0)
                .map(Map.Entry::getKey)
                .sorted()
                .collect(Collectors.toCollection(ArrayList::new));
        List<String> ordered = new ArrayList<>();
        while (!ready.isEmpty()) {
            String currentHandle = ready.removeFirst();
            ordered.add(currentHandle);
            for (String dependent : dependents.getOrDefault(
                    currentHandle, Set.of())) {
                int next = degree.merge(dependent, -1, Integer::sum);
                if (next == 0) {
                    ready.add(dependent);
                }
            }
        }
        handles.stream()
                .filter(handleId -> !ordered.contains(handleId))
                .sorted()
                .forEach(ordered::add);
        java.util.Collections.reverse(ordered);
        return ordered;
    }

    // 将注册归属还原为提供方 ComponentHandle，只保留本次也在停止集合内的内部依赖。
    private Map<String, Set<String>> stopProviders(
            RuntimeView current,
            Set<String> handles) {
        Map<String, String> activationOwners = new HashMap<>();
        current.activations.values().forEach(activation ->
                activationOwners.put(activation.activationId(), activation.handleId()));
        Map<String, Set<String>> result = new TreeMap<>();
        for (String handleId : handles) {
            RuntimeView.ComponentData component = current.components.get(handleId);
            if (component == null || component.currentActivationId() == null) {
                continue;
            }
            ActivationRuntime activation = activations.get(
                    component.currentActivationId());
            if (activation == null) {
                continue;
            }
            Set<String> providerHandles = new LinkedHashSet<>();
            for (RuntimeView.BindingData binding : activation.bindings.values()) {
                if (!binding.present()) {
                    continue;
                }
                String providerHandle = providerHandleForRegistration(
                        current,
                        activationOwners,
                        binding.registrationId());
                if (providerHandle != null
                        && handles.contains(providerHandle)
                        && !providerHandle.equals(handleId)) {
                    providerHandles.add(providerHandle);
                }
            }
            result.put(handleId, providerHandles);
        }
        return result;
    }

    private String providerHandleForRegistration(
            RuntimeView current,
            Map<String, String> activationOwners,
            String registrationId) {
        RuntimeView.RegistrationData registration =
                current.registrations.get(registrationId);
        String ownerActivationId = null;
        if (registration != null
                && registration.owner() instanceof RuntimeView.OwnerData.Activation owner) {
            ownerActivationId = owner.activationId();
        } else {
            // 正在启动的提供方可能仍未发布注册；用暂存表识别它，避免新依赖方与提供方重叠清理。
            for (ActivationRuntime activation : activations.values()) {
                boolean owns = activation.stagedRegistrations.values().stream()
                        .map(RuntimeView.RegistrationData::registrationId)
                        .anyMatch(registrationId::equals);
                if (owns) {
                    ownerActivationId = activation.activationId;
                    break;
                }
            }
        }
        return ownerActivationId == null ? null : activationOwners.get(ownerActivationId);
    }

    // 单组件状态机入口：锁内只选择/登记候选 Activation，用户 start() 随后在锁外执行。
    void driveTransition(String handleId, CompletableFuture<ComponentState> future) {
        ComponentRuntime component = components.get(handleId);
        if (component == null) {
            future.complete(ComponentState.DISPOSED);
            return;
        }
        ActivationRuntime activation;
        ComponentState immediateState = null;
        synchronized (coordinator) {
            RuntimeView.ComponentData data = view.components.get(handleId);
            if (data == null) {
                component.finishTransition(future, ComponentState.DISPOSED);
                return;
            }
            if (data.state() == ComponentState.STOPPING
                    || (data.state() == ComponentState.FAILED
                            && (component.current != null
                                    || component.failedCleanup != null))) {
                activation = component.current;
                if (activation == null && component.failedCleanup != null) {
                    activation = component.failedCleanup;
                }
                if (activation == null) {
                    finalizeOrphanedStoppingLocked(component, data, future);
                    return;
                }
            } else if ((data.state() == ComponentState.WAITING
                    || data.state() == ComponentState.FAILED)
                    && data.goal() == ComponentGoal.RUNNING
                    && !component.suppressAutoRestart
                    && requirementsResolvable(view, data)) {
                activation = beginActivationLocked(component);
            } else {
                immediateState = data.state();
                component.finishTransition(future, immediateState);
                return;
            }
        }

        if (activation == null) {
            ComponentState state = immediateState == null
                    ? componentState(handleId)
                    : immediateState;
            component.finishTransition(future, state);
            return;
        }
        // 锁外复核：若结构事务已使候选 stale 或作用域开始清理，直接走回滚路径。
        if (activation.scope.state() != LifecycleState.OPEN
                || view.components.get(handleId) == null
                || view.components.get(handleId).currentActivationId() == null
                || view.activations.get(activation.activationId) == null
                || view.activations.get(activation.activationId).state()
                        == ActivationState.STOPPING) {
            finishCleanupAfterDependents(component, activation, future);
            return;
        }
        runActivation(component, activation, future);
    }

    private void finalizeOrphanedStoppingLocked(
            ComponentRuntime component,
            RuntimeView.ComponentData data,
            CompletableFuture<ComponentState> future) {
        RuntimeView.Draft draft = new RuntimeView.Draft(view);
        ComponentState state;
        if (data.goal() == ComponentGoal.DISPOSED) {
            removeComponentInView(draft, component.handleId);
            components.remove(component.handleId);
            componentHandles.remove(component.handleId);
            terminalComponents.put(component.handleId, ComponentState.DISPOSED);
            state = ComponentState.DISPOSED;
        } else {
            draft.components.put(
                    component.handleId,
                    data.withState(ComponentState.WAITING).clearActivation());
            state = ComponentState.WAITING;
        }
        refreshDiagnostics(draft);
        view = draft.publishOnce();
        component.finishTransition(future, state);
    }

    private boolean requirementsResolvable(
            RuntimeView view,
            RuntimeView.ComponentData data) {
        return data.descriptor().sortedRequirements().stream()
                .filter(requirement ->
                        requirement.mode() == CapabilityRequirement.Mode.REQUIRED)
                .allMatch(requirement ->
                        view.resolve(data.contextId(), requirement.key()).isPresent());
    }

    // 在协调器内为 WAITING 组件创建 STARTING 代际，并把 BindingSet 与值一起固定到候选中。
    private ActivationRuntime beginActivationLocked(ComponentRuntime component) {
        RuntimeView.Draft draft = new RuntimeView.Draft(view);
        RuntimeView.ComponentData data = draft.components.get(component.handleId);
        component.reconcileFingerprint = reconcileFingerprint(draft, data);
        Map<String, RuntimeView.BindingData> bindings =
                draft.effectiveBindings(data, Map.of());
        String activationId = Sequences.activation();
        ActivationRuntime activation = new ActivationRuntime(
                activationId,
                component,
                component.desiredConfig,
                component.desiredRevision,
                bindings,
                List.of());
        for (CapabilityRequirement requirement
                : data.descriptor().sortedRequirements()) {
            RuntimeView.BindingData binding = bindings.get(requirement.key().name());
            RuntimeView.RegistrationData registration = draft.resolve(
                    data.contextId(),
                    requirement.key(),
                    Map.of()).orElse(null);
            if (binding != null && binding.present()) {
                activation.capturedValues.put(
                        requirement.key().name(),
                        registration.value());
            }
            if (requirement.binding() == CapabilityRequirement.CapabilityBinding.DYNAMIC
                    && requirement.mode() == CapabilityRequirement.Mode.REQUIRED) {
                activation.initialDynamicRequiredPresence.put(
                        requirement.key().name(),
                        registration != null);
            }
        }
        draft.activations.put(activationId, new RuntimeView.ActivationData(
                activationId,
                component.handleId,
                ActivationState.STARTING,
                component.desiredRevision,
                bindings,
                data.descriptor(),
                activation.scope.scopeId()));
        draft.components.put(
                component.handleId,
                data.withState(ComponentState.STARTING).withActivation(activationId));
        refreshDiagnostics(draft);
        RuntimeView next = draft.publishOnce();
        view = next;
        activations.put(activationId, activation);
        component.current = activation;
        component.pendingStartFailure = false;
        component.blockedNonConvergent = false;
        component.lastStartError = "";
        return activation;
    }

    // Activation 事务：锁外执行 start()，重新获取协调器后基于最新视图做提交或回滚裁决。
    private void runActivation(
            ComponentRuntime runtime,
            ActivationRuntime activation,
            CompletableFuture<ComponentState> future) {
        List<ChildMountPlan<?>> plans = new ArrayList<>();
        ActivationContext context = new ActivationContextImpl(
                this,
                activation,
                plans);
        Throwable startError = null;
        // 用户代码不持有协调器锁；期间的结构事务可以通过 stale 位召回本候选。
        try {
            runtime.prepared.start(context, activation.config);
        } catch (Throwable error) {
            startError = error;
        }
        activation.closed.set(true);

        // 无论 start() 成功或失败都先关闭上下文，提交临界区是最后一次能消费暂存副作用的窗口。
        PostCommitPlan postCommit;
        CommitDecision decision;
        boolean emergencyRollback = false;
        boolean cleanupRequired = false;
        // 重新获取协调器后，先验证候选代际，再一次性发布注册、子挂载和组件状态。
        synchronized (coordinator) {
            RuntimeView previous = view;
            RuntimeView published = null;
            try {
                decision = validateActivation(
                        runtime,
                        activation,
                        plans,
                        startError);
                RuntimeView.Draft draft = new RuntimeView.Draft(view);
                postCommit = publishActivationDecision(
                        draft,
                        runtime,
                        activation,
                        decision,
                        plans);
                refreshDiagnostics(draft);
                RuntimeView next = draft.publishOnce();
                view = next;
                published = next;
                retireCommittedRegistrations(postCommit.executable());
                commitActivationExecutable(
                        next,
                        runtime,
                        activation,
                        decision,
                        postCommit);
                cleanupRequired = decisionCleanupRequired(runtime);
                if (!cleanupRequired) {
                    runtime.finishTransition(
                            future,
                            componentState(runtime.handleId));
                }
            } catch (Throwable unexpected) {
                // 提交路径自身异常时先回退已发布的视图，再按失败 Activation 走清理。
                cleanupRequired = true;
                if (published != null) {
                    view = previous;
                }
                discardProvisionalChildren(plans);
                activation.markStale();
                decision = new CommitDecision(
                        false,
                        false,
                        false,
                        "activation commit failed: "
                                + LifecycleScopeImpl.safeError(unexpected));
                try {
                    RuntimeView.Draft rollback = new RuntimeView.Draft(view);
                    postCommit = publishActivationDecision(
                            rollback,
                            runtime,
                            activation,
                            decision,
                            plans);
                    refreshDiagnostics(rollback);
                    RuntimeView next = rollback.publishOnce();
                    view = next;
                    retireCommittedRegistrations(postCommit.executable());
                    commitActivationExecutable(
                            next,
                            runtime,
                            activation,
                            decision,
                            postCommit);
                } catch (Throwable fatal) {
                    activation.markStale();
                    runtime.pendingStartFailure = true;
                    runtime.lastStartError = decision.message();
                    emergencyRollback = true;
                    try {
                        emergencyRollbackActivation(runtime, activation);
                    } catch (Throwable ignored) {
                        // The transition future below is still completed exceptionally.
                    }
                    postCommit = new PostCommitPlan(
                            List.of(),
                            Set.of(runtime.handleId),
                            new ExecutableCommitPlan());
                }
            }
        }

        if (emergencyRollback) {
            runtime.failTransition(
                    future,
                    new IllegalStateException(decision.message()));
            return;
        }
        scheduleAfterCommit(postCommit.dirty());
        if (cleanupRequired) {
            finishCleanupAfterDependents(
                    runtime,
                    activation,
                    future);
        }
    }

    // 常规回滚草稿也失败时的最后防线：至少把组件和 Activation 置入可清理的 STOPPING 状态。
    private void emergencyRollbackActivation(
            ComponentRuntime runtime,
            ActivationRuntime activation) {
        RuntimeView.Draft draft = new RuntimeView.Draft(view);
        RuntimeView.ComponentData data = draft.components.get(runtime.handleId);
        if (data != null) {
            draft.components.put(
                    runtime.handleId,
                    data.withState(ComponentState.STOPPING));
        }
        RuntimeView.ActivationData activationData =
                draft.activations.get(activation.activationId);
        if (activationData != null) {
            draft.activations.put(
                    activation.activationId,
                    activationData.detached());
        }
        refreshDiagnostics(draft);
        view = draft.publishOnce();
    }

    // 父 Activation 失败时，未提交的临时子句柄立即转入终态，挂载 ID 可在后续代际复用。
    private void discardProvisionalChildren(List<ChildMountPlan<?>> plans) {
        for (ChildMountPlan plan : plans) {
            String handleId = plan.handle().handleId();
            ComponentRuntime child = components.remove(handleId);
            if (child != null) {
                child.current = null;
                child.failedCleanup = null;
            }
            componentHandles.remove(handleId);
            terminalComponents.put(handleId, ComponentState.DISPOSED);
        }
    }

    // 基于最新代际裁决候选；stale/配置/绑定检查排在用户 startError 之前，避免把召回误报为业务失败。
    private CommitDecision validateActivation(
            ComponentRuntime runtime,
            ActivationRuntime activation,
            List<ChildMountPlan<?>> plans,
            Throwable startError) {
        RuntimeView current = view;
        RuntimeView.ComponentData data = current.components.get(runtime.handleId);
        if (data == null || data.goal() != ComponentGoal.RUNNING) {
            return new CommitDecision(false, true, false, "component goal changed");
        }
        RuntimeView.ContextData context = current.contexts.get(data.contextId());
        if (context == null || context.state() != ContextState.ACTIVE) {
            return new CommitDecision(false, true, false, "context changed");
        }
        if (data.configRevision() != activation.configRevision
                || runtime.desiredRevision != activation.configRevision) {
            return new CommitDecision(false, true, false, "configuration changed");
        }
        if (activation.stale.get()) {
            return new CommitDecision(false, true, false, "activation became stale");
        }
        for (CapabilityRequirement requirement
                : data.descriptor().sortedRequirements()) {
            if (requirement.binding()
                    == CapabilityRequirement.CapabilityBinding.DYNAMIC) {
                if (requirement.mode() == CapabilityRequirement.Mode.REQUIRED) {
                    boolean initialPresence = activation.initialDynamicRequiredPresence
                            .getOrDefault(requirement.key().name(), false);
                    boolean currentPresence = current.resolve(
                            data.contextId(),
                            requirement.key())
                            .isPresent();
                    if (initialPresence != currentPresence) {
                        return new CommitDecision(
                                false,
                                true,
                                false,
                                "dynamic binding presence changed: "
                                        + requirement.key().name());
                    }
                }
                continue;
            }
            RuntimeView.BindingData captured =
                    activation.bindings.get(requirement.key().name());
            RuntimeView.BindingData effective = current.effectiveBindings(
                            data,
                            activation.stagedRegistrations)
                    .get(requirement.key().name());
            if (!bindingIdentityEqual(captured, effective)) {
                return new CommitDecision(
                        false,
                        true,
                        false,
                        "binding changed: " + requirement.key().name());
            }
        }
        for (RuntimeView.RegistrationData staged
                : activation.stagedRegistrations.values()) {
            Class<?> existing = current.capabilityTypes.get(staged.key().name());
            if (existing != null && existing != staged.key().type()) {
                return new CommitDecision(
                        false,
                        false,
                        false,
                        "staged capability type conflict: " + staged.key().name());
            }
            boolean occupied = current.registrations.values().stream().anyMatch(
                    registration -> registration.contextId()
                                    .equals(staged.contextId())
                            && registration.key().name().equals(staged.key().name()));
            if (occupied) {
                return new CommitDecision(
                        false,
                        false,
                        false,
                        "staged capability slot occupied: " + staged.key().name());
            }
        }
        String childConflict = childPlanConflict(current, data.contextId(), plans);
        if (childConflict != null) {
            return new CommitDecision(false, false, false, childConflict);
        }
        if (RuntimeView.hasCycle(
                current.dependencyGraph(activation.stagedRegistrations))) {
            return new CommitDecision(
                    false,
                    false,
                    true,
                    "binding cycle rejected: " + runtime.handleId);
        }
        if (startError != null) {
            return new CommitDecision(
                    false,
                    false,
                    false,
                    LifecycleScopeImpl.safeError(startError));
        }
        return new CommitDecision(true, false, false, "");
    }

    private String childPlanConflict(
            RuntimeView current,
            String contextId,
            List<ChildMountPlan<?>> plans) {
        // 类型检查覆盖其他仍在 STARTING 的暂存 Activation，防止并发批次合 publish 后破坏名称类型固定。
        Map<String, Class<?>> tentativeTypes = new HashMap<>(current.capabilityTypes);
        for (RuntimeView.RegistrationData staged
                : activationRegistrationsForValidation(current, plans).values()) {
            Class<?> existing = tentativeTypes.putIfAbsent(
                    staged.key().name(), staged.key().type());
            if (existing != null && existing != staged.key().type()) {
                return "staged capability type conflict: " + staged.key().name();
            }
        }

        Set<String> batchIds = new LinkedHashSet<>();
        for (ChildMountPlan plan : plans) {
            String identity = contextId + "/" + plan.mountId();
            if (!batchIds.add(identity)) {
                return "staged child mountId conflicts in transaction: " + plan.mountId();
            }
            boolean occupied = current.components.values().stream().anyMatch(component ->
                    component.contextId().equals(contextId)
                            && component.mountId().equals(plan.mountId()));
            if (occupied) {
                return "staged child mountId conflicts latest view: " + plan.mountId();
            }
            for (CapabilityRequirement requirement
                    : plan.prepared().descriptor().sortedRequirements()) {
                Class<?> existing = tentativeTypes.putIfAbsent(
                        requirement.key().name(), requirement.key().type());
                if (existing != null && existing != requirement.key().type()) {
                    return "staged child capability type conflict: "
                            + requirement.key().name();
                }
            }
        }
        return null;
    }

    private Map<String, RuntimeView.RegistrationData> activationRegistrationsForValidation(
            RuntimeView current,
            List<ChildMountPlan<?>> plans) {
        Map<String, RuntimeView.RegistrationData> registrations = new HashMap<>();
        for (ActivationRuntime activation : activations.values()) {
            if (activation.stale.get()
                    || !current.activations.containsKey(activation.activationId)
                    || current.activations.get(activation.activationId).state()
                            == ActivationState.STOPPING) {
                continue;
            }
            registrations.putAll(activation.stagedRegistrations);
        }
        return registrations;
    }
    // 把裁决写入草稿：成功时暂存注册、子挂载和 ACTIVE 状态同代际发布；失败时脱离并保留清理责任。
    private PostCommitPlan publishActivationDecision(
            RuntimeView.Draft draft,
            ComponentRuntime runtime,
            ActivationRuntime activation,
            CommitDecision decision,
            List<ChildMountPlan<?>> plans) {
        RuntimeView.ComponentData data = draft.components.get(runtime.handleId);
        RuntimeView.ActivationData activationData =
                draft.activations.get(activation.activationId);
        if (data == null || activationData == null) {
            return new PostCommitPlan(List.of(), Set.of(), new ExecutableCommitPlan());
        }
        if (decision.success()) {
            // 提交成功才把暂存注册复制到已发布视图；子挂载同批进入 WAITING。
            for (RuntimeView.RegistrationData staged
                    : activation.stagedRegistrations.values()) {
                draft.registrations.put(
                        staged.registrationId(),
                        staged);
                draft.capabilityTypes.putIfAbsent(
                        staged.key().name(),
                        staged.key().type());
            }
            for (ChildMountPlan plan : plans) {
                for (CapabilityRequirement requirement
                        : plan.prepared().descriptor().sortedRequirements()) {
                    draft.capabilityTypes.putIfAbsent(
                            requirement.key().name(),
                            requirement.key().type());
                }
                String childId = plan.handle().handleId();
                draft.components.put(childId, new RuntimeView.ComponentData(
                        childId,
                        data.contextId(),
                        plan.mountId(),
                        plan.prepared().descriptor().componentId(),
                        plan.prepared().factoryId(),
                        plan.prepared().options().origin(),
                        activation.activationId,
                        runtime.handleId,
                        ComponentState.WAITING,
                        ComponentGoal.RUNNING,
                        1,
                        null,
                        null,
                        plan.prepared().descriptor(),
                        plan.prepared().options()));
            }
            draft.activations.put(
                    activation.activationId,
                    activationData.withState(ActivationState.ACTIVE));
            draft.components.put(
                    runtime.handleId,
                    data.withState(ComponentState.ACTIVE));

            // 新注册可能遮蔽已有提供方；提交时同步找出 BindingSet 变化的外部消费方。
            Set<String> changed = new LinkedHashSet<>();
            for (RuntimeView.ComponentData component : draft.components.values()) {
                if (component.currentActivationId() == null
                        || component.handleId().equals(runtime.handleId)) {
                    continue;
                }
                RuntimeView.ActivationData other = draft.activations.get(
                        component.currentActivationId());
                if (other == null
                        || !RuntimeView.activationTracksGraph(other.state())) {
                    continue;
                }
                Map<String, RuntimeView.BindingData> effective =
                        draft.effectiveBindings(
                                component,
                                activation.stagedRegistrations);
                for (CapabilityRequirement requirement
                        : component.descriptor().sortedRequirements()) {
                    RuntimeView.BindingData old =
                            other.bindings().get(requirement.key().name());
                    RuntimeView.BindingData next =
                            effective.get(requirement.key().name());
                    if (!bindingIdentityEqual(old, next)) {
                        changed.add(component.handleId());
                        break;
                    }
                }
            }
            ExecutableCommitPlan executable = new ExecutableCommitPlan();
            Set<String> dirty = new LinkedHashSet<>();
            Set<String> detachTargets = new LinkedHashSet<>();
            for (String handleId : changed) {
                RuntimeView.ComponentData component =
                        draft.components.get(handleId);
                if (component == null || component.currentActivationId() == null) {
                    detachTargets.add(handleId);
                    continue;
                }
                detachTargets.addAll(disposeOwnershipForActivation(
                        draft,
                        handleId,
                        component.currentActivationId(),
                        executable));
            }
            Set<String> closure = draft.dependentsClosure(detachTargets);
            detachInView(draft, closure, dirty, executable);
            return new PostCommitPlan(plans, dirty, executable);
        }

        // 失败路径不发布暂存内容；Activation 脱离绑定后由 LifecycleScope 回滚已接受资源。
        draft.activations.put(
                activation.activationId,
                activationData.detached());
        draft.components.put(
                runtime.handleId,
                data.withState(ComponentState.STOPPING));
        activation.markStale();
        for (ChildMountPlan plan : plans) {
            terminalComponents.put(plan.handle().handleId(), ComponentState.DISPOSED);
        }
        Set<String> dirty = new LinkedHashSet<>(Set.of(runtime.handleId));
        return new PostCommitPlan(List.of(), dirty, new ExecutableCommitPlan());
    }

    // 视图已发布后的 Activation 侧索引同步；stale 标记必须覆盖提交造成的所有外部消费方。
    private void commitActivationExecutable(
            RuntimeView next,
            ComponentRuntime runtime,
            ActivationRuntime activation,
            CommitDecision decision,
            PostCommitPlan postCommit) {
        activation.stale.set(!decision.success() || decision.stale());
        runtime.pendingStartFailure = !decision.success() && !decision.stale() && !decision.suppressCycle();
        runtime.suppressAutoRestart = decision.suppressCycle();
        runtime.lastStartError = decision.success() || decision.stale()
                ? ""
                : decision.message();
        for (String activationId : postCommit.executable().staleActivations) {
            ActivationRuntime impacted = activations.get(activationId);
            if (impacted != null) {
                impacted.markStale();
            }
        }
        for (ChildMountPlan plan : postCommit.children()) {
            String handleId = plan.handle().handleId();
            if (!next.components.containsKey(handleId)) {
                terminalComponents.put(handleId, ComponentState.DISPOSED);
                continue;
            }
            ComponentRuntime child = new ComponentRuntime(
                    handleId,
                    next.components.get(handleId).contextId(),
                    plan.mountId(),
                    plan.prepared());
            components.put(handleId, child);
            componentHandles.put(handleId, plan.handle());
        }
    }

    private boolean decisionCleanupRequired(ComponentRuntime runtime) {
        RuntimeView.ComponentData data = view.components.get(runtime.handleId);
        return data != null && data.state() == ComponentState.STOPPING;
    }

    // 提供方自身 teardown 前，先等待直接和间接依赖方完成旧 Activation 清理。
    private void finishCleanupAfterDependents(
            ComponentRuntime runtime,
            ActivationRuntime activation,
            CompletableFuture<ComponentState> future) {
        // 先关闭本 Activation 的动态调用准入；provider lease 也在进入 LifecycleScope 前排空。
        CompletableFuture<Void> activationDrain =
                activation.dynamicCalls.close()
                        .thenCompose(ignored -> drainProviderLeases(activation));
        List<ComponentRuntime> dependents;
        synchronized (coordinator) {
            dependents = dependentsForProvider(runtime.handleId);
        }
        List<CompletableFuture<ComponentState>> settlements = dependents.stream()
                .map(dependent -> dependent.enqueue(this, executor))
                .toList();
        CompletableFuture<Void> prerequisite = settlements.isEmpty()
                ? activationDrain
                : CompletableFuture.allOf(Stream.concat(
                        Stream.of(activationDrain),
                        settlements.stream())
                        .toArray(CompletableFuture[]::new));
        prerequisite.whenComplete((ignored, error) ->
                finishCleanup(runtime, activation, future));
    }

    private CompletableFuture<Void> drainProviderLeases(ActivationRuntime activation) {
        List<CompletableFuture<Void>> drains = activation.stagedRegistrations.values().stream()
                .map(RuntimeView.RegistrationData::leases)
                .map(ProviderLeaseRuntime::retire)
                .toList();
        return drains.isEmpty()
                ? CompletableFuture.completedFuture(null)
                : CompletableFuture.allOf(drains.toArray(new CompletableFuture[0]));
    }

    // 从提供方反向扩散到所有传递依赖，并纳入其拥有的子挂载，但只调度仍处 STOPPING 的目标。
    private List<ComponentRuntime> dependentsForProvider(String providerHandleId) {
        RuntimeView current = view;
        Map<String, Set<String>> dependencies = stopProviders(
                current,
                new LinkedHashSet<>(current.components.keySet()));
        Set<String> directAndIndirect = new LinkedHashSet<>();
        Set<String> frontier = new LinkedHashSet<>();
        for (Map.Entry<String, Set<String>> entry : dependencies.entrySet()) {
            if (entry.getValue().contains(providerHandleId)) {
                frontier.add(entry.getKey());
            }
        }
        while (!frontier.isEmpty()) {
            String handleId = frontier.iterator().next();
            frontier.remove(handleId);
            if (!directAndIndirect.add(handleId)) {
                continue;
            }
            for (Map.Entry<String, Set<String>> entry : dependencies.entrySet()) {
                if (entry.getValue().contains(handleId)
                        && !directAndIndirect.contains(entry.getKey())) {
                    frontier.add(entry.getKey());
                }
            }
        }
        for (String descendant : current.ownershipDescendants(providerHandleId)) {
            if (!descendant.equals(providerHandleId)) {
                directAndIndirect.add(descendant);
            }
        }
        return directAndIndirect.stream()
                .filter(handleId -> current.components.containsKey(handleId))
                .filter(handleId -> current.components.get(handleId).state()
                        == ComponentState.STOPPING)
                .map(components::get)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    // LifecycleScope teardown 在协调器外执行；完成回调重新取锁，把清理结果收敛为终态或重试安排。
    private void finishCleanup(
            ComponentRuntime runtime,
            ActivationRuntime activation,
            CompletableFuture<ComponentState> future) {
        activation.scope.teardown().whenComplete((ignored, cleanupError) -> {
            ComponentState state;
            boolean restart;
            ComponentRuntime.Reservation restartReservation = null;
            synchronized (coordinator) {
                RuntimeView.Draft draft = new RuntimeView.Draft(view);
                RuntimeView.ComponentData data =
                        draft.components.get(runtime.handleId);
                boolean failed = cleanupError != null
                        || activation.scope.state() == LifecycleState.FAILED;
                if (data == null) {
                    draft.activations.remove(activation.activationId);
                    activations.remove(activation.activationId);
                    state = ComponentState.DISPOSED;
                    restart = false;
                } else {
                    ComponentGoal latestGoal = data.goal();
                    RuntimeView.ActivationData activationData =
                            draft.activations.get(activation.activationId);
                    if (failed) {
                        // 保留 failedCleanup 和 FAILED Activation，阻止新代际启动，直到 retry 收敛。
                        String cleanupDetail = cleanupError == null
                                ? activation.scope.lastCleanupError()
                                : LifecycleScopeImpl.safeError(cleanupError);
                        runtime.lastCleanupError = cleanupDetail.isBlank()
                                ? "cleanup failed"
                                : "cleanup failed: " + cleanupDetail;
                        runtime.failedCleanup = activation;
                        if (activationData != null) {
                            draft.activations.put(
                                    activation.activationId,
                                    activationData.withState(
                                            ActivationState.FAILED));
                        }
                        draft.components.put(
                                runtime.handleId,
                                data.withState(ComponentState.FAILED));
                        state = ComponentState.FAILED;
                        restart = false;
                    } else {
                        // 清理成功后才能移除 Activation 索引，避免 Snapshot 或停止图丢失待清理资源。
                        runtime.lastCleanupError = "";
                        runtime.failedCleanup = null;
                        draft.activations.remove(activation.activationId);
                        activations.remove(activation.activationId);
                        if (latestGoal == ComponentGoal.DISPOSED) {
                            removeComponentInView(draft, runtime.handleId);
                            components.remove(runtime.handleId);
                            componentHandles.remove(runtime.handleId);
                            terminalComponents.put(
                                    runtime.handleId,
                                    ComponentState.DISPOSED);
                            state = ComponentState.DISPOSED;
                            restart = false;
                        } else if (runtime.pendingStartFailure) {
                            draft.components.put(
                                    runtime.handleId,
                                    data.withState(ComponentState.FAILED)
                                            .clearActivation());
                            runtime.current = null;
                            state = ComponentState.FAILED;
                            restart = false;
                        } else {
                            draft.components.put(
                                    runtime.handleId,
                                    data.withState(ComponentState.WAITING)
                                            .clearActivation());
                            runtime.current = null;
                            state = ComponentState.WAITING;
                            restart = planReconcile(
                                    draft,
                                    draft.components.get(runtime.handleId),
                                    runtime);
                        }
                    }
                }
                refreshDiagnostics(draft);
                view = draft.publishOnce();
                // 仍在协调器内替换过渡链，随后到锁外提交新 Activation，保证旧请求先有结果。
                if (restart) {
                    restartReservation = runtime.replaceTransition();
                } else {
                    runtime.finishTransition(future, state);
                }
            }

            if (restartReservation != null) {
                runtime.finishTransition(future);
                restartReservation.component().executeReserved(
                        this,
                        executor,
                        restartReservation.future());
                restartReservation.future().whenComplete((next, error) -> {
                    if (error != null) {
                        future.completeExceptionally(error);
                    } else {
                        future.complete(next);
                    }
                });
            }
        });
    }

    // stale 回滚后的自动收敛：拓扑指纹变化才重置计数，避免同一无效图反复启动。
    private boolean planReconcile(
            RuntimeView.Draft draft,
            RuntimeView.ComponentData data,
            ComponentRuntime runtime) {
        String fingerprint = reconcileFingerprint(draft, data);
        if (!fingerprint.equals(runtime.reconcileFingerprint)) {
            runtime.reconcileFingerprint = fingerprint;
            runtime.reconcileAttempts = 0;
            runtime.suppressAutoRestart = false;
            runtime.blockedNonConvergent = false;
        }
        if (runtime.suppressAutoRestart) {
            return false;
        }
        runtime.reconcileAttempts++;
        if (runtime.reconcileAttempts >= configuration.maxReconcileIterations()) {
            runtime.blockedNonConvergent = true;
            return false;
        }
        return true;
    }

    private String reconcileFingerprint(
            RuntimeView.Draft draft,
            RuntimeView.ComponentData data) {
        Map<String, RuntimeView.BindingData> bindings =
                draft.effectiveBindings(data, Map.of());
        return data.contextId() + "|" + data.goal() + "|" + data.configRevision()
                + "|" + data.descriptor().sortedRequirements().stream()
                        .map(requirement -> {
                            RuntimeView.BindingData binding =
                                    bindings.get(requirement.key().name());
                            return requirement.key().name() + ":"
                                    + (binding == null || !binding.present()
                                            ? "-"
                                            : binding.registrationId());
                        })
                        .collect(Collectors.joining(","));
    }

    // 第一轮调度直接受影响者；提交后可能新出现的 WAITING 组件再补一轮，避免漏掉级联收敛。
    private void scheduleAfterCommit(Set<String> dirty) {
        schedule(dirty);
        RuntimeView current = view;
        Set<String> waiting = current.components.values().stream()
                .filter(component -> component.state() == ComponentState.WAITING
                        && component.goal() == ComponentGoal.RUNNING)
                .map(RuntimeView.ComponentData::handleId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        schedule(waiting);
    }

    // Context 处置先原子发布 DISPOSING 与停止图；命名空间要等全子树清理成功后才移除。
    CompletionStage<Void> disposeContextInView(
            ContextHandleImpl handle,
            boolean rootClose) {
        Set<String> dirty;
        Set<String> subtree;
        CompletableFuture<Void> future;
        List<CompletableFuture<Void>> registrationDrains = List.of();
        synchronized (coordinator) {
            RuntimeView.ContextData data = view.contexts.get(handle.contextId());
            if (data == null || data.state() == ContextState.DISPOSED) {
                return CompletableFuture.completedFuture(null);
            }
            synchronized (contextFutures) {
                CompletableFuture<Void> existing = contextFutures.get(handle.contextId());
                if (existing != null && !existing.isCompletedExceptionally()) {
                    return existing;
                }
                future = new CompletableFuture<>();
                contextFutures.put(handle.contextId(), future);
            }

            RuntimeView.Draft draft = new RuntimeView.Draft(view);
            ExecutableCommitPlan executable = new ExecutableCommitPlan();
            dirty = new LinkedHashSet<>();
            subtree = draft.contextSubtree(handle.contextId());
            for (String contextId : subtree) {
                RuntimeView.ContextData child = draft.contexts.get(contextId);
                draft.contexts.put(contextId, child.withState(ContextState.DISPOSING));
            }
            for (RuntimeView.RegistrationData registration :
                    draft.registrations.values().stream()
                            .filter(registration -> subtree.contains(registration.contextId())
                                    && registration.owner() instanceof RuntimeView.OwnerData.Host)
                            .toList()) {
                removeRegistrationInView(
                        draft,
                        registration.registrationId(),
                        executable);
            }
            Set<String> handles = draft.components.values().stream()
                    .filter(component -> subtree.contains(component.contextId()))
                    .flatMap(component ->
                            draft.ownershipDescendants(component.handleId()).stream())
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            for (String handleId : handles) {
                RuntimeView.ComponentData component = draft.components.get(handleId);
                if (component == null) {
                    continue;
                }
                draft.components.put(
                        handleId,
                        component.withGoal(ComponentGoal.DISPOSED));
            }
            Set<String> live = handles.stream()
                    .filter(handleId -> draft.components.get(handleId) != null
                            && draft.components.get(handleId).currentActivationId() != null)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            Set<String> closure = draft.dependentsClosure(live);
            detachInView(draft, closure, dirty, executable);
            for (String handleId : handles) {
                RuntimeView.ComponentData component = draft.components.get(handleId);
                if (component != null && component.currentActivationId() == null) {
                    removeComponentInView(draft, handleId);
                    executable.removedComponents.add(handleId);
                } else if (component != null) {
                    dirty.add(handleId);
                }
            }
            refreshDiagnostics(draft);
            RuntimeView next = draft.publishOnce();
            view = next;
            commitExecutable(next, List.of(), executable);
            registrationDrains = retireCommittedRegistrations(executable);
        }

        List<CompletableFuture<?>> settlements =
                new ArrayList<>(registrationDrains);
        settlements.addAll(schedule(dirty));
        CompletableFuture<Void> settlement = CompletableFuture.allOf(
                settlements.toArray(new CompletableFuture[0]));
        settlement.whenComplete((ignored, error) ->
                finalizeContext(subtree, future));
        return future;
    }

    // 清理失败时保留 FAILED Context 与诊断供重试；成功时才释放名称和句柄索引。
    private void finalizeContext(
            Set<String> subtree,
            CompletableFuture<Void> future) {
        synchronized (coordinator) {
            RuntimeView.Draft draft = new RuntimeView.Draft(view);
            boolean failed = draft.components.values().stream()
                    .filter(component -> subtree.contains(component.contextId()))
                    .anyMatch(component -> component.state()
                            == ComponentState.FAILED);
            for (String contextId : subtree) {
                RuntimeView.ContextData data = draft.contexts.get(contextId);
                if (data != null) {
                    draft.contexts.put(contextId, data.withState(
                            failed ? ContextState.FAILED : ContextState.DISPOSED));
                }
            }
            if (!failed) {
                for (String contextId : subtree) {
                    if (!contextId.equals("ctx-root")) {
                        draft.contexts.remove(contextId);
                    }
                }
            }
            refreshDiagnostics(draft);
            RuntimeView next = draft.publishOnce();
            view = next;
            for (String contextId : subtree) {
                contextFutures.remove(contextId);
                if (!failed && !contextId.equals("ctx-root")) {
                    contextHandles.remove(contextId);
                }
            }
            registrationHandles.keySet().retainAll(next.registrations.keySet());
            if (failed) {
                future.completeExceptionally(
                        new IllegalStateException("context cleanup failed"));
            } else {
                future.complete(null);
            }
        }
    }

    // 同一事务中嵌套处置 Context 时只保留最外层，避免子树 settlement 被重复聚合。
    private Set<String> outermostContextDisposals(Set<String> requested) {
        RuntimeView current = view;
        Set<String> result = new LinkedHashSet<>();
        for (String candidate : requested) {
            boolean covered = requested.stream().anyMatch(ancestor ->
                    !ancestor.equals(candidate)
                            && current.contexts.containsKey(ancestor)
                            && current.contexts.get(candidate) != null
                            && current.isInSubtree(candidate, ancestor));
            if (!covered) {
                result.add(candidate);
            }
        }
        return result;
    }

    // 事务内处置在组件收敛完成后才结算；这里为外层 Context 创建去重的最终化 Future。
    private CompletableFuture<Void> settleContextDisposal(
            String contextId,
            CompletableFuture<Void> prerequisite) {
        CompletableFuture<Void> future;
        Set<String> subtree;
        Set<String> components;
        synchronized (coordinator) {
            RuntimeView current = view;
            if (!current.contexts.containsKey(contextId)) {
                return CompletableFuture.completedFuture(null);
            }
            synchronized (contextFutures) {
                CompletableFuture<Void> existing = contextFutures.get(contextId);
                if (existing != null && !existing.isCompletedExceptionally()) {
                    return existing;
                }
                future = new CompletableFuture<>();
                contextFutures.put(contextId, future);
            }
            subtree = current.contextSubtree(contextId);
            components = current.components.values().stream()
                    .filter(component -> subtree.contains(component.contextId()))
                    .map(RuntimeView.ComponentData::handleId)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }

        CompletableFuture<Void> subtreeSettlement = CompletableFuture.allOf(
                schedule(components).toArray(new CompletableFuture[0]));
        CompletableFuture<Void> settlement = prerequisite == null
                ? subtreeSettlement
                : CompletableFuture.allOf(prerequisite, subtreeSettlement);
        settlement.whenComplete((ignored, error) ->
                finalizeContext(subtree, future));
        return future;
    }
    // 诊断随代际整体重建并排序；只保存稳定 DTO，不引用 Throwable、实例或 Class。
    private void refreshDiagnostics(RuntimeView.Draft draft) {
        List<RuntimeDiagnostic> diagnostics = new ArrayList<>();
        for (RuntimeView.ComponentData component : draft.components.values()) {
            if (component.state() == ComponentState.WAITING
                    && component.goal() == ComponentGoal.RUNNING) {
                for (CapabilityRequirement requirement
                        : component.descriptor().sortedRequirements()) {
                    if (requirement.mode() != CapabilityRequirement.Mode.REQUIRED) {
                        continue;
                    }
                    boolean present = draft.resolve(
                            component.contextId(),
                            requirement.key()).isPresent();
                    if (!present) {
                        diagnostics.add(new RuntimeDiagnostic(
                                DiagnosticCode.MISSING_CAPABILITY,
                                component.handleId(),
                                "missing required capability "
                                        + requirement.key().name()));
                    }
                }
                ComponentRuntime runtime = components.get(component.handleId());
                if (runtime != null && runtime.blockedNonConvergent) {
                    diagnostics.add(new RuntimeDiagnostic(
                            DiagnosticCode.NON_CONVERGENT_RECONCILE,
                            component.handleId(),
                            "reconcile did not converge after "
                                    + configuration.maxReconcileIterations()
                                    + " attempts"));
                }
                if (runtime != null && runtime.suppressAutoRestart
                        && runtime.lastStartError.startsWith("binding cycle")) {
                    diagnostics.add(new RuntimeDiagnostic(
                            DiagnosticCode.BINDING_CYCLE,
                            component.handleId(),
                            runtime.lastStartError));
                }
            }
            if (component.state() == ComponentState.FAILED) {
                ComponentRuntime runtime = components.get(component.handleId());
                String startError = runtime == null ? "" : runtime.lastStartError;
                String cleanupError = runtime == null ? "" : runtime.lastCleanupError;
                if (!startError.isBlank()) {
                    diagnostics.add(new RuntimeDiagnostic(
                            DiagnosticCode.ACTIVATION_FAILED,
                            component.handleId(),
                            startError));
                }
                if (!cleanupError.isBlank()) {
                    diagnostics.add(new RuntimeDiagnostic(
                            DiagnosticCode.CLEANUP_FAILED,
                            component.handleId(),
                            cleanupError));
                }
                if (startError.isBlank() && cleanupError.isBlank()) {
                    diagnostics.add(new RuntimeDiagnostic(
                            DiagnosticCode.ACTIVATION_FAILED,
                            component.handleId(),
                            "component failed"));
                }
            }
        }
        draft.diagnostics.clear();
        draft.diagnostics.addAll(diagnostics.stream().sorted().toList());
    }

    private static Reject reject(
            DiagnosticCode code,
            String target,
            String message) {
        return new Reject(new RuntimeDiagnostic(code, target, message));
    }

    private static <T> CompletableFuture<T> failedFuture(String message) {
        CompletableFuture<T> future = new CompletableFuture<>();
        future.completeExceptionally(new IllegalStateException(message));
        return future;
    }

    private record PostCommitPlan(
            List<ChildMountPlan<?>> children,
            Set<String> dirty,
            ExecutableCommitPlan executable) {
    }

    private record ComponentTerminal(ComponentState state) {
    }

    // stale 表示按最新代际重试；非 stale 失败才作为组件启动失败保留并要求显式 retry。
    private record CommitDecision(
            boolean success,
            boolean stale,
            boolean suppressCycle,
            String message) {
    }

    sealed interface Intent permits
            ProvideIntent,
            RevokeIntent,
            ChildContextIntent,
            MountIntent,
            ReconfigureIntent,
            DisposeIntent,
            ContextDisposeIntent {
    }

    private record ProvideIntent(
            RegistrationHandleImpl handle,
            ContextHandleImpl context,
            CapabilityKey<?> key,
            Object value) implements Intent {
    }

    private record RevokeIntent(
            RegistrationHandleImpl handle) implements Intent {
    }

    private record ChildContextIntent(
            ContextHandleImpl parent,
            String name,
            ContextHandleImpl handle) implements Intent {
    }

    record MountIntent<C>(
            ContextHandleImpl context,
            String mountId,
            PreparedComponent<C> prepared,
            ComponentHandleImpl<C> handle) implements Intent {
    }

    private record ReconfigureIntent<C>(
            ComponentHandleImpl<C> handle,
            Object config,
            long expectedRevision,
            boolean equivalent) implements Intent {
    }

    private record ProvisionalConfig(Object config, long revision) {
    }

    private record DisposeIntent(
            ComponentHandleImpl<?> handle) implements Intent {
    }

    private record ContextDisposeIntent(
            ContextHandleImpl handle) implements Intent {
    }

    private static final class Reject extends RuntimeException {
        private final RuntimeDiagnostic diagnostic;

        private Reject(RuntimeDiagnostic diagnostic) {
            super(diagnostic.message(), null, false, false);
            this.diagnostic = diagnostic;
        }

        private RuntimeDiagnostic diagnostic() {
            return diagnostic;
        }
    }

    // 宿主事务回调的私有记录器：只积累 Intent，不直接修改视图，失败事务中的临时句柄随之失效。
    private final class MutationRecorder implements RuntimeTransaction {
        private final List<Intent> intents = new ArrayList<>();
        private final Map<String, ProvisionalConfig> provisionalConfigs = new HashMap<>();
        @Override
        public <T> RegistrationHandle provide(
                ContextHandle context,
                CapabilityKey<T> key,
                T value) {
            Objects.requireNonNull(context, "context");
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(value, "value");
            RegistrationHandleImpl handle = new RegistrationHandleImpl(
                    DefaultKnotraRuntime.this,
                    Sequences.registration());
            intents.add(new ProvideIntent(
                    handle,
                    (ContextHandleImpl) context,
                    key,
                    value));
            return handle;
        }

        @Override
        public void revoke(RegistrationHandle registration) {
            Objects.requireNonNull(registration, "registration");
            RegistrationHandleImpl handle;
            if (registration instanceof ProvidedImpl<?> provided) {
                provided.requireFresh("revoke");
                handle = provided.registration();
            } else if (registration instanceof RegistrationHandleImpl internal) {
                handle = internal;
            } else {
                handle = null;
            }
            if (handle == null || handle.runtime != DefaultKnotraRuntime.this) {
                throw new IllegalArgumentException(
                        "registration handle does not belong to this runtime");
            }
            intents.add(new RevokeIntent(handle));
        }

        @Override
        public ContextHandle childContext(ContextHandle parent, String name) {
            Objects.requireNonNull(parent, "parent");
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("context name must not be blank");
            }
            ContextHandleImpl handle = new ContextHandleImpl(
                    DefaultKnotraRuntime.this,
                    Sequences.context(name));
            intents.add(new ChildContextIntent(
                    (ContextHandleImpl) parent,
                    name,
                    handle));
            return handle;
        }

        @Override
        public <C> ComponentHandle<C> mount(
                ContextHandle context,
                String mountId,
                ComponentFactory<C> factory,
                C config) {
            return mount(context, mountId, factory, config, MountOptions.DEFAULT);
        }

        @Override
        public <C> ComponentHandle<C> mount(
                ContextHandle context,
                String mountId,
                ComponentFactory<C> factory,
                C config,
                MountOptions options) {
            Objects.requireNonNull(context, "context");
            PreparedComponent<C> prepared = PreparedComponent.prepare(
                    factory,
                    config,
                    options == null ? MountOptions.DEFAULT : options);
            ComponentHandleImpl<C> handle = new ComponentHandleImpl<>(
                    DefaultKnotraRuntime.this,
                    Sequences.handle());
            provisionalConfigs.put(
                    handle.handleId(),
                    new ProvisionalConfig(prepared.config(), 1));
            intents.add(new MountIntent<>(
                    (ContextHandleImpl) context,
                    mountId,
                    prepared,
                    handle));
            return handle;
        }

        @Override
        public ComponentHandle<io.knotra.NoConfig> mount(
                ContextHandle context,
                String mountId,
                ComponentFactory<io.knotra.NoConfig> factory) {
            return mount(context, mountId, factory, io.knotra.NoConfig.INSTANCE, MountOptions.DEFAULT);
        }

        @Override
        public ComponentHandle<io.knotra.NoConfig> mount(
                ContextHandle context,
                String mountId,
                ComponentFactory<io.knotra.NoConfig> factory,
                MountOptions options) {
            return mount(context, mountId, factory, io.knotra.NoConfig.INSTANCE, options);
        }

        @Override
        public <C> ComponentHandle<C> reconfigure(
                ComponentHandle<C> handle,
                C config) {
            Objects.requireNonNull(handle, "handle");
            Objects.requireNonNull(
                    config,
                    "config (use NoConfig.INSTANCE for components without configuration)");
            if (!(handle instanceof ComponentHandleImpl<C> typed)
                    || typed.runtime != DefaultKnotraRuntime.this
                    || !ownsOrProvisionallyOwns(typed)) {
                throw new IllegalArgumentException(
                        "component handle does not belong to this runtime");
            }
            PreparedComponent<?> prepared = preparedFor(typed);
            ProvisionalConfig current = provisionalConfigFor(typed, prepared);
            Object normalized = normalizeFor(prepared, config);
            long expectedRevision = current.revision();
            boolean equivalent = Objects.equals(normalized, current.config());
            if (!equivalent) {
                provisionalConfigs.put(
                        typed.handleId(),
                        new ProvisionalConfig(normalized, expectedRevision + 1));
            }
            intents.add(new ReconfigureIntent<>(
                    typed,
                    normalized,
                    expectedRevision,
                    equivalent));
            return typed;
        }

        @Override
        public void dispose(ComponentHandle<?> handle) {
            Objects.requireNonNull(handle, "handle");
            if (!(handle instanceof ComponentHandleImpl<?> typed)
                    || typed.runtime != DefaultKnotraRuntime.this
                    || !ownsOrProvisionallyOwns(typed)) {
                throw new IllegalArgumentException(
                        "component handle does not belong to this runtime");
            }
            intents.add(new DisposeIntent(typed));
        }

        @Override
        public void dispose(ContextHandle context) {
            Objects.requireNonNull(context, "context");
            if (!(context instanceof ContextHandleImpl handle)
                    || handle.runtime != DefaultKnotraRuntime.this) {
                throw new IllegalArgumentException(
                        "context handle does not belong to this runtime");
            }
            intents.add(new ContextDisposeIntent(handle));
        }

        private boolean ownsOrProvisionallyOwns(ComponentHandleImpl<?> handle) {
            for (Intent intent : intents) {
                if (intent instanceof MountIntent<?> mount
                        && mount.handle().handleId().equals(handle.handleId())) {
                    return true;
                }
            }
            return componentHandles.get(handle.handleId()) == handle;
        }

        private PreparedComponent<?> preparedFor(ComponentHandleImpl<?> handle) {
            for (Intent intent : intents) {
                if (intent instanceof MountIntent<?> mount
                        && mount.handle().handleId().equals(handle.handleId())) {
                    return mount.prepared();
                }
            }
            ComponentRuntime runtime = components.get(handle.handleId());
            if (runtime != null) {
                return runtime.prepared;
            }
            throw new IllegalArgumentException(
                    "component handle does not belong to this runtime");
        }

        private ProvisionalConfig provisionalConfigFor(
                ComponentHandleImpl<?> handle,
                PreparedComponent<?> prepared) {
            ProvisionalConfig provisional = provisionalConfigs.get(handle.handleId());
            if (provisional != null) {
                return provisional;
            }
            ComponentRuntime runtime = components.get(handle.handleId());
            return runtime == null
                    ? new ProvisionalConfig(prepared.config(), 1)
                    : new ProvisionalConfig(runtime.desiredConfig, runtime.desiredRevision);
        }


        private Object normalizeFor(PreparedComponent<?> prepared, Object rawConfig) {
            try {
                return Objects.requireNonNull(
                        prepared.normalize(rawConfig),
                        "config normalizer returned null");
            } catch (Exception error) {
                throw new PreparedComponent.InvalidConfigException(
                        LifecycleScopeImpl.safeError(error),
                        error);
            }
        }
    }
}
