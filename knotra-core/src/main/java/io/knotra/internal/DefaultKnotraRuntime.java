package io.knotra.internal;

import io.knotra.AdvancedRuntime;
import io.knotra.PendingOperationsSnapshot;
import io.knotra.PublicationOperation;
import io.knotra.PublicationState;
import io.knotra.CapabilityKey;
import io.knotra.CapabilityRequirement;
import io.knotra.ComponentGoal;
import io.knotra.ComponentState;
import io.knotra.ContextHandle;
import io.knotra.ContextInfo;
import io.knotra.ContextState;
import io.knotra.DiagnosticCode;
import io.knotra.KnotraConfig;
import io.knotra.KnotraRuntime;
import io.knotra.MountHandle;
import io.knotra.MountNotActiveException;
import io.knotra.RegistrationHandle;
import io.knotra.RuntimeDiagnostic;
import io.knotra.RuntimeSnapshot;
import io.knotra.RuntimeTransaction;
import io.knotra.Settlement;
import io.knotra.SettlementReport;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;

/**
 * Runtime 内核默认实现，拥有宿主事务、公开句柄与 Context 处置；Activation/cleanup 状态机
 * 由 {@link ActivationCoordinator} 独立拥有。
 *
 * <p>已提交结构由 {@link KernelStateStore} 以单一 volatile 状态发布；所有草稿校验、代际提交和可执行索引同步
 * 都在 {@code coordinator} 临界区内完成。Factory、normalizer 和用户 {@code start()} 不持有协调器锁，因此
 * 慢用户代码只能阻塞自身 Activation，不能阻塞其他宿主事务或 Snapshot。</p>
 *
 * <p>Runtime 只暴露窄访问器供宿主事务复用 Coordinator 的 scheduler/lease 服务；Activation 最终提交
 * 构造视图/索引草稿并预留 dirty 过渡（除可取消预约外不修改 live 对象）；随后把
 * {@link ActivationCommitCandidate#nextState()} 连同构造基线提交给
 * {@link KernelStateStore}，作为唯一不可逆 final publish；
 * owner/activation 效果在发布后以纯赋值显式 apply，预约驱动、lease 排空与额外调度在协调器外执行。
 * final publish 之后的任何故障都不回滚已提交 child/registration/owner，原始过渡 future 以阶段文本
 * 异常完成；prepublish 失败则取消本候选预约，并从最新代际构造一次 STOPPING 中止候选供清理收敛。</p>
 *
 * <p>锁顺序约定：协调器锁优先；协调器临界区内允许对 dispose/Context 处置注册表做 CHM 短计算，
 * 注册表是叶子结构，绝不反向获取协调器或回调 runtime。完成组件过渡时协调器可嵌套
 * {@link ComponentRuntime} 的过渡链锁。LifecycleScope 释放器、
 * 用户回调和 Future 回调不得反向获取协调器锁。</p>
 */
final class DefaultKnotraRuntime implements KnotraRuntime {
    final KnotraConfig configuration;
    private final LongSupplier ticker;
    // 结构一致性主锁：保护视图草稿、代际发布、可执行索引同步和过渡状态裁决。
    final Object coordinator = new Object();
    final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    // Activation/cleanup 与 Context 处置状态机分别由独立 Coordinator 拥有。
    private final ActivationCoordinator activationCoordinator;
    private final ContextDisposalCoordinator contextCoordinator;
    private final KernelStateStore kernelState;

    // 单 Mount dispose 去重与 pending 元数据；Context 请求由 ContextCoordinator 拥有。
    private final DisposeRequestRegistry disposeRequestRegistry =
            new DisposeRequestRegistry();
    // close 先于新事务置位；失败的未来可被替换以便重试关闭，成功后复用同一结果。
    private final AtomicBoolean closing = new AtomicBoolean();
    private final AtomicReference<CompletableFuture<Void>> closeFuture =
            new AtomicReference<>();
    private volatile long closeStartNanos;
    private volatile boolean closeStartPresent;
    private final AdvancedRuntime advanced = new RuntimeAdvancedFacade(this);
    private final BindingImpactAnalyzer bindingImpacts = new BindingImpactAnalyzer();
    private final DynamicCapabilityBroker dynamicBroker = new DynamicCapabilityBroker(this);
    // 包内测试探针：在 whenSettled 读取状态后、进入 chainLock 前暂停；探针须先清空自身再阻塞。
    volatile Runnable whenSettledObservationProbe;

    DefaultKnotraRuntime(KnotraConfig configuration) {
        this(configuration, System::nanoTime);
    }

    // 包内测试可注入单调时钟；生产路径始终使用 System.nanoTime()。
    DefaultKnotraRuntime(KnotraConfig configuration, LongSupplier ticker) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.ticker = Objects.requireNonNull(ticker, "ticker");
        this.kernelState = KernelStateStore.initial(
                coordinator, new ContextHandleImpl(this, "ctx-root"));
        this.activationCoordinator = new ActivationCoordinator(
                coordinator,
                kernelState,
                configuration,
                executor,
                ticker,
                new RuntimeActivationHost(this));
        this.contextCoordinator = new ContextDisposalCoordinator(
                coordinator,
                kernelState,
                executor,
                ticker,
                configuration,
                activationCoordinator);
    }

    long pendingTime() {
        return ticker.getAsLong();
    }

    RuntimeView currentView() {
        return kernelState.read().view;
    }

    PublishedKernelState publishedState() {
        return kernelState.read();
    }

    DynamicCapabilityBroker dynamicBroker() {
        return dynamicBroker;
    }

    ActivationCoordinator activationCoordinator() {
        return activationCoordinator;
    }

    ContextDisposalCoordinator contextCoordinator() {
        return contextCoordinator;
    }

    @Override
    public String runtimeId() {
        return configuration.runtimeId();
    }

    @Override
    public ContextHandle root() {
        return kernelState.read().index.contextHandles.get("ctx-root");
    }

    @Override
    public AdvancedRuntime advanced() {
        return advanced;
    }

    public RuntimeSnapshot snapshot() {
        PublishedKernelState state = kernelState.read();
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

    // ---- 内部 Publication 服务：意图经 transact 提交，槽位验证全部在协调器内完成 ----

    <T> PublicationServiceResult createOrUpdatePublication(
            ContextHandleImpl context,
            CapabilityKey<T> key,
            T value) {
        Objects.requireNonNull(value, "value");
        AtomicReference<PublicationProvideOutcome> outcome =
                new AtomicReference<>();
        TransactionReceipt<Void> receipt = transact(transaction -> {
            ((TransactionRecorder) transaction).recordPublicationIntent(
                    new PublicationProvideIntent(
                            null,
                            context,
                            key,
                            value,
                            -1,
                            null,
                            new RegistrationHandleImpl(this, Sequences.registration()),
                            outcome));
            return null;
        });
        return new PublicationServiceResult(outcome.get(), receipt.settlement());
    }

    TransactionReceipt<Void> updatePublication(
            String slotId,
            long expectedEpoch,
            String expectedRegistrationId,
            ContextHandleImpl context,
            CapabilityKey<?> key,
            Object value) {
        return transact(transaction -> {
            ((TransactionRecorder) transaction).recordPublicationIntent(
                    new PublicationProvideIntent(
                            slotId,
                            context,
                            key,
                            value,
                            expectedEpoch,
                            expectedRegistrationId,
                            new RegistrationHandleImpl(this, Sequences.registration()),
                            new AtomicReference<>()));
            return null;
        });
    }

    TransactionReceipt<Void> unpublishPublication(
            String slotId,
            long expectedEpoch,
            String expectedRegistrationId,
            ContextHandleImpl context,
            CapabilityKey<?> key) {
        return transact(transaction -> {
            ((TransactionRecorder) transaction).recordPublicationIntent(
                    new PublicationUnpublishIntent(
                            slotId, context, key, expectedEpoch, expectedRegistrationId));
            return null;
        });
    }

    RuntimeView.PublicationSlotData publicationSlot(String slotId) {
        return kernelState.read().view.publicationSlots.get(slotId);
    }

    /**
     * 句柄侧槽位观察：只读一次 published，active-only 视图命中即为 PUBLISHED；
     * 未命中读共享 ref 的终态数据（完成先于发布，读到新代际必见终态）。
     */
    PublicationSlotObservation publicationSlotObservation(
            PublicationSlotTerminalRef ref) {
        PublishedKernelState state = kernelState.read();
        RuntimeView.PublicationSlotData slot =
                state.view.publicationSlots.get(ref.slotId);
        if (slot != null) {
            return new PublicationSlotObservation(
                    PublicationState.PUBLISHED,
                    slot.epoch(),
                    slot.currentRegistrationId(),
                    slot.lastChangedGeneration());
        }
        PublicationSlotTerminalRef.TerminalData terminal = ref.terminalData();
        if (terminal != null) {
            return new PublicationSlotObservation(
                    terminal.state(), -1, null, terminal.lastChangedGeneration());
        }
        // 正常提交顺序下不可达（终态先完成 ref 再发布视图）；防御性按 DISPLACED 观察。
        return new PublicationSlotObservation(
                PublicationState.DISPLACED, -1, null, 0);
    }

    record PublicationSlotObservation(
            PublicationState state,
            long epoch,
            String currentRegistrationId,
            long lastChangedGeneration) {
    }

    /** final commit 专用：published 赋值前完成全部终态 ref；纯赋值，不得抛出。 */
    private void completeTerminalPublicationRefs(ExecutableCommitPlan executable) {
        for (ExecutableCommitPlan.PublicationTerminalEffect effect
                : executable.terminalPublicationSlots.values()) {
            effect.ref().completeForCommit(effect.terminalData());
        }
    }

    record PublicationServiceResult(
            PublicationProvideOutcome outcome, Settlement settlement) {
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
        String postCommitFailure = null;
        List<CompletableFuture<Void>> registrationDrains = List.of();
        ExecutableCommitPlan executable = new ExecutableCommitPlan();
        StructuralPostCommitPort.PreparedTransitions transitions =
                new StructuralPostCommitPort.PreparedTransitions(
                        TransitionPlan.EMPTY, List.of());
        boolean committed = false;
        try {
            synchronized (coordinator) {
                PublishedKernelState state = kernelState.read();
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
                        viewChanged |= applyIntent(
                                state, draft, indexDraft, intent, dirty, executable);
                    }
                    bindingImpacts.markBindingImpacts(state, draft, dirty, executable);
                    if (!viewChanged) {
                        return new TransactionReceipt<>(
                                callbackValue,
                                DefaultSettlement.empty(state.view.generation));
                    }
                    transitions = activationCoordinator.prepare(
                            state, draft, dirty, executable, indexDraft);
                    DiagnosticSupport.refresh(draft, state.index, configuration);
                    RuntimeView next = draft.publishOnce();
                    commitExecutable(
                            state.index, next, intents, executable, indexDraft);
                    PublishedKernelState candidate = indexDraft.publish(next);
                    completeTerminalPublicationRefs(executable);
                    kernelState.commitLocked(state, candidate);
                    committed = true;
                    committedGeneration = next.generation;
                    postCommitDirty.addAll(dirty);
                    try {
                        activationCoordinator.applyCommittedEffectsLocked(
                                transitions, executable, candidate.index);
                    } catch (Throwable applyError) {
                        postCommitFailure = PostCommitFaults.append(
                                postCommitFailure,
                                PostCommitFaults.failure(
                                        "host transaction postcommit",
                                        "committed effects",
                                        applyError));
                    }
                } catch (Reject rejection) {
                    throw new TransactionRejectedException(List.of(rejection.diagnostic()));
                }
            }
        } finally {
            if (committed) {
                StructuralPostCommitPort.CommittedEffects effects =
                        activationCoordinator.finishCommittedEffects(
                                transitions, executable);
                registrationDrains = effects.registrationDrains();
                postCommitFailure = PostCommitFaults.append(
                        postCommitFailure, effects.failure());
            } else {
                activationCoordinator.cancel(transitions);
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
        settlements.addAll(contextCoordinator.settleTransactionalDisposals(
                executable, componentSettlement));
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
        ContextHandleImpl root = kernelState.read().index.contextHandles.get("ctx-root");
        // Runtime close 复用根 Context 处置路径；只有全树清理成功才关闭执行器。
        contextCoordinator.dispose(root, true).whenComplete((ignored, error) -> {
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
        PublishedKernelState state = kernelState.read();
        List<PendingOperationSample> samples = new ArrayList<>();
        Set<String> transitionTargets = new LinkedHashSet<>();
        for (ComponentRuntime component : state.index.components.values()) {
            PendingOperationSample sample = component.pendingSnapshot();
            if (sample != null) {
                samples.add(sample);
                transitionTargets.add(sample.targetId());
            }
        }
        for (PendingOperationSample sample : disposeRequestRegistry.pending()) {
            if (transitionTargets.contains(sample.targetId())) {
                continue;
            }
            samples.add(sample);
        }
        samples.addAll(contextCoordinator.pending());
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
        for (ProviderLeaseRuntime leases : activationCoordinator.pendingProviderLeases()) {
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


    <T> Optional<T> findInContext(String contextId, CapabilityKey<T> key) {
        Objects.requireNonNull(key, "key");
        // 固定本地状态引用，同一次 require/find 不会被并发发布拆到两个代际。
        RuntimeView current = kernelState.read().view;
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
        RuntimeView current = kernelState.read().view;
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
        RuntimeView.ContextData data = kernelState.read().view.contexts.get(contextId);
        return data == null ? ContextState.DISPOSED : data.state();
    }

    ComponentState componentState(String handleId) {
        RuntimeView.ComponentData data = kernelState.read().view.components.get(handleId);
        if (data != null) {
            return data.state();
        }
        return ComponentState.DISPOSED;
    }

    ComponentGoal componentGoal(String handleId) {
        RuntimeView.ComponentData data = kernelState.read().view.components.get(handleId);
        return data == null ? ComponentGoal.DISPOSED : data.goal();
    }

    long componentConfigRevision(String handleId) {
        RuntimeView.ComponentData data = kernelState.read().view.components.get(handleId);
        return data == null ? 0 : data.configRevision();
    }

    CompletionStage<ComponentState> whenSettled(String handleId) {
        PublishedKernelState state = kernelState.read();
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
            PublishedKernelState current = kernelState.read();
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
        PublishedKernelState state = kernelState.read();
        DiagnosticSupport.FailureSnapshot snapshot =
                DiagnosticSupport.failureSnapshot(state, handle.handleId());
        if (snapshot.state() == ComponentState.ACTIVE) {
            return handle;
        }
        ComponentState failureState = outcome.settledNormally()
                ? outcome.result()
                : snapshot.state();
        List<RuntimeDiagnostic> diagnostics = snapshot.state() == failureState
                ? new ArrayList<>(snapshot.diagnostics())
                : new ArrayList<>();
        RuntimeDiagnostic detail = DiagnosticSupport.failureDetail(
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
            PublishedKernelState state = kernelState.read();
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
        return activationCoordinator.scheduler().enqueue(component);
    }

    CompletionStage<ComponentState> dispose(MountHandleImpl handle) {
        PublishedKernelState state = kernelState.read();
        if (isAlreadyDisposed(handle, state)) {
            return CompletableFuture.completedFuture(ComponentState.DISPOSED);
        }
        // 注册表短操作合并并发请求；登记后立即离开注册表锁，事务与 future 完成都在锁外驱动。
        DisposeRequestRegistry.Registration registration =
                disposeRequestRegistry.getOrCreate(handle.handleId(), pendingTime());
        if (!registration.created()) {
            return registration.future();
        }
        CompletableFuture<ComponentState> request = registration.future();
        state = kernelState.read();
        if (isAlreadyDisposed(handle, state)) {
            disposeRequestRegistry.remove(handle.handleId(), request);
            request.complete(ComponentState.DISPOSED);
            return request;
        }
        try {
            transact(transaction -> {
                transaction.dispose(handle);
                return null;
            });
        } catch (TransactionRejectedException rejection) {
            disposeRequestRegistry.remove(handle.handleId(), request);
            request.completeExceptionally(rejection);
            return request;
        }
        settleDisposeRequest(handle.handleId(), request);
        return request;
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
                    disposeRequestRegistry.remove(handleId, request);
                    request.completeExceptionally(
                            new TransitionRejectedStateException(rejectionError));
                }
                return;
            }
            if (error != null || state != ComponentState.STOPPING) {
                disposeRequestRegistry.remove(handleId, request);
            }
            if (error != null) {
                request.completeExceptionally(error);
            } else {
                request.complete(state);
            }
        });
    }


    CompletionStage<Void> disposeContext(ContextHandleImpl handle) {
        if (kernelState.read().index.contextHandles.get(handle.contextId()) != handle) {
            CompletableFuture<Void> rejected = new CompletableFuture<>();
            rejected.completeExceptionally(new IllegalStateException(
                    "context handle does not belong to this runtime"));
            return rejected;
        }
        return contextCoordinator.dispose(handle, false);
    }

    Object registrationValue(String registrationId) {
        PublishedKernelState state = kernelState.read();
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
        Class<?> existing = liveCapabilityType(kernelState.read(), key.name());
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


    boolean mountIdReserved(String contextId, String mountId) {
        RuntimeView current = kernelState.read().view;
        return current.components.values().stream().anyMatch(component ->
                component.contextId().equals(contextId)
                        && component.mountId().equals(mountId));
    }

    private boolean applyIntent(
            PublishedKernelState baseState,
            RuntimeView.Draft draft,
            KernelStateDraft index,
            Intent intent,
            Set<String> dirty,
            ExecutableCommitPlan executable) {
        return switch (intent) {
            case ProvideIntent provide -> applyProvide(draft, provide);
            case RevokeIntent revoke -> applyRevoke(
                    baseState, draft, revoke, dirty, executable);
            case PublicationProvideIntent provide ->
                    applyPublicationProvide(
                            baseState, draft, provide, dirty, executable);
            case PublicationUnpublishIntent unpublish ->
                    applyPublicationUnpublish(
                            baseState, draft, unpublish, dirty, executable);
            case ChildContextIntent child -> applyChildContext(draft, child);
            case MountIntent mount -> applyMount(draft, mount, dirty, executable);
            case ReconfigureIntent<?> reconfigure ->
                    applyReconfigure(baseState, draft, reconfigure, dirty, executable);
            case DisposeIntent dispose ->
                    applyDispose(baseState, draft, dispose, dirty, executable);
            case ContextDisposeIntent dispose ->
                    contextCoordinator.prepareTransactionalDisposal(
                            baseState,
                            draft,
                            index,
                            dispose.handle().contextId(),
                            executable,
                            dirty);
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
                                ticker),
                        null));
        draft.capabilityTypes.putIfAbsent(key.name(), key.type());
        return true;
    }

    private boolean applyRevoke(
            PublishedKernelState baseState,
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
        StructureGraphMutator.MutationResult revoked =
                StructureGraphMutator.detachConsumersOfRegistration(
                        draft,
                        handle.registrationId(),
                        StructureGraphMutator.activePublicationSlotRefs(
                                baseState.index, executable),
                        PublicationState.DISPLACED);
        revoked.applyTo(executable);
        dirty.addAll(revoked.dirty());
        return true;
    }

    /**
     * Publication 发布/更新（create-or-attach）。
     *
     * <p>slotId 为 null 时：坐标上存在活跃同名槽位则线性化为该槽位的 UPDATE（同类型）；
     * 存在 raw/Activation 注册占用则拒绝（不接管）；否则创建新槽位。带 slotId 时为乐观 UPDATE，
     * 期望失效抛内部 Stale 异常由句柄重试。UPDATE 只换 current，不进入终态。</p>
     */
    private boolean applyPublicationProvide(
            PublishedKernelState baseState,
            RuntimeView.Draft draft,
            PublicationProvideIntent intent,
            Set<String> dirty,
            ExecutableCommitPlan executable) {
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

        RuntimeView.PublicationSlotData slot;
        if (intent.slotId() == null) {
            slot = draft.activePublicationSlots.get(
                    new RuntimeView.PublicationSlotKey(
                            context.contextId(), key.name()));
            if (slot == null) {
                boolean occupied = draft.registrations.values().stream()
                        .anyMatch(registration ->
                                registration.contextId().equals(context.contextId())
                                        && registration.key().name().equals(key.name()));
                if (occupied) {
                    throw reject(
                            DiagnosticCode.CAPABILITY_SLOT_OCCUPIED,
                            key.name(),
                            "context capability slot is already occupied");
                }
            }
        } else {
            slot = requireExpectedPublicationSlot(
                    draft, intent.slotId(), context.contextId(), key,
                    intent.expectedEpoch(), intent.expectedRegistrationId(), "update");
        }

        String newRegistrationId = intent.handle().registrationId();
        long nextEpoch;
        boolean slotWasCreated = slot == null;
        PublicationSlotTerminalRef terminalRef;
        if (slotWasCreated) {
            String slotId = Sequences.publicationSlot();
            terminalRef = new PublicationSlotTerminalRef(slotId);
            slot = new RuntimeView.PublicationSlotData(
                    slotId,
                    context.contextId(),
                    key.name(),
                    key.typeName(),
                    null,
                    null,
                    -1,
                    draft.generation);
            nextEpoch = 0;
        } else {
            Map<String, PublicationSlotTerminalRef> slotRefs =
                    StructureGraphMutator.activePublicationSlotRefs(
                            baseState.index, executable);
            terminalRef = Objects.requireNonNull(
                    slotRefs.get(slot.slotId()),
                    "active publication slot missing terminal ref: " + slot.slotId());
            StructureGraphMutator.MutationResult updated =
                    StructureGraphMutator.detachConsumersOfRegistration(
                            draft,
                            slot.currentRegistrationId(),
                            slotRefs,
                            null);
            updated.applyTo(executable);
            dirty.addAll(updated.dirty());
            nextEpoch = slot.epoch() + 1;
        }

        RuntimeView.PublicationSlotData next = slot.withCurrent(
                newRegistrationId, nextEpoch, draft.generation + 1);
        draft.registrations.put(
                newRegistrationId,
                new RuntimeView.RegistrationData(
                        newRegistrationId,
                        key,
                        context.contextId(),
                        RuntimeView.OwnerData.Host.INSTANCE,
                        intent.value(),
                        new ProviderLeaseRuntime(newRegistrationId, ticker),
                        next.slotId()));
        draft.capabilityTypes.putIfAbsent(key.name(), key.type());
        draft.publicationSlots.put(next.slotId(), next);
        draft.activePublicationSlots.put(
                new RuntimeView.PublicationSlotKey(context.contextId(), key.name()), next);
        executable.createdPublicationSlots.putIfAbsent(next.slotId(), terminalRef);
        intent.outcome().set(new PublicationProvideOutcome(
                next.slotId(),
                slotWasCreated ? PublicationOperation.PUBLISH : PublicationOperation.UPDATE,
                terminalRef));
        return true;
    }

    private boolean applyPublicationUnpublish(
            PublishedKernelState baseState,
            RuntimeView.Draft draft,
            PublicationUnpublishIntent intent,
            Set<String> dirty,
            ExecutableCommitPlan executable) {
        ContextHandleImpl context = requireContext(draft, intent.context());
        RuntimeView.PublicationSlotData slot = requireExpectedPublicationSlot(
                draft, intent.slotId(), context.contextId(), intent.key(),
                intent.expectedEpoch(), intent.expectedRegistrationId(), "unpublish");
        String registrationId = slot.currentRegistrationId();
        StructureGraphMutator.MutationResult unpublished =
                StructureGraphMutator.detachConsumersOfRegistration(
                        draft,
                        registrationId,
                        StructureGraphMutator.activePublicationSlotRefs(
                                baseState.index, executable),
                        PublicationState.UNPUBLISHED);
        unpublished.applyTo(executable);
        dirty.addAll(unpublished.dirty());
        return true;
    }

    private RuntimeView.PublicationSlotData requireExpectedPublicationSlot(
            RuntimeView.Draft draft,
            String slotId,
            String contextId,
            CapabilityKey<?> key,
            long expectedEpoch,
            String expectedRegistrationId,
            String operation) {
        // 视图为 active-only：存在即 PUBLISHED；不存在即已终态（调用方以 Stale 重试）。
        RuntimeView.PublicationSlotData slot = draft.publicationSlots.get(slotId);
        if (slot == null
                || !slot.contextId().equals(contextId)
                || !slot.capabilityName().equals(key.name())
                || !slot.typeName().equals(key.typeName())
                || slot.epoch() != expectedEpoch
                || !Objects.equals(slot.currentRegistrationId(), expectedRegistrationId)) {
            throw new StalePublicationSlotException(
                    "publication slot " + slotId + " changed before " + operation);
        }
        return slot;
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
            PublishedKernelState baseState,
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
            StructureGraphMutator.MutationResult detached =
                    StructureGraphMutator.disposeOwnersAndDetach(
                            draft,
                            Set.of(handle.handleId()),
                            StructureGraphMutator.activePublicationSlotRefs(
                                    baseState.index, executable));
            detached.applyTo(executable);
            dirty.addAll(detached.dirty());
        }
        dirty.add(handle.handleId());
        return true;
    }

    private boolean applyDispose(
            PublishedKernelState baseState,
            RuntimeView.Draft draft,
            DisposeIntent intent,
            Set<String> dirty,
            ExecutableCommitPlan executable) {
        MountHandleImpl handle = requireComponent(draft, intent.handle());
        StructureGraphMutator.MutationResult disposed =
                StructureGraphMutator.disposeComponent(
                        draft,
                        handle.handleId(),
                        StructureGraphMutator.activePublicationSlotRefs(
                                baseState.index, executable));
        disposed.applyTo(executable);
        dirty.addAll(disposed.dirty());
        return true;
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


    // 在同一个协调器临界区内先更新索引草稿，再与 next 一起原子发布。
    private void commitExecutable(
            ExecutionIndex base,
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
            ExecutableIndexSynchronizer.removeRemovedComponent(
                    base, indexDraft, handleId);
        }
        ExecutableIndexSynchronizer.synchronizePublicationSlotRefs(
                base, indexDraft, executable);

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
            ExecutableIndexSynchronizer.removeRetiredRegistration(
                    base, indexDraft, registrationId);
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
                        ExecutableIndexSynchronizer.removeRetiredRegistration(
                                base, indexDraft, revoke.handle().registrationId());
                case PublicationProvideIntent provide -> {
                    if (next.registrations.containsKey(
                            provide.handle().registrationId())) {
                        indexDraft.registrationHandles().put(
                                provide.handle().registrationId(),
                                provide.handle());
                    }
                }
                case PublicationUnpublishIntent ignored -> {
                }
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

    List<CompletableFuture<ComponentState>> schedule(Set<String> dirty) {
        return activationCoordinator.schedule(dirty);
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
}
