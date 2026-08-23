package io.knotra.internal;

import io.knotra.ActivationContext;
import io.knotra.ActivationState;
import io.knotra.AdvancedRuntime;
import io.knotra.PendingOperationsSnapshot;
import io.knotra.PublicationChange;
import io.knotra.CapabilityKey;
import io.knotra.CapabilityRequirement;
import io.knotra.CapabilityUnavailableException;
import io.knotra.ComponentFactory;
import io.knotra.ComponentGoal;
import io.knotra.ComponentOrigin;
import io.knotra.ComponentState;
import io.knotra.ConfiguredMountHandle;
import io.knotra.ContextHandle;
import io.knotra.ContextInfo;
import io.knotra.ContextState;
import io.knotra.FailureInfo;
import io.knotra.FailurePhase;
import io.knotra.DiagnosticCode;
import io.knotra.DynamicCapabilityClosedException;
import io.knotra.KnotraConfig;
import io.knotra.KnotraRuntime;
import io.knotra.LifecycleState;
import io.knotra.MountHandle;
import io.knotra.MountNotActiveException;
import io.knotra.NoConfig;
import io.knotra.MountOptions;
import io.knotra.Registration;
import io.knotra.RegistrationHandle;
import io.knotra.RuntimeDiagnostic;
import io.knotra.RuntimeSnapshot;
import io.knotra.RuntimeTransaction;
import io.knotra.Settlement;
import io.knotra.SettlementReport;
import io.knotra.StagedRegistration;
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
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Runtime 内核的默认实现，拥有宿主事务、Activation 状态机和组件过渡调度。
 *
 * <p>已提交结构保存在 volatile 的 {@link RuntimeView} 中；所有草稿校验、代际发布和可执行索引同步
 * 都在 {@code coordinator} 临界区内完成。Factory、normalizer 和用户 {@code start()} 不持有协调器锁，因此
 * 慢用户代码只能阻塞自身 Activation，不能阻塞其他宿主事务或 Snapshot。</p>
 *
 * <p>Activation 最终提交采用固定管线：prepublish 阶段在协调器内校验候选、冻结暂存注册与子挂载、
 * 构造视图/索引草稿并预留 dirty 过渡（除可取消预约外不修改 live 对象）；随后把
 * {@link ActivationCommitCandidate#nextState()} 赋给 {@code published} 作为唯一不可逆 final publish；
 * owner/activation 效果在发布后以纯赋值显式 apply，预约驱动、lease 排空与额外调度在协调器外执行。
 * final publish 之后的任何故障都不回滚已提交 child/registration/owner，原始过渡 future 以阶段文本
 * 异常完成；prepublish 失败则取消本候选预约，并从最新代际构造一次 STOPPING 中止候选供清理收敛。</p>
 *
 * <p>锁顺序约定：协调器锁优先；Context 处置临界区会在协调器内再取 {@code contextFutures}；
 * 完成组件过渡时协调器可嵌套 {@link ComponentRuntime} 的过渡链锁。LifecycleScope 释放器、
 * 用户回调和 Future 回调不得反向获取协调器锁。</p>
 */
final class DefaultKnotraRuntime implements KnotraRuntime {
    final KnotraConfig configuration;
    private final LongSupplier ticker;
    // 结构一致性主锁：保护视图草稿、代际发布、可执行索引同步和过渡状态裁决。
    final Object coordinator = new Object();
    final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    // 只在协调器内整体替换；无锁读取方拿到的是 view 与 live membership 的同代状态。
    private volatile PublishedKernelState published;

    // Context 处置去重在协调器内嵌套 contextFutures；单组件 dispose 用独立请求锁合并并发调用。
    private final Map<String, CompletableFuture<Void>> contextFutures =
            new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<ComponentState>> disposeRequests =
            new ConcurrentHashMap<>();
    private final Map<String, RequestPending> disposeRequestPending =
            new ConcurrentHashMap<>();
    private final Map<String, ContextDisposalPending> contextDisposalPending =
            new ConcurrentHashMap<>();
    private final RetiredProviderLeaseRegistry retiredProviderLeases =
            new RetiredProviderLeaseRegistry();
    // close 先于新事务置位；失败的未来可被替换以便重试关闭，成功后复用同一结果。
    private final AtomicBoolean closing = new AtomicBoolean();
    private final AtomicReference<CompletableFuture<Void>> closeFuture =
            new AtomicReference<>();
    private volatile long closeStartNanos;
    private volatile boolean closeStartPresent;
    private final AdvancedRuntime advanced = new RuntimeAdvancedFacade(this);
    private final BindingImpactAnalyzer bindingImpacts = new BindingImpactAnalyzer(this);
    private final DynamicCapabilityBroker dynamicBroker = new DynamicCapabilityBroker(this);
    // 包内测试探针：在 Activation 裁决释放协调器后、完成/清理前构造结构事务竞态。
    volatile Runnable activationDecisionProbe;
    // 包内测试探针：在发布前预约完成后暂停，用于构造 reserve/publish/drive 竞态。
    volatile Runnable transitionReservationProbe;
    // 包内测试探针：在 Activation prepublish 候选构造完成后、final publish 前注入故障。
    volatile Runnable activationPrepublishProbe;
    // 包内测试探针：在 final publish 计算前注入故障，覆盖 nextState() 抛出的恢复路径。
    volatile Runnable activationFinalPublishProbe;
    // 包内测试探针：在 final publish 赋值后、效果 apply 前注入提交后效果故障。
    volatile Runnable activationPostPublishEffectProbe;
    // 包内测试探针：在非终态视图发布后、过渡驱动前观察 whenSettled 的可见行为。
    volatile Runnable transitionPublicationProbe;
    // 包内测试探针：在 whenSettled 读取 published 后、进入 chainLock 前暂停；探针须先清空自身再阻塞。
    volatile Runnable whenSettledObservationProbe;
    // 包内测试探针：在第 N 个过渡预约创建前注入故障；参数为即将创建的预约序号。
    volatile IntConsumer transitionReservationFaultProbe;
    // 包内测试探针：在第 N 个已提交 provider lease retire 前注入故障。
    volatile IntConsumer providerLeaseRetireFaultProbe;
    // 包内测试探针：在中止候选 final publish 后注入提交后故障。
    volatile Runnable activationRollbackCommitProbe;
    private final DiagnosticSupport diagnostics = new DiagnosticSupport(this);

    DefaultKnotraRuntime(KnotraConfig configuration) {
        this(configuration, System::nanoTime);
    }

    // 包内测试可注入单调时钟；生产路径始终使用 System.nanoTime()。
    DefaultKnotraRuntime(KnotraConfig configuration, LongSupplier ticker) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.ticker = Objects.requireNonNull(ticker, "ticker");
        this.published = PublishedKernelState.initial(
                new ContextHandleImpl(this, "ctx-root"));
    }

    long pendingTime() {
        return ticker.getAsLong();
    }

    RuntimeView currentView() {
        return published.view;
    }

    PublishedKernelState publishedState() {
        return published;
    }

    DynamicCapabilityBroker dynamicBroker() {
        return dynamicBroker;
    }

    @Override
    public String runtimeId() {
        return configuration.runtimeId();
    }

    @Override
    public ContextHandle root() {
        return published.index.contextHandles.get("ctx-root");
    }

    @Override
    public AdvancedRuntime advanced() {
        return advanced;
    }

    public RuntimeSnapshot snapshot() {
        PublishedKernelState state = published;
        RuntimeSnapshot partial = state.view.snapshotWithoutScopes();
        List<RuntimeSnapshot.LifecycleScopeSnapshot> scopes =
                state.index.activations.values().stream()
                .flatMap(activation ->
                        activation.scope.snapshots(activation.activationId).stream())
                .sorted(Comparator.comparing(
                        RuntimeSnapshot.LifecycleScopeSnapshot::scopeId))
                .toList();
        return new RuntimeSnapshot(
                partial.generation(),
                partial.contexts(),
                partial.mounts(),
                partial.activations(),
                partial.registrations(),
                scopes,
                partial.diagnostics());
    }

    /**
     * 在传播收敛后构建不可变的结算报告。受影响挂载的失败不会使结算 future 异常完成；
     * 调用方应检查报告中的挂载结果。
     */
    SettlementReport settlementReport(
            long generation,
            Set<String> affectedMounts,
            Map<String, String> removedMountIds) {
        RuntimeSnapshot snapshot = snapshot();
        Map<String, RuntimeSnapshot.MountSnapshot> current = snapshot.mounts().stream()
                .collect(Collectors.toMap(RuntimeSnapshot.MountSnapshot::handleId, mount -> mount));
        List<SettlementReport.MountOutcome> outcomes = affectedMounts.stream()
                .sorted()
                .map(handleId -> {
                    RuntimeSnapshot.MountSnapshot mount = current.get(handleId);
                    ComponentState state = mount != null
                            ? mount.state()
                            : ComponentState.DISPOSED;
                    String mountId = mount != null
                            ? mount.mountId()
                            : removedMountIds.getOrDefault(handleId, handleId);
                    List<RuntimeDiagnostic> diagnostics = mount != null
                            ? snapshot.diagnostics().stream()
                                    .filter(diagnostic -> handleId.equals(diagnostic.targetId()))
                                    .toList()
                            : List.of();
                    return new SettlementReport.MountOutcome(handleId, mountId, state, diagnostics);
                })
                .toList();
        List<RuntimeDiagnostic> operationDiagnostics = outcomes.stream()
                .flatMap(outcome -> outcome.diagnostics().stream())
                .toList();
        return new SettlementReport(generation, outcomes, operationDiagnostics);
    }

    <T> Registration<T> register(ContextHandle context, CapabilityKey<T> key, T value) {
        TransactionReceipt<StagedRegistration<T>> receipt =
                transact(transaction -> transaction.provide(context, key, value));
        StagedRegistration<T> staged = receipt.value();
        return new RegistrationImpl<>(
                staged instanceof StagedRegistrationImpl<T> internal
                        ? internal.registration()
                        : null,
                key,
                context,
                receipt.settlement());
    }

    Settlement revokeRegistration(RegistrationHandle registration) {
        if (registration instanceof RegistrationImpl<?> typed) {
            if (typed.runtime() != this) {
                throw new IllegalArgumentException(
                        "registration handle does not belong to this runtime");
            }
            return revoke(typed);
        }
        TransactionReceipt<Void> receipt = transact(transaction -> {
            transaction.revoke(registration);
            return null;
        });
        return receipt.settlement();
    }

    <T> Registration<T> replace(RegistrationImpl<T> handle, T value) {
        handle.requireFresh("replace");
        TransactionReceipt<StagedRegistration<T>> receipt = transact(transaction -> {
            transaction.revoke(handle);
            return transaction.provide(handle.context(), handle.capabilityKey(), value);
        });
        handle.markStale();
        return new RegistrationImpl<>(
                ((StagedRegistrationImpl<T>) receipt.value()).registration(),
                handle.capabilityKey(),
                handle.context(),
                receipt.settlement());
    }

    <T> Settlement revoke(RegistrationImpl<T> handle) {
        handle.requireFresh("revoke");
        TransactionReceipt<Void> receipt = transact(transaction -> {
            transaction.revoke(handle);
            return null;
        });
        handle.markStale();
        return receipt.settlement();
    }

    public <R> TransactionReceipt<R> transact(Function<RuntimeTransaction, R> action) {
        Objects.requireNonNull(action, "action");
        TransactionRecorder recorder = new TransactionRecorder(this);
        R callbackValue;
        List<Intent> intents;
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
        } finally {
            intents = recorder.intents();
            recorder.close();
        }

        long committedGeneration = 0;
        Set<String> postCommitDirty = new LinkedHashSet<>();
        Set<String> contextDisposals = new LinkedHashSet<>();
        String postCommitFailure = null;
        List<CompletableFuture<Void>> registrationDrains = List.of();
        Map<String, ProviderLeaseRuntime> retiredRegistrations = Map.of();
        ExecutableCommitPlan executable = new ExecutableCommitPlan();
        List<ComponentRuntime.Reservation> reservations = new ArrayList<>();
        boolean published = false;
        RuntimeView committedView = null;
        try {
            synchronized (coordinator) {
                PublishedKernelState state = this.published;
                if (closing.get()) {
                    throw new TransactionRejectedException(List.of(new RuntimeDiagnostic(
                            DiagnosticCode.INVALID_LIFECYCLE_OPERATION,
                            configuration.runtimeId(),
                            "runtime is closing")));
                }
                if (intents.isEmpty()) {
                    return new TransactionReceipt<>(
                            callbackValue,
                            DefaultSettlement.empty(state.view.generation));
                }

                RuntimeView.Draft draft = new RuntimeView.Draft(state.view);
                KernelStateDraft indexDraft = new KernelStateDraft(state);
                Set<String> dirty = new LinkedHashSet<>();
                boolean viewChanged = false;
                try {
                    for (Intent intent : intents) {
                        viewChanged |= applyIntent(draft, intent, dirty, executable);
                    }
                    bindingImpacts.markBindingImpacts(draft, dirty, executable);
                    if (!viewChanged) {
                        return new TransactionReceipt<>(
                                callbackValue,
                                DefaultSettlement.empty(state.view.generation));
                    }
                    reserveDraftTransitions(
                            draft,
                            dirty,
                            executable,
                            indexDraft,
                            reservations);
                    runTransitionReservationProbe();
                    diagnostics.refresh(draft);
                    RuntimeView next = draft.publishOnce();
                    commitExecutable(next, intents, executable, indexDraft);
                    this.published = indexDraft.publish(next);
                    committedView = next;
                    published = true;
                    retiredRegistrations = Map.copyOf(executable.retiredRegistrations);
                    committedGeneration = next.generation;
                    postCommitDirty.addAll(dirty);
                    contextDisposals.addAll(executable.contextDisposals);
                    runTransitionPublicationProbe();
                } catch (Reject rejection) {
                    throw new TransactionRejectedException(List.of(rejection.diagnostic()));
                }
            }
        } finally {
            if (published) {
                CommittedLeaseRetirement retirement =
                        retireCommittedRegistrations(
                                retiredRegistrations,
                                "host transaction postcommit");
                registrationDrains = retirement.drains();
                postCommitFailure = appendPostCommitFailure(
                        postCommitFailure,
                        retirement.failure());
                postCommitFailure = appendPostCommitFailure(
                        postCommitFailure,
                        executeReservedTransitions(reservations));
            } else {
                completeCancelledTransitions(
                        cancelCreatedReservationFutures(reservations));
            }
        }

        final long committedGenerationResult = committedGeneration;
        Set<String> affectedMounts = new LinkedHashSet<>(postCommitDirty);
        affectedMounts.addAll(executable.mounts.keySet());
        affectedMounts.addAll(executable.removedComponents.keySet());
        affectedMounts.addAll(executable.configs.keySet());
        Map<String, String> removedMountIds = new HashMap<>();
        executable.removedComponents.forEach((handleId, removed) ->
                removedMountIds.putIfAbsent(handleId, removed.mountId()));
        executable.reportedRemovedMounts.forEach((handleId, removed) ->
                removedMountIds.putIfAbsent(handleId, removed.mountId()));
        OperationSettlement operationSettlement =
                new OperationSettlement(this, affectedMounts, removedMountIds);

        CompletableFuture<Void> componentSettlement =
                operationSettlement.await(postCommitDirty);
        List<CompletableFuture<?>> settlements = new ArrayList<>();
        settlements.add(componentSettlement);
        if (postCommitFailure != null) {
            settlements.add(failedFuture(postCommitFailure));
        }
        settlements.addAll(registrationDrains);
        for (String contextId : ContextTrees.outermostDisposals(
                committedView, contextDisposals)) {
            settlements.add(settleContextDisposal(contextId, componentSettlement));
        }
        CompletableFuture<Void> settlement = CompletableFuture.allOf(
                settlements.toArray(CompletableFuture[]::new));
        Settlement committedSettlement = new DefaultSettlement(
                committedGenerationResult,
                settlement.thenApply(ignored -> operationSettlement.report(
                        committedGenerationResult)));
        return new TransactionReceipt<>(callbackValue, committedSettlement);
    }

    @Override
    public CompletionStage<Void> closeAsync() {
        CompletableFuture<Void> previous = closeFuture.get();
        CompletableFuture<Void> created = closeFuture.updateAndGet(existing ->
                existing != null && !existing.isCompletedExceptionally()
                        ? existing
                        : new CompletableFuture<>());
        if (created != previous) {
            closeStartNanos = pendingTime();
            closeStartPresent = true;
        }
        closing.set(true);
        ContextHandleImpl root = published.index.contextHandles.get("ctx-root");
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

    PendingOperationsSnapshot pendingOperations() {
        long now = ticker.getAsLong();
        PublishedKernelState state = published;
        List<PendingOperationSample> samples = new ArrayList<>();
        Set<String> transitionTargets = new LinkedHashSet<>();
        for (ComponentRuntime component : state.index.components.values()) {
            PendingOperationSample sample = component.pendingSnapshot();
            if (sample != null) {
                samples.add(sample);
                transitionTargets.add(sample.targetId());
            }
        }
        for (RequestPending request : disposeRequestPending.values()) {
            CompletableFuture<ComponentState> future =
                    disposeRequests.get(request.targetId());
            if (future == null || future.isDone()
                    || transitionTargets.contains(request.targetId())) {
                continue;
            }
            samples.add(new PendingOperationSample(
                    PendingOperationsSnapshot.Kind.COMPONENT_TRANSITION,
                    request.targetId(),
                    PendingOperationsSnapshot.WaitType.COMPONENT,
                    request.startNanos(),
                    "mount dispose waiting for component"));
        }
        for (ContextDisposalPending context : contextDisposalPending.values()) {
            samples.add(new PendingOperationSample(
                    PendingOperationsSnapshot.Kind.CONTEXT_DISPOSAL,
                    context.contextId(),
                    PendingOperationsSnapshot.WaitType.CONTEXT,
                    context.startNanos(),
                    "context disposal"));
        }
        for (ActivationRuntime activation : state.index.activations.values()) {
            samples.addAll(activation.scope.pendingCleanup());
            DynamicCallGate.ActiveSnapshot calls =
                    activation.dynamicCalls.pendingSnapshot();
            if (calls.draining() && calls.active() > 0 && calls.started()) {
                samples.add(new PendingOperationSample(
                        PendingOperationsSnapshot.Kind.CONSUMER_LEASE,
                        activation.owner.handleId(),
                        PendingOperationsSnapshot.WaitType.LEASE_RELEASE,
                        calls.startNanos(),
                        "dynamic calls=" + calls.active()
                                + " activation=" + activation.activationId));
            }
        }
        for (ProviderLeaseRuntime leases : retiredProviderLeases.pending()) {
            ProviderLeaseRuntime.LeaseSnapshot snapshot =
                    leases.pendingSnapshot();
            if (snapshot.retired() && snapshot.leases() > 0 && snapshot.started()) {
                samples.add(new PendingOperationSample(
                        PendingOperationsSnapshot.Kind.PROVIDER_LEASE,
                        leases.registrationId(),
                        PendingOperationsSnapshot.WaitType.LEASE_RELEASE,
                        snapshot.startNanos(),
                        "provider leases=" + snapshot.leases()));
            }
        }
        CompletableFuture<Void> close = closeFuture.get();
        if (closing.get() && close != null && !close.isDone()
                && closeStartPresent) {
            samples.add(new PendingOperationSample(
                    PendingOperationsSnapshot.Kind.RUNTIME_CLOSE,
                    configuration.runtimeId(),
                    PendingOperationsSnapshot.WaitType.RUNTIME_DRAIN,
                    closeStartNanos,
                    "runtime close"));
        }
        List<PendingOperationsSnapshot.Operation> operations =
                samples.stream().map(sample -> sample.toOperation(now)).toList();
        return new PendingOperationsSnapshot(closing.get(), operations, 0);
    }

    boolean hasLiveRegistration(String registrationId) {
        return published.view.registrations.containsKey(registrationId);
    }

    <T> Optional<T> findInContext(String contextId, CapabilityKey<T> key) {
        Objects.requireNonNull(key, "key");
        // 固定本地状态引用，同一次 require/find 不会被并发发布拆到两个代际。
        RuntimeView current = published.view;
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

    ContextInfo contextInfo(String contextId) {
        RuntimeView current = published.view;
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
        RuntimeView.ContextData data = published.view.contexts.get(contextId);
        return data == null ? ContextState.DISPOSED : data.state();
    }

    ComponentState componentState(String handleId) {
        RuntimeView.ComponentData data = published.view.components.get(handleId);
        if (data != null) {
            return data.state();
        }
        return ComponentState.DISPOSED;
    }

    ComponentGoal componentGoal(String handleId) {
        RuntimeView.ComponentData data = published.view.components.get(handleId);
        return data == null ? ComponentGoal.DISPOSED : data.goal();
    }

    long componentConfigRevision(String handleId) {
        RuntimeView.ComponentData data = published.view.components.get(handleId);
        return data == null ? 0 : data.configRevision();
    }

    CompletionStage<ComponentState> whenSettled(String handleId) {
        PublishedKernelState state = published;
        ComponentRuntime runtime = state.index.components.get(handleId);
        if (runtime == null) {
            RuntimeView.ComponentData data = state.view.components.get(handleId);
            return CompletableFuture.completedFuture(
                    data == null ? ComponentState.DISPOSED : data.state());
        }
        Runnable observationProbe = whenSettledObservationProbe;
        if (observationProbe != null) {
            observationProbe.run();
        }
        return runtime.observeSettled(() -> {
            // 必须在 chainLock 内确认无 live transition 后读取当前 published：
            // 调用前捕获的旧代可能在停顿期间被完整处置并清空槽位。
            PublishedKernelState current = published;
            if (current.index.components.get(handleId) == null) {
                return ComponentState.DISPOSED;
            }
            RuntimeView.ComponentData data = current.view.components.get(handleId);
            return data == null ? ComponentState.DISPOSED : data.state();
        });
    }

    <C> ConfiguredMountHandleImpl<C> requireActiveConfigured(
            ConfiguredMountHandleImpl<C> handle, Duration timeout) {
        return (ConfiguredMountHandleImpl<C>) requireActive(handle, timeout);
    }

    MountHandle requireActive(MountHandleImpl handle, Duration timeout) {
        AwaitSupport.Outcome<ComponentState> outcome =
                AwaitSupport.await(handle.whenSettled(), timeout);
        if (outcome.settledNormally() && outcome.result() == ComponentState.ACTIVE) {
            return handle;
        }
        DiagnosticSupport.FailureSnapshot snapshot = diagnostics.failureSnapshot(handle.handleId());
        if (snapshot.state() == ComponentState.ACTIVE) {
            return handle;
        }
        ComponentState failureState = outcome.settledNormally()
                ? outcome.result()
                : snapshot.state();
        List<RuntimeDiagnostic> diagnostics = snapshot.state() == failureState
                ? new ArrayList<>(snapshot.diagnostics())
                : new ArrayList<>();
        RuntimeDiagnostic detail = this.diagnostics.failureDetail(
                handle.handleId(),
                outcome.interrupted(),
                outcome.failure());
        if (detail != null) {
            diagnostics.add(detail);
        }
        throw new MountNotActiveException(
                failureState,
                handle.handleId(),
                handle.mountId(),
                handle.componentId(),
                handle.factoryId(),
                handle.contextId(),
                timeout,
                diagnostics);
    }

    <C> CompletionStage<ComponentState> reconfigure(
            ConfiguredMountHandleImpl<C> handle,
            C config) {
        try {
            TransactionReceipt<Void> receipt = transact(transaction -> {
                transaction.reconfigure(handle, config);
                return null;
            });
            return receipt.settlement().whenSettled().thenApply(report -> report
                    .outcome(handle.handleId())
                    .map(SettlementReport.MountOutcome::state)
                    .orElseGet(() -> componentState(handle.handleId())));
        } catch (TransactionRejectedException rejection) {
            return CompletableFuture.failedFuture(rejection);
        }
    }

    CompletionStage<ComponentState> retry(MountHandleImpl handle) {
        ComponentRuntime component;
        synchronized (coordinator) {
            PublishedKernelState state = published;
            component = state.index.components.get(handle.handleId());
            if (component == null
                    || state.index.componentHandles.get(handle.handleId()) != handle) {
                return failedFuture("handle does not belong to this runtime");
            }
            RuntimeView.ComponentData data = state.view.components.get(handle.handleId());
            if (data == null || data.state() != ComponentState.FAILED) {
                return failedFuture("retry is only valid for a failed component");
            }
            component.requestRetryLocked(component.failedCleanup() != null
                    ? ComponentRuntime.RetryIntent.CLEANUP
                    : ComponentRuntime.RetryIntent.ACTIVATION);
        }
        return component.enqueue(this, executor);
    }

    CompletionStage<ComponentState> dispose(MountHandleImpl handle) {
        PublishedKernelState state = published;
        if (isAlreadyDisposed(handle, state)) {
            return CompletableFuture.completedFuture(ComponentState.DISPOSED);
        }
        // 只合并仍在执行的 dispose；已完成请求按 identity 移除，后续显式调用可建立新请求。
        synchronized (disposeRequests) {
            state = published;
            if (isAlreadyDisposed(handle, state)) {
                return CompletableFuture.completedFuture(ComponentState.DISPOSED);
            }
            CompletableFuture<ComponentState> existing =
                    disposeRequests.get(handle.handleId());
            if (existing != null && !existing.isDone()) {
                return existing;
            }
            if (existing != null) {
                removeDisposeRequest(handle.handleId(), existing);
            }
            CompletableFuture<ComponentState> request = new CompletableFuture<>();
            RequestPending pending =
                    new RequestPending(handle.handleId(), pendingTime());
            disposeRequests.put(handle.handleId(), request);
            disposeRequestPending.put(handle.handleId(), pending);
            try {
                transact(transaction -> {
                    transaction.dispose(handle);
                    return null;
                });
            } catch (TransactionRejectedException rejection) {
                removeDisposeRequest(handle.handleId(), request);
                request.completeExceptionally(rejection);
                return request;
            }
            settleDisposeRequest(handle.handleId(), request);
            return request;
        }
    }

    private boolean isAlreadyDisposed(
            MountHandleImpl handle,
            PublishedKernelState state) {
        if (handle.runtime != this) {
            return false;
        }
        RuntimeView.ComponentData data = state.view.components.get(handle.handleId());
        return data == null
                || (data.state() == ComponentState.DISPOSED
                        && data.goal() == ComponentGoal.DISPOSED);
    }

    private void settleDisposeRequest(
            String handleId,
            CompletableFuture<ComponentState> request) {
        // whenSettled 可能观察到 STOPPING 的中间态；重新排队直到旧 Activation 清理完成。
        whenSettled(handleId).whenComplete((state, error) -> {
            if (state == ComponentState.STOPPING) {
                try {
                    executor.execute(() -> settleDisposeRequest(handleId, request));
                } catch (RejectedExecutionException rejectionError) {
                    removeDisposeRequest(handleId, request);
                    request.completeExceptionally(
                            new TransitionRejectedStateException(rejectionError));
                }
                return;
            }
            if (error != null || state != ComponentState.STOPPING) {
                removeDisposeRequest(handleId, request);
            }
            if (error != null) {
                request.completeExceptionally(error);
            } else {
                request.complete(state);
            }
        });
    }

    private void removeDisposeRequest(
            String handleId,
            CompletableFuture<ComponentState> request) {
        RequestPending pending = disposeRequestPending.get(handleId);
        if (disposeRequests.remove(handleId, request)) {
            disposeRequestPending.remove(handleId, pending);
        }
    }

    CompletionStage<Void> disposeContext(ContextHandleImpl handle) {
        if (published.index.contextHandles.get(handle.contextId()) != handle) {
            CompletableFuture<Void> rejected = new CompletableFuture<>();
            rejected.completeExceptionally(new IllegalStateException(
                    "context handle does not belong to this runtime"));
            return rejected;
        }
        return disposeContextInView(handle, false);
    }

    Object registrationValue(String registrationId) {
        PublishedKernelState state = published;
        RuntimeView.RegistrationData committed =
                state.view.registrations.get(registrationId);
        if (committed != null) {
            return committed.value();
        }
        for (ActivationRuntime activation : state.index.activations.values()) {
            RuntimeView.RegistrationData staged =
                    activation.stagedRegistrations.get(registrationId);
            if (staged != null) {
                return staged.value();
            }
        }
        return null;
    }

    void validateCapabilityType(CapabilityKey<?> key) {
        Class<?> existing = liveCapabilityType(published, key.name());
        if (existing != null && existing != key.type()) {
            throw new IllegalArgumentException(
                    "capability name already has type " + existing.getName());
        }
    }

    private Class<?> liveCapabilityType(
            PublishedKernelState state,
            String capabilityName) {
        for (RuntimeView.RegistrationData registration :
                state.view.registrations.values()) {
            if (registration.key().name().equals(capabilityName)) {
                return registration.key().type();
            }
        }
        for (ComponentRuntime component : state.index.components.values()) {
            for (CapabilityRequirement requirement
                    : component.prepared().descriptor().sortedRequirements()) {
                if (requirement.key().name().equals(capabilityName)) {
                    return requirement.key().type();
                }
            }
        }
        return null;
    }

    private Map<String, Class<?>> liveCapabilityTypes(PublishedKernelState state) {
        Map<String, Class<?>> result = new HashMap<>();
        state.view.registrations.values().forEach(registration ->
                result.putIfAbsent(
                        registration.key().name(),
                        registration.key().type()));
        state.index.components.values().forEach(component ->
                component.prepared().descriptor().sortedRequirements().forEach(requirement ->
                        result.putIfAbsent(
                                requirement.key().name(),
                                requirement.key().type())));
        return result;
    }

    boolean mountIdReserved(String contextId, String mountId) {
        RuntimeView current = published.view;
        return current.components.values().stream().anyMatch(component ->
                component.contextId().equals(contextId)
                        && component.mountId().equals(mountId));
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
            case MountIntent mount -> applyMount(draft, mount, dirty, executable);
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
                                intent.handle().registrationId(),
                                ticker)));
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
        Set<String> direct = BindingImpactAnalyzer.componentsWithBinding(
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
            MountIntent intent,
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
        MountHandleImpl handle = requireComponent(draft, intent.handle());
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
        MountHandleImpl handle = requireComponent(draft, intent.handle());
        RuntimeView.ComponentData parent = draft.components.get(handle.handleId());
        if (parent != null) {
            draft.components.put(
                    handle.handleId(),
                    parent.withGoal(ComponentGoal.DISPOSED));
            executable.reportedRemovedMounts.putIfAbsent(
                    handle.handleId(),
                    new ExecutableCommitPlan.RemovedMount(parent.mountId()));
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
            executable.removedComponents.put(
                    handle.handleId(),
                    new ExecutableCommitPlan.RemovedMount(handle.mountId()));
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
            executable.reportedRemovedMounts.putIfAbsent(
                    handleId,
                    new ExecutableCommitPlan.RemovedMount(component.mountId()));
        }
        requestContextCleanupIntents(handles);
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
                executable.removedComponents.put(
                        handleId,
                        new ExecutableCommitPlan.RemovedMount(component.mountId()));
            } else if (component != null) {
                dirty.add(handleId);
            }
        }
        return true;
    }

    // 事务与直接 Context 处置都要唤醒已失败的清理；否则子树最终化会永远停在 FAILED。
    private void requestContextCleanupIntents(Set<String> handles) {
        ExecutionIndex index = published.index;
        for (String handleId : handles) {
            ComponentRuntime component = index.components.get(handleId);
            if (component != null && component.failedCleanup() != null) {
                component.requestRetryLocked(ComponentRuntime.RetryIntent.CLEANUP);
            }
        }
    }

    // 换代只处置属于旧 Activation 的子挂载；其他 Activation 创建的同名层后代不能被误删。
    Set<String> disposeOwnershipForActivation(
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
            executable.reportedRemovedMounts.putIfAbsent(
                    handleId,
                    new ExecutableCommitPlan.RemovedMount(component.mountId()));
            if (component.currentActivationId() == null) {
                removeComponentInView(draft, handleId);
                executable.removedComponents.put(
                        handleId,
                        new ExecutableCommitPlan.RemovedMount(component.mountId()));
            } else {
                live.add(handleId);
            }
        }
        return live;
    }

    // 视图中先脱离绑定并标记 STOPPING；实际 LifecycleScope teardown 延迟到依赖方清理完成后。
    void detachInView(
            RuntimeView.Draft draft,
            Set<String> handles,
            Set<String> dirty,
            ExecutableCommitPlan executable) {
        RuntimeGraph graph = draft.graph();
        Set<String> closure = graph.dependentsClosure(draft, handles);
        List<String> ownedRegistrations =
                graph.registrationsOwnedBy(draft, closure);
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

    private MountHandleImpl requireComponent(
            RuntimeView.Draft draft,
            MountHandle candidate) {
        if (!(candidate instanceof MountHandleImpl handle)
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

    private CommittedLeaseRetirement retireCommittedRegistrations(
            Map<String, ProviderLeaseRuntime> retiredRegistrations,
            String failureScope) {
        List<CompletableFuture<Void>> drains = new ArrayList<>();
        String failure = null;
        int leaseIndex = 0;
        for (Map.Entry<String, ProviderLeaseRuntime> entry
                : retiredRegistrations.entrySet()) {
            try {
                runProviderLeaseRetireFaultProbe(leaseIndex++);
                drains.add(retireProviderLease(entry.getKey(), entry.getValue()));
            } catch (Throwable retireError) {
                failure = appendPostCommitFailure(failure, postCommitFailure(
                        failureScope,
                        "provider lease retire",
                        retireError));
            }
        }
        return new CommittedLeaseRetirement(List.copyOf(drains), failure);
    }

    private static String postCommitFailure(
            String scope,
            String stage,
            Throwable error) {
        return scope + " failed at " + stage + ": "
                + LifecycleScopeImpl.safeError(error);
    }

    private static String appendPostCommitFailure(String current, String failure) {
        if (failure == null || failure.isBlank()) {
            return current;
        }
        return current == null ? failure : current + "; " + failure;
    }

    private record CommittedLeaseRetirement(
            List<CompletableFuture<Void>> drains,
            String failure) {
    }

    private void runProviderLeaseRetireFaultProbe(int leaseIndex) {
        IntConsumer probe = providerLeaseRetireFaultProbe;
        if (probe != null) {
            probe.accept(leaseIndex);
        }
    }

    private CompletableFuture<Void> retireProviderLease(
            String registrationId,
            ProviderLeaseRuntime leases) {
        return retiredProviderLeases.retire(registrationId, leases);
    }

    // 在同一个协调器临界区内先更新索引草稿，再与 next 一起原子发布。
    private void commitExecutable(
            RuntimeView next,
            List<Intent> intents,
            ExecutableCommitPlan executable,
            KernelStateDraft indexDraft) {
        for (String activationId : executable.staleActivations) {
            ActivationRuntime activation = indexDraft.activations().get(activationId);
            if (activation != null) {
                activation.markStale();
            }
        }
        for (String handleId : executable.resetAutoRestart) {
            ComponentRuntime runtime = indexDraft.components().get(handleId);
            if (runtime != null) {
                runtime.resetAutoRestartLocked();
            }
        }
        for (String handleId : executable.removedComponents.keySet()) {
            indexDraft.components().remove(handleId);
            indexDraft.componentHandles().remove(handleId);
        }

        for (MountIntent mount : executable.mounts.values()) {
            String handleId = mount.handle().handleId();
            if (!next.components.containsKey(handleId)) {
                continue;
            }
            ComponentRuntime runtime = executable.componentRuntimes.get(handleId);
            if (runtime == null) {
                runtime = new ComponentRuntime(
                        handleId,
                        mount.context().contextId(),
                        mount.mountId(),
                        mount.prepared(),
                        coordinator);
            }
            indexDraft.components().put(handleId, runtime);
            indexDraft.componentHandles().put(handleId, mount.handle());
        }

        for (Map.Entry<String, ExecutableCommitPlan.ConfigUpdate> entry
                : executable.configs.entrySet()) {
            ComponentRuntime runtime = indexDraft.components().get(entry.getKey());
            if (runtime != null) {
                runtime.updateDesiredLocked(
                        entry.getValue().config(),
                        entry.getValue().revision());
            }
        }
        for (String registrationId : executable.retiredRegistrations.keySet()) {
            indexDraft.registrationHandles().remove(registrationId);
        }

        for (Intent intent : intents) {
            switch (intent) {
                case ProvideIntent provide -> {
                    if (next.registrations.containsKey(
                            provide.handle().registrationId())) {
                        indexDraft.registrationHandles().put(
                                provide.handle().registrationId(),
                                provide.handle());
                    }
                }
                case RevokeIntent revoke ->
                        indexDraft.registrationHandles().remove(
                                revoke.handle().registrationId());
                case ChildContextIntent child -> {
                    if (next.contexts.containsKey(child.handle().contextId())) {
                        indexDraft.contextHandles().put(
                                child.handle().contextId(), child.handle());
                    }
                }
                case ContextDisposeIntent dispose -> {
                    if (!next.contexts.containsKey(dispose.handle().contextId())) {
                        indexDraft.contextHandles().remove(dispose.handle().contextId());
                    }
                }
                default -> {
                }
            }
        }
    }

    // 先在协调器内按最新视图预约并合并过渡，再离开锁提交虚拟线程执行用户代码。
    List<CompletableFuture<ComponentState>> schedule(Set<String> dirty) {
        List<ComponentRuntime.Reservation> reservations = new ArrayList<>();
        List<CompletableFuture<ComponentState>> cancelled = List.of();
        try {
            synchronized (coordinator) {
                PublishedKernelState state = published;
                KernelStateDraft indexDraft = new KernelStateDraft(state);
                reserveDraftTransitions(
                        new RuntimeView.Draft(state.view),
                        dirty,
                        new ExecutableCommitPlan(),
                        indexDraft,
                        reservations);
                runTransitionReservationProbe();
            }
        } catch (Throwable error) {
            cancelled = cancelCreatedReservationFutures(reservations);
            reservations = List.of();
            throw error;
        } finally {
            completeCancelledTransitions(cancelled);
        }
        executeReservedTransitions(reservations);
        return reservations.stream()
                .map(ComponentRuntime.Reservation::future)
                .collect(Collectors.toList());
    }

    // 非终态视图发布前必须先占用过渡槽；观察者因此只能挂到已预约的收敛 Future 上。
    // reservations 由调用方持有：预约中途抛出时，已创建预约对调用方的取消逻辑可见，不泄漏槽位。
    private void reserveDraftTransitions(
            RuntimeView.Draft draft,
            Set<String> dirty,
            ExecutableCommitPlan executable,
            KernelStateDraft indexDraft,
            List<ComponentRuntime.Reservation> reservations) {
        for (MountIntent mount : executable.mounts.values()) {
            String handleId = mount.handle().handleId();
            if (!draft.components.containsKey(handleId)
                    || indexDraft.components().containsKey(handleId)) {
                continue;
            }
            executable.componentRuntimes.computeIfAbsent(handleId, ignored ->
                    new ComponentRuntime(
                            handleId,
                            mount.context().contextId(),
                            mount.mountId(),
                            mount.prepared(),
                            coordinator));
        }

        Set<String> stopping = new LinkedHashSet<>();
        Set<String> starting = new LinkedHashSet<>();
        for (String handleId : dirty) {
            RuntimeView.ComponentData data = draft.components.get(handleId);
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

        for (String handleId : orderForStop(
                draft, stopping, indexDraft.activations())) {
            RuntimeView.ComponentData data = draft.components.get(handleId);
            reserveDraft(
                    handleId,
                    executable,
                    reservations,
                    stopDetail(data),
                    indexDraft);
        }
        for (String handleId : starting.stream().sorted().toList()) {
            RuntimeView.ComponentData data = draft.components.get(handleId);
            reserveDraft(
                    handleId,
                    executable,
                    reservations,
                    startDetail(data),
                    indexDraft);
        }
    }

    private String stopDetail(RuntimeView.ComponentData data) {
        if (data == null) {
            return "component stop";
        }
        return data.goal() == ComponentGoal.DISPOSED
                ? "component dispose"
                : "component stop";
    }

    private String startDetail(RuntimeView.ComponentData data) {
        return data != null && data.state() == ComponentState.FAILED
                ? "component restart"
                : "component activation start";
    }

    private void reserveDraft(
            String handleId,
            ExecutableCommitPlan executable,
            List<ComponentRuntime.Reservation> reservations,
            String detail,
            KernelStateDraft indexDraft) {
        ComponentRuntime runtime = executable.componentRuntimes
                .computeIfAbsent(handleId, indexDraft.components()::get);
        if (runtime != null) {
            runTransitionReservationFaultProbe(reservations.size());
            reservations.add(runtime.reserveTransition(pendingTime(), detail));
        }
    }

    private void runTransitionReservationFaultProbe(int reservationIndex) {
        IntConsumer probe = transitionReservationFaultProbe;
        if (probe != null) {
            probe.accept(reservationIndex);
        }
    }

    private String executeReservedTransitions(
            List<ComponentRuntime.Reservation> reservations) {
        String failure = null;
        for (ComponentRuntime.Reservation reservation : reservations) {
            if (!reservation.created()) {
                continue;
            }
            try {
                reservation.component().executeReserved(
                        this,
                        executor,
                        reservation.future());
            } catch (Throwable driveError) {
                String message = postCommitFailure(
                        "postcommit transition drive",
                        "reservation execute",
                        driveError);
                failure = appendPostCommitFailure(failure, message);
                try {
                    reservation.component().failTransition(
                            reservation.future(),
                            new IllegalStateException(message));
                } catch (Throwable completeError) {
                    failure = appendPostCommitFailure(failure, postCommitFailure(
                            "postcommit transition drive",
                            "reservation failure completion",
                            completeError));
                }
            }
        }
        return failure;
    }

    private List<CompletableFuture<ComponentState>> cancelCreatedReservationFutures(
            List<ComponentRuntime.Reservation> reservations) {
        List<CompletableFuture<ComponentState>> cancelled = new ArrayList<>();
        for (ComponentRuntime.Reservation reservation : reservations) {
            if (reservation.created()
                    && reservation.component().cancelTransition(reservation.future())) {
                cancelled.add(reservation.future());
            }
        }
        return cancelled;
    }

    private void completeCancelledTransitions(
            List<CompletableFuture<ComponentState>> cancelled) {
        for (CompletableFuture<ComponentState> future : cancelled) {
            future.completeExceptionally(new TransitionCancelledStateException());
        }
    }

    private List<CompletableFuture<ComponentState>> concatFutures(
            List<CompletableFuture<ComponentState>> first,
            List<CompletableFuture<ComponentState>> second) {
        List<CompletableFuture<ComponentState>> result =
                new ArrayList<>(first);
        result.addAll(second);
        return result;
    }

    private void runTransitionReservationProbe() {
        Runnable probe = transitionReservationProbe;
        if (probe != null) {
            probe.run();
        }
    }

    private void runTransitionPublicationProbe() {
        Runnable probe = transitionPublicationProbe;
        if (probe != null) {
            probe.run();
        }
    }

    private Runnable prepareTransitionCompletion(
            ComponentRuntime component,
            CompletableFuture<ComponentState> future,
            ComponentState state) {
        component.clearTransition(future);
        return () -> future.complete(state);
    }

    private void dispatchCompletion(Runnable completion) {
        if (completion == null) {
            return;
        }
        try {
            executor.execute(completion);
        } catch (RejectedExecutionException error) {
            // Runtime close and lifecycle completion can race. The caller must invoke this
            // only after leaving coordinator so dependent callbacks may safely re-enter.
            completion.run();
        }
    }

    // Kahn 拓扑排序先排无提供方，再整体反转，得到依赖方先于提供方的停止顺序。
    private List<String> orderForStop(
            RuntimeViewReader current,
            Set<String> handles,
            Map<String, ActivationRuntime> activationRuntimes) {
        if (handles.isEmpty()) {
            return List.of();
        }
        Map<String, Set<String>> providers =
                stopProviders(current, handles, activationRuntimes);
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

    // 将注册归属还原为提供方 MountHandle，只保留本次也在停止集合内的内部依赖。
    private Map<String, Set<String>> stopProviders(
            RuntimeViewReader current,
            Set<String> handles,
            Map<String, ActivationRuntime> activationRuntimes) {
        Map<String, String> activationOwners = new HashMap<>();
        current.activations().values().forEach(activation ->
                activationOwners.put(activation.activationId(), activation.handleId()));
        Map<String, Set<String>> result = new TreeMap<>();
        for (String handleId : handles) {
            RuntimeView.ComponentData component = current.components().get(handleId);
            if (component == null || component.currentActivationId() == null) {
                continue;
            }
            ActivationRuntime activation = activationRuntimes.get(
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
                        activationRuntimes,
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
            RuntimeViewReader current,
            Map<String, String> activationOwners,
            Map<String, ActivationRuntime> activationRuntimes,
            String registrationId) {
        RuntimeView.RegistrationData registration =
                current.registrations().get(registrationId);
        String ownerActivationId = null;
        if (registration != null
                && registration.owner() instanceof RuntimeView.OwnerData.Activation owner) {
            ownerActivationId = owner.activationId();
        } else {
            // 正在启动的提供方可能仍未发布注册；用暂存表识别它，避免新依赖方与提供方重叠清理。
            for (ActivationRuntime activation : activationRuntimes.values()) {
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
    void driveTransition(ComponentRuntime reserved, CompletableFuture<ComponentState> future) {
        String handleId = reserved.handleId();
        PublishedKernelState entryState = published;
        ComponentRuntime component = entryState.index.components.get(handleId);
        if (component == null) {
            reserved.clearTransition(future);
            future.complete(ComponentState.DISPOSED);
            return;
        }
        ActivationRuntime activation = null;
        ComponentState immediateState = null;
        Runnable completion = null;
        synchronized (coordinator) {
            PublishedKernelState state = published;
            RuntimeView.ComponentData data = state.view.components.get(handleId);
            if (data == null) {
                completion = prepareTransitionCompletion(
                        component, future, ComponentState.DISPOSED);
            } else {
                // current 与 failedCleanup 必须来自同一代快照，避免组合撕裂。
                ComponentRuntime.ActivationSlots slots = component.slots();
                if (data.state() == ComponentState.STOPPING) {
                    activation = slots.current();
                    if (activation == null) {
                        completion = finalizeOrphanedStoppingLocked(component, data, future);
                    } else if (slots.failedCleanup() != null
                            && !component.consumeCleanupRetryIntentLocked()) {
                        completion = retainFailedCleanupLocked(component, data, future);
                    }
                } else if (data.state() == ComponentState.FAILED
                        && slots.failedCleanup() != null) {
                    activation = slots.failedCleanup();
                    if (!component.consumeCleanupRetryIntentLocked()) {
                        completion = retainFailedCleanupLocked(component, data, future);
                        activation = null;
                    }
                } else {
                    boolean canStartActivation =
                            (data.state() == ComponentState.WAITING
                                    || data.state() == ComponentState.FAILED)
                                    && data.goal() == ComponentGoal.RUNNING
                                    && !component.suppressAutoRestart()
                                    && requirementsResolvable(state.view, data);
                    boolean requiresActivationRetry =
                            data.state() == ComponentState.FAILED
                                    && component.pendingStartFailure();
                    if (canStartActivation
                            && (!requiresActivationRetry
                                    || component.consumeActivationRetryIntentLocked())) {
                        activation = beginActivationLocked(component);
                    } else {
                        immediateState = data.state();
                        completion = prepareTransitionCompletion(
                                component, future, immediateState);
                    }
                }
            }
        }

        dispatchCompletion(completion);
        if (completion != null) {
            return;
        }

        if (activation == null) {
            ComponentState state = immediateState == null
                    ? componentState(handleId)
                    : immediateState;
            component.finishTransition(future, state);
            return;
        }
        // 锁外复核：若结构事务已使候选 stale 或作用域开始清理，直接走回滚路径。
        PublishedKernelState staleState = published;
        RuntimeView.ComponentData staleComponent =
                staleState.view.components.get(handleId);
        RuntimeView.ActivationData staleActivation =
                staleState.view.activations.get(activation.activationId);
        if (activation.scope.state() != LifecycleState.OPEN
                || staleComponent == null
                || staleComponent.currentActivationId() == null
                || staleActivation == null
                || staleActivation.state() == ActivationState.STOPPING) {
            finishCleanupAfterDependents(component, activation, future);
            return;
        }
        runActivation(component, activation, future);
    }

    private Runnable retainFailedCleanupLocked(
            ComponentRuntime component,
            RuntimeView.ComponentData data,
            CompletableFuture<ComponentState> future) {
        PublishedKernelState state = published;
        RuntimeView.Draft draft = new RuntimeView.Draft(state.view);
        KernelStateDraft indexDraft = new KernelStateDraft(state);
        draft.components.put(
                component.handleId(),
                data.withState(ComponentState.FAILED));
        diagnostics.refresh(draft);
        published = indexDraft.publish(draft.publishOnce());
        return prepareTransitionCompletion(component, future, ComponentState.FAILED);
    }

    private Runnable finalizeOrphanedStoppingLocked(
            ComponentRuntime component,
            RuntimeView.ComponentData data,
            CompletableFuture<ComponentState> future) {
        PublishedKernelState state = published;
        RuntimeView.Draft draft = new RuntimeView.Draft(state.view);
        KernelStateDraft indexDraft = new KernelStateDraft(state);
        ComponentState stateResult;
        if (data.goal() == ComponentGoal.DISPOSED) {
            removeComponentInView(draft, component.handleId());
            indexDraft.components().remove(component.handleId());
            indexDraft.componentHandles().remove(component.handleId());
            stateResult = ComponentState.DISPOSED;
        } else {
            draft.components.put(
                    component.handleId(),
                    data.withState(ComponentState.WAITING).clearActivation());
            stateResult = ComponentState.WAITING;
        }
        diagnostics.refresh(draft);
        published = indexDraft.publish(draft.publishOnce());
        return prepareTransitionCompletion(component, future, stateResult);
    }

    private boolean requirementsResolvable(
            RuntimeView view,
            RuntimeView.ComponentData data) {
        if (data.descriptor().sortedRequirements().stream().noneMatch(requirement ->
                requirement.mode() == CapabilityRequirement.Mode.REQUIRED)) {
            return true;
        }
        return data.descriptor().sortedRequirements().stream()
                .filter(requirement ->
                        requirement.mode() == CapabilityRequirement.Mode.REQUIRED)
                .allMatch(requirement -> RuntimeGraph.resolveDirect(
                        view,
                        Map.of(),
                        data.contextId(),
                        requirement.key()).isPresent());
    }

    // 在协调器内为 WAITING 组件创建 STARTING 代际，并把 BindingSet 与值一起固定到候选中。
    private ActivationRuntime beginActivationLocked(ComponentRuntime component) {
        PublishedKernelState state = published;
        RuntimeView.Draft draft = new RuntimeView.Draft(state.view);
        KernelStateDraft indexDraft = new KernelStateDraft(state);
        RuntimeView.ComponentData data = draft.components.get(component.handleId());
        component.recordReconcileFingerprintLocked(reconcileFingerprint(draft, data));
        RuntimeGraph.ResolutionCache resolutions = RuntimeGraph.resolutionCache();
        Map<String, RuntimeView.BindingData> bindings =
                RuntimeGraph.effectiveBindingsDirect(
                        draft, Map.of(), resolutions, data);
        String activationId = Sequences.activation();
        DesiredComponentState desired = component.desiredState();
        ActivationRuntime activation = new ActivationRuntime(
                activationId,
                component,
                desired.config(),
                desired.revision(),
                bindings,
                List.of(),
                ticker);
        for (CapabilityRequirement requirement
                : data.descriptor().sortedRequirements()) {
            RuntimeView.BindingData binding = bindings.get(requirement.key().name());
            RuntimeView.RegistrationData registration = RuntimeGraph.resolveDirect(
                    draft,
                    Map.of(),
                    resolutions,
                    data.contextId(),
                    requirement.key()).orElse(null);
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
                component.handleId(),
                ActivationState.STARTING,
                desired.revision(),
                bindings,
                data.descriptor(),
                activation.scope.scopeId()));
        draft.components.put(
                component.handleId(),
                data.withState(ComponentState.STARTING).withActivation(activationId));
        diagnostics.refresh(draft);
        indexDraft.activations().put(activationId, activation);
        published = indexDraft.publish(draft.publishOnce());
        component.claimCurrentLocked(activation);
        component.clearStartFailureLocked();
        component.clearBlockedNonConvergentLocked();
        return activation;
    }

    // Activation 事务：锁外执行 start()，重新获取协调器后按 validate -> 候选构造 ->
    // 单一 final publish -> 提交后效果收敛。final publish 之后任何故障都不回滚已提交结构。
    private void runActivation(
            ComponentRuntime runtime,
            ActivationRuntime activation,
            CompletableFuture<ComponentState> future) {
        List<ChildMountPlan> plans = new ArrayList<>();
        ActivationContext context = new ActivationContextImpl(
                this,
                activation,
                plans);
        StartFailureEvidence startEvidence = StartFailureEvidence.none();
        // 用户代码不持有协调器锁；失败证据在锁外一次性提取为纯值，原始 Throwable 不得进入临界区。
        try {
            runtime.prepared().start(context, activation.config);
        } catch (Throwable error) {
            startEvidence = StartFailureEvidence.capture(
                    error, configuration.failureDetailPolicy());
        }
        activation.closed.set(true);

        // 上下文已关闭，暂存副作用不再变化；候选构造只消费冻结副本。
        List<ChildMountPlan> frozenPlans = List.copyOf(plans);
        Map<String, RuntimeView.RegistrationData> frozenRegistrations =
                Map.copyOf(activation.stagedRegistrations);

        ActivationCommitCandidate candidate = null;
        boolean emergencyRollback = false;
        boolean cleanupRequired = false;
        List<CompletableFuture<ComponentState>> cancelledTransitions = List.of();
        String postCommitFailure = null;
        String emergencyFailure = null;
        synchronized (coordinator) {
            // 用户 start() 的失败证据在此处进入 owner 状态；后续候选/紧急路径读到同代数据。
            if (startEvidence.failed()) {
                runtime.recordStartFailureDetailLocked(startEvidence.failure());
            }
            List<ComponentRuntime.Reservation> createdReservations = new ArrayList<>();
            PublishedKernelState nextState = null;
            try {
                PublishedKernelState state = published;
                CommitDecision decision = validateActivation(
                        runtime,
                        activation,
                        frozenPlans,
                        frozenRegistrations,
                        startEvidence,
                        state);
                candidate = prepareActivationCandidate(
                        state,
                        runtime,
                        activation,
                        decision,
                        frozenPlans,
                        frozenRegistrations,
                        createdReservations);
                runActivationPrepublishProbe();
                runActivationFinalPublishProbe();
                nextState = candidate.nextState();
            } catch (Throwable prepublishError) {
                // prepublish/final publish 计算失败：取消本候选已创建的预约，
                // 不发布任何成功 child/registration。
                cancelledTransitions =
                        cancelCreatedReservationFutures(createdReservations);
                String message = "activation commit failed: "
                        + LifecycleScopeImpl.safeError(prepublishError);
                try {
                    candidate = prepareAbortedActivationCandidate(
                            runtime,
                            activation,
                            frozenPlans,
                            CommitDecision.commitFailed(message),
                            createdReservations);
                    nextState = candidate.nextState();
                } catch (Throwable fatal) {
                    cancelledTransitions = concatFutures(
                            cancelledTransitions,
                            cancelCreatedReservationFutures(createdReservations));
                    emergencyRollback = true;
                    try {
                        candidate = prepareEmergencyActivationCandidate(
                                runtime, activation, message, true);
                        nextState = candidate.nextState();
                    } catch (Throwable lastResort) {
                        // 连紧急候选也无法构造/发布：保持最后已发布结构，
                        // 下方仅以异常完成原始 future，绝不留下 pending。
                        candidate = null;
                        nextState = null;
                        emergencyFailure = message + "; emergency publish failed: "
                                + LifecycleScopeImpl.safeError(lastResort);
                    }
                }
            }
            if (candidate != null && nextState != null) {
                // 单一 final publish：赋值即不可逆提交点，此后不得回滚已提交结构或换回旧状态。
                published = nextState;
                try {
                    candidate.applyEffects(activation);
                    runActivationPostPublishEffectProbe();
                    cleanupRequired = decisionCleanupRequired(runtime);
                    if (candidate.abortedCandidate()) {
                        runActivationRollbackCommitProbe();
                    }
                    runTransitionPublicationProbe();
                } catch (Throwable postpublishError) {
                    // 已提交结构保持不变；预约在协调器外照常驱动，原始 future 异常完成。
                    postCommitFailure = "activation postcommit failed after publish: "
                            + LifecycleScopeImpl.safeError(postpublishError);
                }
            }
        }
        if (candidate != null) {
            CommittedLeaseRetirement retirement = retireCommittedRegistrations(
                    candidate.postCommitEffects().leasesToRetire(),
                    "activation postcommit");
            postCommitFailure = appendPostCommitFailure(
                    postCommitFailure,
                    retirement.failure());
        }
        completeCancelledTransitions(cancelledTransitions);
        Runnable decisionProbe = activationDecisionProbe;
        if (decisionProbe != null) {
            decisionProbe.run();
        }

        if (candidate == null) {
            runtime.failTransition(
                    future,
                    new IllegalStateException(emergencyFailure));
            return;
        }

        // 后续提交后效果若失败，lease 已登记 retired registry，结构保持 final publish。
        postCommitFailure = appendPostCommitFailure(
                postCommitFailure,
                executeReservedTransitions(candidate.reservations()));
        if (emergencyRollback) {
            runtime.failTransition(
                    future,
                    new IllegalStateException(candidate.emergencyMessage()));
            return;
        }
        try {
            scheduleAfterCommit(candidate.postCommitEffects().dirty());
        } catch (Throwable scheduleError) {
            postCommitFailure = postCommitFailure == null
                    ? "activation postcommit failed at additional scheduling: "
                            + LifecycleScopeImpl.safeError(scheduleError)
                    : postCommitFailure + "; additional scheduling failed: "
                            + LifecycleScopeImpl.safeError(scheduleError);
        }
        if (postCommitFailure != null) {
            // 已提交结构保持不变；原始过渡 future 异常完成，不得永久 pending。
            runtime.failTransition(
                    future,
                    new IllegalStateException(postCommitFailure));
            if (cleanupRequired) {
                finishCleanupAfterDependents(runtime, activation, future);
            }
            return;
        }

        Runnable transitionCompletion = null;
        if (!cleanupRequired) {
            synchronized (coordinator) {
                // 结构事务可能在 Activation 裁决释放锁后把同一 future 改成 STOPPING。
                PublishedKernelState state = published;
                RuntimeView.ComponentData data =
                        state.view.components.get(runtime.handleId());
                ComponentState currentState =
                        data == null ? ComponentState.DISPOSED : data.state();
                cleanupRequired = currentState == ComponentState.STOPPING;
                if (!cleanupRequired) {
                    transitionCompletion = prepareTransitionCompletion(
                            runtime,
                            future,
                            currentState);
                }
            }
        }
        dispatchCompletion(transitionCompletion);
        if (cleanupRequired) {
            finishCleanupAfterDependents(
                    runtime,
                    activation,
                    future);
        }
    }

    // 常规中止候选也失败时的最后防线：FAILED 视图与 failedCleanup/retry 意图同代裁决。
    void emergencyRollbackActivation(
            ComponentRuntime runtime,
            ActivationRuntime activation) {
        ActivationCommitCandidate candidate = prepareEmergencyActivationCandidate(
                runtime, activation, "emergency activation rollback", false);
        published = candidate.nextState();
        candidate.applyEffects(activation);
    }

    // 基于最新代际裁决候选；stale/配置/绑定检查排在用户 start 失败之前，避免把召回误报为业务失败。
    private CommitDecision validateActivation(
            ComponentRuntime runtime,
            ActivationRuntime activation,
            List<ChildMountPlan> plans,
            Map<String, RuntimeView.RegistrationData> stagedRegistrations,
            StartFailureEvidence startFailure,
            PublishedKernelState state) {
        RuntimeView current = state.view;
        RuntimeView.ComponentData data = current.components.get(runtime.handleId());
        if (data == null || data.goal() != ComponentGoal.RUNNING) {
            return CommitDecision.stale("component goal changed");
        }
        RuntimeView.ContextData context = current.contexts.get(data.contextId());
        if (context == null || context.state() != ContextState.ACTIVE) {
            return CommitDecision.stale("context changed");
        }
        DesiredComponentState desired = runtime.desiredState();
        if (data.configRevision() != activation.configRevision
                || desired.revision() != activation.configRevision) {
            return CommitDecision.stale("configuration changed");
        }
        if (activation.stale.get()) {
            return CommitDecision.stale("activation became stale");
        }
        RuntimeGraph currentGraph = RuntimeGraph.of(current, stagedRegistrations);
        RuntimeGraph.ResolutionCache resolutions = RuntimeGraph.resolutionCache();
        Map<String, RuntimeView.BindingData> effectiveBindings =
                currentGraph.effectiveBindings(
                        current, stagedRegistrations, resolutions, data);
        for (CapabilityRequirement requirement
                : data.descriptor().sortedRequirements()) {
            if (requirement.binding()
                    == CapabilityRequirement.CapabilityBinding.DYNAMIC) {
                if (requirement.mode() == CapabilityRequirement.Mode.REQUIRED) {
                    boolean initialPresence = activation.initialDynamicRequiredPresence
                            .getOrDefault(requirement.key().name(), false);
                    boolean currentPresence = currentGraph.resolve(
                            current,
                            stagedRegistrations,
                            resolutions,
                            data.contextId(),
                            requirement.key())
                            .isPresent();
                    if (initialPresence != currentPresence) {
                        return CommitDecision.stale("dynamic binding presence changed: "
                                        + requirement.key().name());
                    }
                }
                continue;
            }
            RuntimeView.BindingData captured =
                    activation.bindings.get(requirement.key().name());
            RuntimeView.BindingData effective =
                    effectiveBindings.get(requirement.key().name());
            if (!BindingImpactAnalyzer.bindingIdentityEqual(captured, effective)) {
                return CommitDecision.stale("binding changed: " + requirement.key().name());
            }
        }
        for (RuntimeView.RegistrationData staged
                : stagedRegistrations.values()) {
            Class<?> existing = liveCapabilityType(
                    state,
                    staged.key().name());
            if (existing != null && existing != staged.key().type()) {
                return CommitDecision.startFailed(
                        "staged capability type conflict: " + staged.key().name());
            }
            boolean occupied = current.registrations.values().stream().anyMatch(
                    registration -> registration.contextId()
                                    .equals(staged.contextId())
                            && registration.key().name().equals(staged.key().name()));
            if (occupied) {
                return CommitDecision.startFailed("staged capability slot occupied: " + staged.key().name());
            }
        }
        String childConflict = childPlanConflict(
                current, state, data.contextId(), plans);
        if (childConflict != null) {
            return CommitDecision.startFailed(childConflict);
        }
        if (RuntimeGraph.hasCycle(
                currentGraph.dependencyGraph(current, stagedRegistrations))) {
            return CommitDecision.cycleRejected("binding cycle rejected: " + runtime.handleId());
        }
        if (startFailure.failed()) {
            return CommitDecision.startFailed(startFailure.summary());
        }
        return CommitDecision.success();
    }

    private String childPlanConflict(
            RuntimeView current,
            PublishedKernelState state,
            String contextId,
            List<ChildMountPlan> plans) {
        // 类型检查覆盖其他仍在 STARTING 的暂存 Activation，防止并发批次合 publish 后破坏名称类型固定。
        Map<String, Class<?>> tentativeTypes = liveCapabilityTypes(state);
        for (RuntimeView.RegistrationData staged
                : activationRegistrationsForValidation(current, state, plans).values()) {
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
            PublishedKernelState state,
            List<ChildMountPlan> plans) {
        Map<String, RuntimeView.RegistrationData> registrations = new HashMap<>();
        for (ActivationRuntime activation : state.index.activations.values()) {
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
    // 只写草稿；owner/activation 的 live 效果由 ActivationOwnerEffect 在 final publish 后统一 apply。
    private Set<String> publishActivationDecision(
            RuntimeView.Draft draft,
            ComponentRuntime runtime,
            ActivationRuntime activation,
            CommitDecision decision,
            List<ChildMountPlan> plans,
            Map<String, RuntimeView.RegistrationData> stagedRegistrations,
            ExecutableCommitPlan executable) {
        RuntimeView.ComponentData data = draft.components.get(runtime.handleId());
        RuntimeView.ActivationData activationData =
                draft.activations.get(activation.activationId);
        if (data == null || activationData == null) {
            return Set.of();
        }
        if (decision.successful()) {
            // 提交成功才把暂存注册复制到已发布视图；子挂载同批进入 WAITING。
            for (RuntimeView.RegistrationData staged : stagedRegistrations.values()) {
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
                        runtime.handleId(),
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
                    runtime.handleId(),
                    data.withState(ComponentState.ACTIVE));

            // 新注册可能遮蔽已有提供方；提交时同步找出 BindingSet 变化的外部消费方。
            Set<String> changed = new LinkedHashSet<>();
            RuntimeGraph effectiveGraph = RuntimeGraph.of(draft, stagedRegistrations);
            RuntimeGraph.ResolutionCache effectiveResolutions =
                    RuntimeGraph.resolutionCache();
            for (RuntimeView.ComponentData component : draft.components.values()) {
                if (component.currentActivationId() == null
                        || component.handleId().equals(runtime.handleId())) {
                    continue;
                }
                RuntimeView.ActivationData other = draft.activations.get(
                        component.currentActivationId());
                if (other == null
                        || !RuntimeView.activationTracksGraph(other.state())) {
                    continue;
                }
                Map<String, RuntimeView.BindingData> effective =
                        effectiveGraph.effectiveBindings(
                                draft,
                                stagedRegistrations,
                                effectiveResolutions,
                                component);
                for (CapabilityRequirement requirement
                        : component.descriptor().sortedRequirements()) {
                    RuntimeView.BindingData old =
                            other.bindings().get(requirement.key().name());
                    RuntimeView.BindingData next =
                            effective.get(requirement.key().name());
                    if (!BindingImpactAnalyzer.bindingIdentityEqual(old, next)) {
                        changed.add(component.handleId());
                        break;
                    }
                }
            }
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
            return dirty;
        }

        // 失败路径不发布暂存内容；Activation 脱离绑定后由 LifecycleScope 回滚已接受资源。
        draft.activations.put(
                activation.activationId,
                activationData.detached());
        draft.components.put(
                runtime.handleId(),
                data.withState(ComponentState.STOPPING));
        return new LinkedHashSet<>(Set.of(runtime.handleId()));
    }

    private void precreateChildRuntimes(
            RuntimeView.Draft draft,
            List<ChildMountPlan> plans,
            ExecutableCommitPlan executable,
            KernelStateDraft indexDraft) {
        for (ChildMountPlan plan : plans) {
            String handleId = plan.handle().handleId();
            if (!draft.components.containsKey(handleId)
                    || indexDraft.components().containsKey(handleId)) {
                continue;
            }
            executable.componentRuntimes.computeIfAbsent(handleId, ignored ->
                    new ComponentRuntime(
                            handleId,
                            draft.components.get(handleId).contextId(),
                            plan.mountId(),
                            plan.prepared(),
                            coordinator));
        }
    }

    // prepublish：校验通过后的候选构造只写草稿与可取消预约，不修改 live 可变对象。
    private ActivationCommitCandidate prepareActivationCandidate(
            PublishedKernelState state,
            ComponentRuntime runtime,
            ActivationRuntime activation,
            CommitDecision decision,
            List<ChildMountPlan> plans,
            Map<String, RuntimeView.RegistrationData> stagedRegistrations,
            List<ComponentRuntime.Reservation> createdReservations) {
        RuntimeView.Draft draft = new RuntimeView.Draft(state.view);
        KernelStateDraft indexDraft = new KernelStateDraft(state);
        ExecutableCommitPlan executable = new ExecutableCommitPlan();
        Set<String> dirty = publishActivationDecision(
                draft, runtime, activation, decision, plans, stagedRegistrations, executable);
        precreateChildRuntimes(draft, plans, executable, indexDraft);
        reserveDraftTransitions(
                draft, dirty, executable, indexDraft, createdReservations);
        runTransitionReservationProbe();
        diagnostics.refresh(draft);
        applyIndexEffects(draft, indexDraft, executable, plans);
        return new ActivationCommitCandidate(
                draft,
                indexDraft,
                createdReservations,
                ownerEffectFor(runtime, decision),
                new ActivationPostCommitEffects(
                        Set.copyOf(dirty),
                        Map.copyOf(executable.retiredRegistrations)),
                executable.staleActivations,
                false,
                "");
    }

    // prepublish 失败后的中止候选：从最新已发布代际构造一次 STOPPING 收敛发布。
    private ActivationCommitCandidate prepareAbortedActivationCandidate(
            ComponentRuntime runtime,
            ActivationRuntime activation,
            List<ChildMountPlan> plans,
            CommitDecision decision,
            List<ComponentRuntime.Reservation> createdReservations) {
        PublishedKernelState state = published;
        RuntimeView.Draft draft = new RuntimeView.Draft(state.view);
        KernelStateDraft indexDraft = new KernelStateDraft(state);
        ExecutableCommitPlan executable = new ExecutableCommitPlan();
        Set<String> dirty = publishActivationDecision(
                draft, runtime, activation, decision, plans, Map.of(), executable);
        // 只驱动中止候选新建的预约；首候选已取消的预约是 no-op，不得混入驱动列表。
        int reservationBaseline = createdReservations.size();
        reserveDraftTransitions(
                draft, dirty, executable, indexDraft, createdReservations);
        runTransitionReservationProbe();
        diagnostics.refresh(draft);
        applyIndexEffects(draft, indexDraft, executable, List.of());
        return new ActivationCommitCandidate(
                draft,
                indexDraft,
                createdReservations.subList(
                        reservationBaseline, createdReservations.size()),
                ownerEffectFor(runtime, decision),
                new ActivationPostCommitEffects(
                        Set.copyOf(dirty),
                        Map.copyOf(executable.retiredRegistrations)),
                executable.staleActivations,
                true,
                "");
    }

    // 紧急回滚候选：FAILED 视图、failedCleanup 归属与 CLEANUP retry 意图在同一协调器裁决。
    private ActivationCommitCandidate prepareEmergencyActivationCandidate(
            ComponentRuntime runtime,
            ActivationRuntime activation,
            String message,
            boolean fatalPath) {
        PublishedKernelState state = published;
        RuntimeView.Draft draft = new RuntimeView.Draft(state.view);
        KernelStateDraft indexDraft = new KernelStateDraft(state);
        RuntimeView.ComponentData data = draft.components.get(runtime.handleId());
        if (data != null) {
            draft.components.put(
                    runtime.handleId(),
                    data.withState(ComponentState.FAILED));
        }
        RuntimeView.ActivationData activationData =
                draft.activations.get(activation.activationId);
        if (activationData != null) {
            draft.activations.put(
                    activation.activationId,
                    activationData.withState(ActivationState.FAILED));
        }
        diagnostics.refresh(draft);
        // 两条紧急路径都保留既有 suppressAutoRestart 语义：紧急回滚只召回清理，不重置周期抑制位。
        ActivationOwnerEffect ownerEffect = new ActivationOwnerEffect(
                fatalPath,
                fatalPath || runtime.pendingStartFailure(),
                runtime.suppressAutoRestart(),
                true,
                fatalPath ? message : runtime.lastStartError(),
                runtime.lastStartFailure());
        return new ActivationCommitCandidate(
                draft,
                indexDraft,
                List.of(),
                ownerEffect,
                ActivationPostCommitEffects.empty(),
                Set.of(),
                true,
                message);
    }

    // owner/activation 效果在 prepublish 冻结为纯值，final publish 后统一 apply。
    private ActivationOwnerEffect ownerEffectFor(
            ComponentRuntime runtime,
            CommitDecision decision) {
        boolean successful = decision.successful();
        boolean staleCandidate = decision.staleCandidate();
        String lastStartError;
        FailureInfo lastStartFailure;
        if (successful || staleCandidate) {
            lastStartError = "";
            lastStartFailure = FailureInfo.EMPTY;
        } else {
            lastStartError = decision.message();
            lastStartFailure = FailureInfo.EMPTY.equals(runtime.lastStartFailure())
                    ? FailureCapture.capture(
                            new IllegalStateException(decision.message()),
                            FailurePhase.ACTIVATION,
                            configuration.failureDetailPolicy(),
                            null)
                    : runtime.lastStartFailure();
        }
        return new ActivationOwnerEffect(
                !successful || staleCandidate,
                !successful && !staleCandidate && !decision.suppressCycle(),
                decision.suppressCycle(),
                false,
                lastStartError,
                lastStartFailure);
    }

    // 索引侧效果在 prepublish 内随草稿一起构造，与视图同代发布；只操作草稿集合。
    private void applyIndexEffects(
            RuntimeView.Draft draft,
            KernelStateDraft indexDraft,
            ExecutableCommitPlan executable,
            List<ChildMountPlan> plans) {
        for (String handleId : executable.removedComponents.keySet()) {
            if (draft.components.containsKey(handleId)) {
                continue;
            }
            indexDraft.components().remove(handleId);
            indexDraft.componentHandles().remove(handleId);
        }
        for (String registrationId : executable.retiredRegistrations.keySet()) {
            indexDraft.registrationHandles().remove(registrationId);
        }
        for (ChildMountPlan plan : plans) {
            String handleId = plan.handle().handleId();
            if (!draft.components.containsKey(handleId)) {
                continue;
            }
            ComponentRuntime child = executable.componentRuntimes
                    .computeIfAbsent(handleId, indexDraft.components()::get);
            if (child == null) {
                child = new ComponentRuntime(
                        handleId,
                        draft.components.get(handleId).contextId(),
                        plan.mountId(),
                        plan.prepared(),
                        coordinator);
                executable.componentRuntimes.put(handleId, child);
            }
            indexDraft.components().put(handleId, child);
            indexDraft.componentHandles().put(handleId, plan.handle());
        }
        syncProviderLeases(indexDraft, draft.registrations);
    }

    private void syncProviderLeases(
            KernelStateDraft indexDraft,
            Map<String, RuntimeView.RegistrationData> registrations) {
        Map<String, ProviderLeaseRuntime> leases = indexDraft.providerLeases();
        leases.keySet().retainAll(registrations.keySet());
        registrations.forEach((registrationId, registration) ->
                leases.put(registrationId, registration.leases()));
    }


    private void runActivationPrepublishProbe() {
        Runnable probe = activationPrepublishProbe;
        if (probe != null) {
            probe.run();
        }
    }

    private void runActivationFinalPublishProbe() {
        Runnable probe = activationFinalPublishProbe;
        if (probe != null) {
            probe.run();
        }
    }

    private void runActivationPostPublishEffectProbe() {
        Runnable probe = activationPostPublishEffectProbe;
        if (probe != null) {
            probe.run();
        }
    }
    private void runActivationRollbackCommitProbe() {
        Runnable probe = activationRollbackCommitProbe;
        if (probe != null) {
            probe.run();
        }
    }

    private boolean decisionCleanupRequired(ComponentRuntime runtime) {
        RuntimeView.ComponentData data =
                published.view.components.get(runtime.handleId());
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
            dependents = dependentsForProvider(runtime.handleId());
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
        List<CompletableFuture<Void>> drains = activation.stagedRegistrations.entrySet().stream()
                .map(entry -> retireProviderLease(entry.getKey(), entry.getValue().leases()))
                .toList();
        return drains.isEmpty()
                ? CompletableFuture.completedFuture(null)
                : CompletableFuture.allOf(drains.toArray(new CompletableFuture[0]));
    }

    // 从提供方反向扩散到所有传递依赖，并纳入其拥有的子挂载，但只调度仍处 STOPPING 的目标。
    private List<ComponentRuntime> dependentsForProvider(String providerHandleId) {
        PublishedKernelState state = published;
        RuntimeView current = state.view;
        Map<String, Set<String>> dependencies = stopProviders(
                current,
                new LinkedHashSet<>(current.components.keySet()),
                state.index.activations);
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
                .map(state.index.components::get)
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
            Runnable transitionCompletion = null;
            // 失败详情在协调器外捕获；协调器内只做同代字段应用，不在锁下格式化堆栈。
            boolean cleanupFailed = cleanupError != null
                    || activation.scope.state() == LifecycleState.FAILED;
            String cleanupDetail = cleanupFailed
                    ? (cleanupError == null
                            ? activation.scope.lastCleanupError()
                            : LifecycleScopeImpl.safeError(cleanupError))
                    : "";
            String cleanupErrorText = cleanupFailed
                    ? (cleanupDetail.isBlank() ? "cleanup failed" : "cleanup failed: " + cleanupDetail)
                    : "";
            FailureInfo cleanupFailure = FailureInfo.EMPTY;
            if (cleanupFailed) {
                cleanupFailure = cleanupError != null
                        ? FailureCapture.capture(
                                cleanupError,
                                FailurePhase.CLEANUP,
                                configuration.failureDetailPolicy(),
                                null)
                        : new FailureInfo(
                                FailurePhase.CLEANUP,
                                activation.scope.lastCleanupExceptionType().isBlank()
                                        ? IllegalStateException.class.getName()
                                        : activation.scope.lastCleanupExceptionType(),
                                activation.scope.lastCleanupError(),
                                List.of(),
                                List.of(),
                                java.time.Instant.now());
            }
            synchronized (coordinator) {
                PublishedKernelState kernelState = published;
                RuntimeView.Draft draft = new RuntimeView.Draft(kernelState.view);
                KernelStateDraft indexDraft = new KernelStateDraft(kernelState);
                RuntimeView.ComponentData data =
                        draft.components.get(runtime.handleId());
                if (data == null) {
                    draft.activations.remove(activation.activationId);
                    indexDraft.activations().remove(activation.activationId);
                    state = ComponentState.DISPOSED;
                    restart = false;
                } else {
                    ComponentGoal latestGoal = data.goal();
                    RuntimeView.ActivationData activationData =
                            draft.activations.get(activation.activationId);
                    if (cleanupFailed) {
                        // 保留 failedCleanup 和 FAILED Activation，阻止新代际启动，直到 retry 收敛。
                        runtime.recordCleanupFailureLocked(cleanupErrorText, cleanupFailure);
                        runtime.markFailedCleanupLocked(activation);
                        if (activationData != null) {
                            draft.activations.put(
                                    activation.activationId,
                                    activationData.withState(
                                            ActivationState.FAILED));
                        }
                        draft.components.put(
                                runtime.handleId(),
                                data.withState(ComponentState.FAILED));
                        state = ComponentState.FAILED;
                        restart = false;
                    } else {
                        // 清理成功后才能移除 Activation 索引，避免 Snapshot 或停止图丢失待清理资源。
                        runtime.clearCleanupFailureLocked();
                        runtime.clearFailedCleanupLocked();
                        draft.activations.remove(activation.activationId);
                        indexDraft.activations().remove(activation.activationId);
                        if (latestGoal == ComponentGoal.DISPOSED) {
                            removeComponentInView(draft, runtime.handleId());
                            indexDraft.components().remove(runtime.handleId());
                            indexDraft.componentHandles().remove(runtime.handleId());
                            state = ComponentState.DISPOSED;
                            restart = false;
                        } else if (runtime.pendingStartFailure()) {
                            draft.components.put(
                                    runtime.handleId(),
                                    data.withState(ComponentState.FAILED)
                                            .clearActivation());
                            runtime.clearCurrentLocked();
                            state = ComponentState.FAILED;
                            restart = false;
                        } else {
                            draft.components.put(
                                    runtime.handleId(),
                                    data.withState(ComponentState.WAITING)
                                            .clearActivation());
                            runtime.clearCurrentLocked();
                            state = ComponentState.WAITING;
                            restart = planReconcile(
                                    draft,
                                    draft.components.get(runtime.handleId()),
                                    runtime);
                        }
                    }
                }
                diagnostics.refresh(draft);
                published = indexDraft.publish(draft.publishOnce());
                // 仍在协调器内替换过渡链，随后到锁外提交新 Activation，保证旧请求先有结果。
                if (restart) {
                    restartReservation = runtime.replaceTransition(
                            pendingTime(), "component restart");
                } else {
                    transitionCompletion = prepareTransitionCompletion(runtime, future, state);
                }
            }

            dispatchCompletion(transitionCompletion);
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
        return runtime.planReconcileLocked(
                reconcileFingerprint(draft, data),
                configuration.maxReconcileIterations());
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
        RuntimeView current = published.view;
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
        CompletableFuture<Void> future = null;
        String postCommitFailure = null;
        List<CompletableFuture<Void>> registrationDrains = List.of();
        Map<String, ProviderLeaseRuntime> retiredRegistrations = Map.of();
        List<ComponentRuntime.Reservation> reservations = new ArrayList<>();
        boolean published = false;
        try {
            synchronized (coordinator) {
                PublishedKernelState state = this.published;
                RuntimeView.ContextData data =
                        state.view.contexts.get(handle.contextId());
                if (data == null || data.state() == ContextState.DISPOSED) {
                    return CompletableFuture.completedFuture(null);
                }
                synchronized (contextFutures) {
                    CompletableFuture<Void> existing =
                            contextFutures.get(handle.contextId());
                    if (existing != null && !existing.isCompletedExceptionally()) {
                        return existing;
                    }
                    if (existing != null) {
                        removeContextFuture(handle.contextId(), existing);
                    }
                    future = new CompletableFuture<>();
                    ContextDisposalPending pending = new ContextDisposalPending(
                            handle.contextId(), pendingTime());
                    contextFutures.put(handle.contextId(), future);
                    contextDisposalPending.put(handle.contextId(), pending);
                }

                RuntimeView.Draft draft = new RuntimeView.Draft(state.view);
                KernelStateDraft indexDraft = new KernelStateDraft(state);
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
                requestContextCleanupIntents(handles);
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
                        executable.removedComponents.put(
                                handleId,
                                new ExecutableCommitPlan.RemovedMount(component.mountId()));
                    } else if (component != null) {
                        dirty.add(handleId);
                    }
                }
                reserveDraftTransitions(
                        draft, dirty, executable, indexDraft, reservations);
                runTransitionReservationProbe();
                diagnostics.refresh(draft);
                RuntimeView next = draft.publishOnce();
                commitExecutable(next, List.of(), executable, indexDraft);
                this.published = indexDraft.publish(next);
                published = true;
                retiredRegistrations = Map.copyOf(executable.retiredRegistrations);
                runTransitionPublicationProbe();
            }
        } finally {
            if (published) {
                CommittedLeaseRetirement retirement =
                        retireCommittedRegistrations(
                                retiredRegistrations,
                                "context disposal postcommit");
                registrationDrains = retirement.drains();
                postCommitFailure = appendPostCommitFailure(
                        postCommitFailure,
                        retirement.failure());
                postCommitFailure = appendPostCommitFailure(
                        postCommitFailure,
                        executeReservedTransitions(reservations));
            } else {
                completeCancelledTransitions(
                        cancelCreatedReservationFutures(reservations));
                if (future != null) {
                    removeContextFuture(handle.contextId(), future);
                    future.completeExceptionally(new IllegalStateException(
                            "context disposal commit failed"));
                }
            }
        }

        CompletableFuture<Void> contextFuture = future;
        List<CompletableFuture<?>> settlements =
                new ArrayList<>(registrationDrains);
        if (postCommitFailure != null) {
            settlements.add(failedFuture(postCommitFailure));
        }
        settlements.addAll(reservations.stream()
                .map(ComponentRuntime.Reservation::future)
                .toList());
        CompletableFuture<Void> settlement = CompletableFuture.allOf(
                settlements.toArray(new CompletableFuture[0]));
        String forcedFailure = postCommitFailure;
        settlement.whenComplete((ignored, error) ->
                finalizeContext(subtree, contextFuture, forcedFailure));
        return future;
    }

    // 清理失败时保留 FAILED Context 与诊断供重试；成功时才释放名称和句柄索引。
    private void finalizeContext(
            Set<String> subtree,
            CompletableFuture<Void> future,
            String postCommitFailure) {
        Runnable contextCompletion;
        synchronized (coordinator) {
            PublishedKernelState state = published;
            RuntimeView.Draft draft = new RuntimeView.Draft(state.view);
            KernelStateDraft indexDraft = new KernelStateDraft(state);
            boolean failed = postCommitFailure != null
                    || draft.components.values().stream()
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
                        indexDraft.contextHandles().remove(contextId);
                    }
                }
            }
            diagnostics.refresh(draft);
            RuntimeView next = draft.publishOnce();
            published = indexDraft.publish(next);
            for (String contextId : subtree) {
                CompletableFuture<Void> currentFuture =
                        contextFutures.get(contextId);
                if (currentFuture != null) {
                    removeContextFuture(contextId, currentFuture);
                }
            }
            boolean finalFailed = failed;
            contextCompletion = () -> {
                if (finalFailed) {
                    future.completeExceptionally(new IllegalStateException(
                            postCommitFailure == null
                                    ? "context cleanup failed"
                                    : postCommitFailure));
                } else {
                    future.complete(null);
                }
            };
        }
        dispatchCompletion(contextCompletion);
    }

    private void removeContextFuture(
            String contextId,
            CompletableFuture<Void> future) {
        ContextDisposalPending pending = contextDisposalPending.get(contextId);
        if (contextFutures.remove(contextId, future)) {
            contextDisposalPending.remove(contextId, pending);
        }
    }
    // 事务内处置在组件收敛完成后才结算；这里为外层 Context 创建去重的最终化 Future。
    private CompletableFuture<Void> settleContextDisposal(
            String contextId,
            CompletableFuture<Void> prerequisite) {
        CompletableFuture<Void> future;
        Set<String> subtree;
        Set<String> components;
        synchronized (coordinator) {
            RuntimeView current = published.view;
            if (!current.contexts.containsKey(contextId)) {
                return CompletableFuture.completedFuture(null);
            }
            synchronized (contextFutures) {
                CompletableFuture<Void> existing =
                        contextFutures.get(contextId);
                if (existing != null && !existing.isCompletedExceptionally()) {
                    return existing;
                }
                if (existing != null) {
                    removeContextFuture(contextId, existing);
                }
                future = new CompletableFuture<>();
                ContextDisposalPending pending = new ContextDisposalPending(
                        contextId, pendingTime());
                contextFutures.put(contextId, future);
                contextDisposalPending.put(contextId, pending);
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
                finalizeContext(subtree, future, null));
        return future;
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

    record RequestPending(String targetId, long startNanos) {
    }

    record ContextDisposalPending(String contextId, long startNanos) {
    }
}
