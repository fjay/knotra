package io.knotra.internal;

import io.knotra.ActivationContext;
import io.knotra.ActivationState;
import io.knotra.AdvancedRuntime;
import io.knotra.PendingOperationsSnapshot;
import io.knotra.PublicationOperation;
import io.knotra.PublicationState;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
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
 * Runtime 内核默认实现，拥有宿主事务、Activation 状态机与最终状态发布；组件过渡的预约、
 * 拓扑排序和锁外驱动委托给 {@link TransitionScheduler}。
 *
 * <p>已提交结构由 {@link KernelStateStore} 以单一 volatile 状态发布；所有草稿校验、代际提交和可执行索引同步
 * 都在 {@code coordinator} 临界区内完成。Factory、normalizer 和用户 {@code start()} 不持有协调器锁，因此
 * 慢用户代码只能阻塞自身 Activation，不能阻塞其他宿主事务或 Snapshot。</p>
 *
 * <p>Activation 最终提交采用固定管线：prepublish 阶段在协调器内校验候选、冻结暂存注册与子挂载、
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
    // 过渡预约/拓扑/驱动属调度器；runtime 只保留状态机 driver 与最终发布权。
    final TransitionScheduler transitionScheduler;
    // 唯一 volatile 状态持有者：读方一次拿到 view 与 live membership 的同代组合。
    private final KernelStateStore kernelState;

    // dispose/Context 处置去重与 pending 元数据统一收口到叶子注册表；方法体不回调 runtime。
    private final DisposeRequestRegistry disposeRequestRegistry =
            new DisposeRequestRegistry();
    private final ContextDisposalRequestRegistry contextDisposalRegistry =
            new ContextDisposalRequestRegistry();
    private final RetiredProviderLeaseRegistry retiredProviderLeases =
            new RetiredProviderLeaseRegistry();
    // close 先于新事务置位；失败的未来可被替换以便重试关闭，成功后复用同一结果。
    private final AtomicBoolean closing = new AtomicBoolean();
    private final AtomicReference<CompletableFuture<Void>> closeFuture =
            new AtomicReference<>();
    private volatile long closeStartNanos;
    private volatile boolean closeStartPresent;
    private final AdvancedRuntime advanced = new RuntimeAdvancedFacade(this);
    private final BindingImpactAnalyzer bindingImpacts = new BindingImpactAnalyzer();
    private final ActivationCandidateFactory activationCandidates;
    private final DynamicCapabilityBroker dynamicBroker = new DynamicCapabilityBroker(this);
    // 包内测试探针：在 Activation 裁决释放协调器后、完成/清理前构造结构事务竞态。
    volatile Runnable activationDecisionProbe;
    // 包内测试探针：在 Activation prepublish 候选构造完成后、final publish 前注入故障。
    volatile Runnable activationPrepublishProbe;
    // 包内测试探针：在 final publish 计算前注入故障，覆盖 nextState() 抛出的恢复路径。
    volatile Runnable activationFinalPublishProbe;
    // 包内测试探针：在 final publish 提交后、效果 apply 前注入提交后效果故障。
    volatile Runnable activationPostPublishEffectProbe;
    // 包内测试探针：在非终态视图发布后、过渡驱动前观察 whenSettled 的可见行为。
    volatile Runnable transitionPublicationProbe;
    // 包内测试探针：在 whenSettled 读取状态后、进入 chainLock 前暂停；探针须先清空自身再阻塞。
    volatile Runnable whenSettledObservationProbe;
    // 包内测试探针：在第 N 个已提交 provider lease retire 前注入故障。
    volatile IntConsumer providerLeaseRetireFaultProbe;
    // 包内测试探针：在中止候选 final publish 后注入提交后故障。
    volatile Runnable activationRollbackCommitProbe;
    // 包内测试探针：在 finishCleanup 最终提交前注入竞争代际或故障。
    volatile Runnable cleanupFinalCommitProbe;
    // 包内测试探针：在 Context 最终化提交前注入竞争代际或故障。
    volatile Runnable contextFinalCommitProbe;

    DefaultKnotraRuntime(KnotraConfig configuration) {
        this(configuration, System::nanoTime);
    }

    // 包内测试可注入单调时钟；生产路径始终使用 System.nanoTime()。
    DefaultKnotraRuntime(KnotraConfig configuration, LongSupplier ticker) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.ticker = Objects.requireNonNull(ticker, "ticker");
        this.activationCandidates = new ActivationCandidateFactory(configuration);
        this.transitionScheduler = new TransitionScheduler(
                coordinator, executor, this::driveTransition, ticker);
        this.kernelState = KernelStateStore.initial(
                coordinator, new ContextHandleImpl(this, "ctx-root"));
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
        Set<String> contextDisposals = new LinkedHashSet<>();
        String postCommitFailure = null;
        List<CompletableFuture<Void>> registrationDrains = List.of();
        Map<String, ProviderLeaseRuntime> retiredRegistrations = Map.of();
        ExecutableCommitPlan executable = new ExecutableCommitPlan();
        List<ComponentRuntime.Reservation> reservations = new ArrayList<>();
        TransitionPlan transitionPlan = TransitionPlan.EMPTY;
        boolean committed = false;
        RuntimeView committedView = null;
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
                                state, draft, intent, dirty, executable);
                    }
                    bindingImpacts.markBindingImpacts(state, draft, dirty, executable);
                    if (!viewChanged) {
                        return new TransactionReceipt<>(
                                callbackValue,
                                DefaultSettlement.empty(state.view.generation));
                    }
                    transitionPlan = transitionScheduler.prepare(
                            state,
                            draft,
                            dirty,
                            executable,
                            indexDraft,
                            reservations);
                    DiagnosticSupport.refresh(draft, state.index, configuration);
                    RuntimeView next = draft.publishOnce();
                    commitExecutable(next, intents, executable, indexDraft);
                    PublishedKernelState candidate = indexDraft.publish(next);
                    completeTerminalPublicationRefs(executable);
                    kernelState.commitLocked(state, candidate);
                    committedView = next;
                    committed = true;
                    retiredRegistrations = Map.copyOf(executable.retiredRegistrations);
                    committedGeneration = next.generation;
                    postCommitDirty.addAll(dirty);
                    contextDisposals.addAll(executable.contextDisposals);
                    requestContextCleanupIntents(
                            candidate.index, executable.cleanupRetryIntents);
                    runTransitionPublicationProbe();
                } catch (Reject rejection) {
                    throw new TransactionRejectedException(List.of(rejection.diagnostic()));
                }
            }
        } finally {
            if (committed) {
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
                        transitionScheduler.drive(transitionPlan));
            } else {
                transitionScheduler.completeCancelled(
                        transitionScheduler.cancelCreated(reservations));
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
        ContextHandleImpl root = kernelState.read().index.contextHandles.get("ctx-root");
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
        samples.addAll(contextDisposalRegistry.pending());
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
        return transitionScheduler.enqueue(component);
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
        return disposeContextInView(handle, false);
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
        RuntimeView current = kernelState.read().view;
        return current.components.values().stream().anyMatch(component ->
                component.contextId().equals(contextId)
                        && component.mountId().equals(mountId));
    }

    private boolean applyIntent(
            PublishedKernelState baseState,
            RuntimeView.Draft draft,
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
                    applyContextDispose(
                            baseState, draft, dispose, dirty, executable);
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

    private boolean applyContextDispose(
            PublishedKernelState baseState,
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
        StructureGraphMutator.MutationResult disposed =
                StructureGraphMutator.disposeContext(
                        draft,
                        handle.contextId(),
                        StructureGraphMutator.activePublicationSlotRefs(
                                baseState.index, executable));
        disposed.applyTo(executable);
        dirty.addAll(disposed.dirty());
        executable.cleanupRetryIntents.addAll(
                disposed.reportedRemovedMounts().keySet());
        return true;
    }

    // 事务与直接 Context 处置都要唤醒已失败的清理；否则子树最终化会永远停在 FAILED。
    private void requestContextCleanupIntents(
            ExecutionIndex index,
            Set<String> handles) {
        for (String handleId : handles) {
            ComponentRuntime component = index.components.get(handleId);
            if (component != null && component.failedCleanup() != null) {
                component.requestRetryLocked(ComponentRuntime.RetryIntent.CLEANUP);
            }
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


    private CommittedLeaseRetirement retireCommittedRegistrations(
            Map<String, ProviderLeaseRuntime> retiredRegistrations,
            String failureScope) {
        List<CompletableFuture<Void>> drains = new ArrayList<>();
        String failure = null;
        int leaseIndex = 0;
        for (Map.Entry<String, ProviderLeaseRuntime> entry
                : retiredRegistrations.entrySet()) {
            try {
                // 故障注入点必须在真实 retire 之后：probe 异常只模拟 retire 已完成
                // 后的 postcommit 阶段故障，不能遗留未登记、未 retire 的孤立租约。
                drains.add(retireProviderLease(entry.getKey(), entry.getValue()));
                runProviderLeaseRetireFaultProbe(leaseIndex++);
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
        syncPublicationSlotRefs(executable, indexDraft);

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

    /**
     * 同步 Publication 槽位 ref 到索引草稿：先登记新建/既有槽位的共享 ref，
     * 再移除终态槽位；同一事务内 create+terminalize 同一坐标时以终态移除为准。
     */
    private static void syncPublicationSlotRefs(
            ExecutableCommitPlan executable, KernelStateDraft indexDraft) {
        if (executable.createdPublicationSlots.isEmpty()
                && executable.terminalPublicationSlots.isEmpty()) {
            return;
        }
        Map<String, PublicationSlotTerminalRef> refs = indexDraft.publicationSlotRefs();
        executable.createdPublicationSlots.forEach(refs::putIfAbsent);
        executable.terminalPublicationSlots.keySet().forEach(refs::remove);
    }

    List<CompletableFuture<ComponentState>> schedule(Set<String> dirty) {
        List<ComponentRuntime.Reservation> reservations = new ArrayList<>();
        TransitionPlan plan = TransitionPlan.EMPTY;
        List<CompletableFuture<ComponentState>> cancelled = List.of();
        try {
            synchronized (coordinator) {
                plan = transitionScheduler.schedule(
                        kernelState.read(), dirty, reservations);
            }
        } catch (Throwable error) {
            cancelled = transitionScheduler.cancelCreated(reservations);
            throw error;
        } finally {
            transitionScheduler.completeCancelled(cancelled);
        }
        transitionScheduler.drive(plan);
        return plan.futures();
    }

    private List<CompletableFuture<ComponentState>> concatFutures(
            List<CompletableFuture<ComponentState>> first,
            List<CompletableFuture<ComponentState>> second) {
        List<CompletableFuture<ComponentState>> result =
                new ArrayList<>(first);
        result.addAll(second);
        return result;
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
        return component.finishTransition(future, state);
    }

    // 提交失败路径与正常完成共用锁纪律：锁内只清槽，完成动作出锁派发。
    private Runnable prepareTransitionFailureCompletion(
            ComponentRuntime component,
            CompletableFuture<ComponentState> future,
            Throwable error) {
        return component.failTransition(future, error);
    }

    private void runCleanupFinalCommitProbe() {
        Runnable probe = cleanupFinalCommitProbe;
        if (probe != null) {
            probe.run();
        }
    }

    private void runContextFinalCommitProbe() {
        Runnable probe = contextFinalCommitProbe;
        if (probe != null) {
            probe.run();
        }
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


    // 单组件状态机入口：锁内只选择/登记候选 Activation，用户 start() 随后在锁外执行。
    void driveTransition(ComponentRuntime reserved, CompletableFuture<ComponentState> future) {
        String handleId = reserved.handleId();
        PublishedKernelState entryState = kernelState.read();
        ComponentRuntime component = entryState.index.components.get(handleId);
        if (component == null) {
            dispatchCompletion(reserved.finishTransition(
                    future, ComponentState.DISPOSED));
            return;
        }
        try {
            ActivationRuntime activation = null;
            ComponentState immediateState = null;
            Runnable completion = null;
            synchronized (coordinator) {
                PublishedKernelState state = kernelState.read();
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
                dispatchCompletion(component.finishTransition(future, state));
                return;
            }
            // 锁外复核：若结构事务已使候选 stale 或作用域开始清理，直接走回滚路径。
            // 已 closed 的 Activation 表示 start 已裁决完，只能推进清理，不得重入 start。
            PublishedKernelState staleState = kernelState.read();
            RuntimeView.ComponentData staleComponent =
                    staleState.view.components.get(handleId);
            RuntimeView.ActivationData staleActivation =
                    staleState.view.activations.get(activation.activationId);
            if (activation.closed.get()
                    || activation.scope.state() != LifecycleState.OPEN
                    || staleComponent == null
                    || staleComponent.currentActivationId() == null
                    || staleActivation == null
                    || staleActivation.state() == ActivationState.STOPPING) {
                finishCleanupAfterDependents(component, activation, future);
                return;
            }
            runActivation(component, activation, future);
        } catch (Throwable transitionError) {
            dispatchCompletion(component.failTransition(future, new IllegalStateException(
                    "component transition failed", transitionError)));
        }
    }

    private Runnable retainFailedCleanupLocked(
            ComponentRuntime component,
            RuntimeView.ComponentData data,
            CompletableFuture<ComponentState> future) {
        PublishedKernelState state = kernelState.read();
        RuntimeView.Draft draft = new RuntimeView.Draft(state.view);
        KernelStateDraft indexDraft = new KernelStateDraft(state);
        draft.components.put(
                component.handleId(),
                data.withState(ComponentState.FAILED));
        DiagnosticSupport.refresh(draft, state.index, configuration);
        kernelState.commitLocked(state, indexDraft.publish(draft.publishOnce()));
        return prepareTransitionCompletion(component, future, ComponentState.FAILED);
    }

    private Runnable finalizeOrphanedStoppingLocked(
            ComponentRuntime component,
            RuntimeView.ComponentData data,
            CompletableFuture<ComponentState> future) {
        PublishedKernelState state = kernelState.read();
        RuntimeView.Draft draft = new RuntimeView.Draft(state.view);
        KernelStateDraft indexDraft = new KernelStateDraft(state);
        ComponentState stateResult;
        if (data.goal() == ComponentGoal.DISPOSED) {
            StructureGraphMutator.removeComponent(draft, component.handleId());
            indexDraft.components().remove(component.handleId());
            indexDraft.componentHandles().remove(component.handleId());
            stateResult = ComponentState.DISPOSED;
        } else {
            draft.components.put(
                    component.handleId(),
                    data.withState(ComponentState.WAITING).clearActivation());
            stateResult = ComponentState.WAITING;
        }
        DiagnosticSupport.refresh(draft, state.index, configuration);
        kernelState.commitLocked(state, indexDraft.publish(draft.publishOnce()));
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
        PublishedKernelState state = kernelState.read();
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
        DiagnosticSupport.refresh(draft, state.index, configuration);
        indexDraft.activations().put(activationId, activation);
        kernelState.commitLocked(state, indexDraft.publish(draft.publishOnce()));
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
            String finalPublishFailure = null;
            try {
                PublishedKernelState state = kernelState.read();
                ActivationCandidateFactory.FrozenActivationInputs inputs =
                        freezeActivationInputs(
                                state,
                                runtime,
                                activation,
                                frozenPlans,
                                frozenRegistrations,
                                startEvidence);
                CommitDecision decision =
                        activationCandidates.validate(inputs, state);
                candidate = completeActivationCandidate(
                        activationCandidates.prepare(inputs, state, decision),
                        state,
                        frozenPlans,
                        createdReservations);
                runActivationPrepublishProbe();
                runActivationFinalPublishProbe();
                nextState = candidate.nextState();
            } catch (Throwable prepublishError) {
                // prepublish/final publish 计算失败：取消本候选已创建的预约，
                // 不发布任何成功 child/registration。
                cancelledTransitions =
                        transitionScheduler.cancelCreated(createdReservations);
                String message = "activation commit failed: "
                        + LifecycleScopeImpl.safeError(prepublishError);
                try {
                    PublishedKernelState abortState = kernelState.read();
                    candidate = completeActivationCandidate(
                            activationCandidates.prepareAborted(
                                    freezeActivationInputs(
                                            abortState,
                                            runtime,
                                            activation,
                                            frozenPlans,
                                            frozenRegistrations,
                                            startEvidence),
                                    abortState,
                                    CommitDecision.commitFailed(message)),
                            abortState,
                            List.of(),
                            createdReservations);
                    nextState = candidate.nextState();
                } catch (Throwable fatal) {
                    cancelledTransitions = concatFutures(
                            cancelledTransitions,
                            transitionScheduler.cancelCreated(createdReservations));
                    emergencyRollback = true;
                    try {
                        PublishedKernelState emergencyState = kernelState.read();
                        candidate = completeActivationCandidate(
                                activationCandidates.prepareEmergency(
                                        freezeActivationInputs(
                                                emergencyState,
                                                runtime,
                                                activation,
                                                frozenPlans,
                                                frozenRegistrations,
                                                startEvidence),
                                        emergencyState,
                                        message,
                                        true),
                                emergencyState,
                                List.of(),
                                createdReservations);
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
                // 单一 final publish：提交即不可逆提交点，此后不得回滚已提交结构或换回旧状态。
                // 终态 ref 必须先完成再发布：读到新代际的旧句柄立即观察到终态。
                try {
                    candidate.completePublicationTerminals();
                    kernelState.commitLocked(candidate.base(), nextState);
                } catch (Throwable finalPublishError) {
                    finalPublishFailure = "activation final publish failed: "
                            + LifecycleScopeImpl.safeError(finalPublishError);
                    cancelledTransitions = concatFutures(
                            cancelledTransitions,
                            transitionScheduler.cancelCreated(
                                    candidate.transitionPlan().reservations()));
                    // 首候选的成功 child/registration 从未发布。仍在同一临界区内，从最新代际
                    // 重新认领 STARTING activation 并构造中止清理候选，按最新 expected 提交；
                    // 恢复提交也失败时退到紧急 FAILED + failedCleanup/retry 意图。
                    try {
                        runtime.claimCurrentLocked(activation);
                        PublishedKernelState recoveryState = kernelState.read();
                        candidate = completeActivationCandidate(
                                activationCandidates.prepareAborted(
                                        freezeActivationInputs(
                                                recoveryState,
                                                runtime,
                                                activation,
                                                frozenPlans,
                                                frozenRegistrations,
                                                startEvidence),
                                        recoveryState,
                                        CommitDecision.commitFailed(finalPublishFailure)),
                                recoveryState,
                                List.of(),
                                createdReservations);
                        nextState = candidate.nextState();
                        candidate.completePublicationTerminals();
                        kernelState.commitLocked(candidate.base(), nextState);
                    } catch (Throwable recoveryError) {
                        cancelledTransitions = concatFutures(
                                cancelledTransitions,
                                transitionScheduler.cancelCreated(createdReservations));
                        emergencyRollback = true;
                        try {
                            PublishedKernelState lastState = kernelState.read();
                            candidate = completeActivationCandidate(
                                    activationCandidates.prepareEmergency(
                                            freezeActivationInputs(
                                                    lastState,
                                                    runtime,
                                                    activation,
                                                    frozenPlans,
                                                    frozenRegistrations,
                                                    startEvidence),
                                            lastState,
                                            finalPublishFailure,
                                            true),
                                    lastState,
                                    List.of(),
                                    createdReservations);
                            nextState = candidate.nextState();
                            candidate.completePublicationTerminals();
                            kernelState.commitLocked(candidate.base(), nextState);
                        } catch (Throwable lastResort) {
                            // 连恢复与紧急候选都无法提交：保持最后已发布结构，
                            // 下方仅以异常完成原始 future，绝不留下 pending。
                            candidate = null;
                            nextState = null;
                            emergencyFailure = finalPublishFailure
                                    + "; recovery publish failed: "
                                    + LifecycleScopeImpl.safeError(recoveryError)
                                    + "; emergency publish failed: "
                                    + LifecycleScopeImpl.safeError(lastResort);
                        }
                    }
                }
                if (candidate != null && nextState != null) {
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
                    if (finalPublishFailure != null) {
                        // 首选 final publish 已被拒绝：原始 future 必须异常完成；
                        // 恢复候选的清理预约照常驱动，组件不得悬停在 STARTING。
                        postCommitFailure = appendPostCommitFailure(
                                postCommitFailure, finalPublishFailure);
                    }
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
        transitionScheduler.completeCancelled(cancelledTransitions);
        Runnable decisionProbe = activationDecisionProbe;
        if (decisionProbe != null) {
            decisionProbe.run();
        }

        if (candidate == null) {
            dispatchCompletion(runtime.failTransition(
                    future,
                    new IllegalStateException(emergencyFailure)));
            return;
        }

        // 后续提交后效果若失败，lease 已登记 retired registry，结构保持 final publish。
        postCommitFailure = appendPostCommitFailure(
                postCommitFailure,
                transitionScheduler.drive(candidate.transitionPlan()));
        if (emergencyRollback) {
            dispatchCompletion(runtime.failTransition(
                    future,
                    new IllegalStateException("emergency activation rollback: "
                            + candidate.emergencyMessage())));
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
            dispatchCompletion(runtime.failTransition(
                    future,
                    new IllegalStateException(postCommitFailure)));
            if (cleanupRequired) {
                finishCleanupAfterDependents(runtime, activation, future);
            }
            return;
        }

        Runnable transitionCompletion = null;
        if (!cleanupRequired) {
            synchronized (coordinator) {
                // 结构事务可能在 Activation 裁决释放锁后把同一 future 改成 STOPPING。
                PublishedKernelState state = kernelState.read();
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

    // coordinator 内先把 live 可变状态冻结为纯 facts；候选工厂不再读取 ComponentRuntime 字段。
    private ActivationCandidateFactory.FrozenActivationInputs freezeActivationInputs(
            PublishedKernelState state,
            ComponentRuntime runtime,
            ActivationRuntime activation,
            List<ChildMountPlan> plans,
            Map<String, RuntimeView.RegistrationData> stagedRegistrations,
            StartFailureEvidence startEvidence) {
        List<ActivationCandidateFactory.FrozenActivationInputs.PendingActivation>
                pendingActivations = new ArrayList<>();
        for (Map.Entry<String, ActivationRuntime> entry
                : state.index.activations.entrySet()) {
            ActivationRuntime pending = entry.getValue();
            pendingActivations.add(
                    new ActivationCandidateFactory.FrozenActivationInputs.PendingActivation(
                            pending.activationId,
                            pending.stale.get(),
                            pending.stagedRegistrations));
        }
        ComponentRuntime.ComponentFailureState failure = runtime.failureState();
        ComponentRuntime.ComponentReconcileState reconcile = runtime.reconcileState();
        return new ActivationCandidateFactory.FrozenActivationInputs(
                state,
                plans,
                stagedRegistrations,
                startEvidence,
                liveCapabilityTypes(state),
                pendingActivations,
                new ActivationCandidateFactory.FrozenActivationInputs.OwnerFacts(
                        runtime.handleId(),
                        runtime.desiredState().revision(),
                        failure.pendingStartFailure(),
                        failure.lastStartError(),
                        failure.lastStartFailure(),
                        reconcile.suppressAutoRestart()),
                new ActivationCandidateFactory.FrozenActivationInputs.ActivationFacts(
                        activation.activationId,
                        activation.configRevision,
                        activation.bindings,
                        activation.initialDynamicRequiredPresence,
                        activation.stale.get()));
    }

    // 候选草稿完成后由 runtime 注入 transition 预约；工厂本身始终不触碰调度器。
    private ActivationCommitCandidate completeActivationCandidate(
            ActivationCandidateFactory.PreparedCandidate prepared,
            PublishedKernelState state,
            List<ChildMountPlan> plans,
            List<ComponentRuntime.Reservation> createdReservations) {
        if (prepared.indexDraft().base() != state) {
            throw new IllegalStateException(
                    "activation candidate is not based on caller state");
        }
        TransitionPlan transitionPlan = TransitionPlan.EMPTY;
        if (prepared.scheduleTransitions()) {
            precreateChildRuntimes(
                    prepared.draft(),
                    plans,
                    prepared.executable(),
                    prepared.indexDraft());
            transitionPlan = transitionScheduler.prepare(
                    state,
                    prepared.draft(),
                    prepared.dirty(),
                    prepared.executable(),
                    prepared.indexDraft(),
                    createdReservations);
        }
        DiagnosticSupport.refresh(prepared.draft(), state.index, configuration);
        applyIndexEffects(
                prepared.draft(),
                prepared.indexDraft(),
                prepared.executable(),
                plans);
        return prepared.toCandidate(transitionPlan);
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
        syncPublicationSlotRefs(executable, indexDraft);
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
                kernelState.read().view.components.get(runtime.handleId());
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
                .map(transitionScheduler::enqueue)
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
        PublishedKernelState state = kernelState.read();
        RuntimeView current = state.view;
        Map<String, Set<String>> dependencies = transitionScheduler.stopProviders(
                state,
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
                PublishedKernelState kernelSnapshot = kernelState.read();
                RuntimeView.Draft draft = new RuntimeView.Draft(kernelSnapshot.view);
                KernelStateDraft indexDraft = new KernelStateDraft(kernelSnapshot);
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
                            StructureGraphMutator.removeComponent(
                                    draft, runtime.handleId());
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
                DiagnosticSupport.refresh(draft, kernelSnapshot.index, configuration);
                runCleanupFinalCommitProbe();
                try {
                    kernelState.commitLocked(
                            kernelSnapshot, indexDraft.publish(draft.publishOnce()));
                } catch (Throwable commitError) {
                    // 锁内只清过渡槽；异常完成必须出锁派发，避免回调重入协调器死锁。
                    Throwable failure = new IllegalStateException(
                            "component cleanup publish failed: "
                                    + LifecycleScopeImpl.safeError(commitError),
                            commitError);
                    transitionCompletion = prepareTransitionFailureCompletion(
                            runtime, future, failure);
                }
                if (transitionCompletion == null) {
                    // 仍在协调器内替换过渡链，随后到锁外提交新 Activation，保证旧请求先有结果。
                    if (restart) {
                        restartReservation = runtime.replaceTransition(
                                pendingTime(), "component restart");
                    } else {
                        transitionCompletion = prepareTransitionCompletion(runtime, future, state);
                    }
                }
            }

            dispatchCompletion(transitionCompletion);
            if (restartReservation != null) {
                runtime.finishTransition(future);
                transitionScheduler.driveReservation(restartReservation);
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
        RuntimeView current = kernelState.read().view;
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
        TransitionPlan transitionPlan = TransitionPlan.EMPTY;
        boolean committed = false;
        try {
            synchronized (coordinator) {
                PublishedKernelState state = kernelState.read();
                RuntimeView.ContextData data =
                        state.view.contexts.get(handle.contextId());
                if (data == null || data.state() == ContextState.DISPOSED) {
                    return CompletableFuture.completedFuture(null);
                }
                // 注册表合并是 CHM 叶子短操作：允许协调器内调用，但不形成第二把嵌套监视器。
                ContextDisposalRequestRegistry.Registration disposalRegistration =
                        contextDisposalRegistry.getOrCreate(
                                handle.contextId(), pendingTime());
                if (!disposalRegistration.created()) {
                    return disposalRegistration.future();
                }
                future = disposalRegistration.future();

                RuntimeView.Draft draft = new RuntimeView.Draft(state.view);
                KernelStateDraft indexDraft = new KernelStateDraft(state);
                ExecutableCommitPlan executable = new ExecutableCommitPlan();
                StructureGraphMutator.MutationResult disposed =
                        StructureGraphMutator.disposeContext(
                                draft,
                                handle.contextId(),
                                StructureGraphMutator.activePublicationSlotRefs(
                                        state.index, executable));
                disposed.applyTo(executable);
                dirty = new LinkedHashSet<>(disposed.dirty());
                subtree = disposed.subtree();
                executable.cleanupRetryIntents.addAll(
                        disposed.reportedRemovedMounts().keySet());
                transitionPlan = transitionScheduler.prepare(
                        state,
                        draft,
                        dirty,
                        executable,
                        indexDraft,
                        reservations);
                DiagnosticSupport.refresh(draft, state.index, configuration);
                RuntimeView next = draft.publishOnce();
                commitExecutable(next, List.of(), executable, indexDraft);
                PublishedKernelState candidate = indexDraft.publish(next);
                completeTerminalPublicationRefs(executable);
                kernelState.commitLocked(state, candidate);
                committed = true;
                requestContextCleanupIntents(
                        candidate.index, executable.cleanupRetryIntents);
                retiredRegistrations = Map.copyOf(executable.retiredRegistrations);
                runTransitionPublicationProbe();
            }
        } finally {
            if (committed) {
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
                        transitionScheduler.drive(transitionPlan));
            } else {
                transitionScheduler.completeCancelled(
                        transitionScheduler.cancelCreated(reservations));
                if (future != null) {
                    contextDisposalRegistry.remove(handle.contextId(), future);
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
        Runnable contextCompletion = null;
        synchronized (coordinator) {
            PublishedKernelState state = kernelState.read();
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
            DiagnosticSupport.refresh(draft, state.index, configuration);
            RuntimeView next = draft.publishOnce();
            runContextFinalCommitProbe();
            try {
                kernelState.commitLocked(state, indexDraft.publish(next));
            } catch (Throwable commitError) {
                contextDisposalRegistry.removeAll(subtree);
                if (future != null) {
                    // 锁内只移除去重表；异常完成出锁派发，回调可安全重入协调器。
                    Throwable failure = new IllegalStateException(
                            "context finalization publish failed", commitError);
                    contextCompletion = () -> future.completeExceptionally(failure);
                }
            }
            if (contextCompletion == null) {
                contextDisposalRegistry.removeAll(subtree);
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
        }
        dispatchCompletion(contextCompletion);
    }

    // 事务内处置在组件收敛完成后才结算；这里为外层 Context 创建去重的最终化 Future。
    private CompletableFuture<Void> settleContextDisposal(
            String contextId,
            CompletableFuture<Void> prerequisite) {
        CompletableFuture<Void> future;
        Set<String> subtree;
        Set<String> components;
        synchronized (coordinator) {
            RuntimeView current = kernelState.read().view;
            if (!current.contexts.containsKey(contextId)) {
                return CompletableFuture.completedFuture(null);
            }
            ContextDisposalRequestRegistry.Registration registration =
                    contextDisposalRegistry.getOrCreate(contextId, pendingTime());
            if (!registration.created()) {
                return registration.future();
            }
            future = registration.future();
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
}
