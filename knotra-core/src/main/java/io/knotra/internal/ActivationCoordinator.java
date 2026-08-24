package io.knotra.internal;

import io.knotra.ActivationContext;
import io.knotra.ActivationState;
import io.knotra.CapabilityRequirement;
import io.knotra.ComponentGoal;
import io.knotra.ComponentState;
import io.knotra.FailureInfo;
import io.knotra.FailurePhase;
import io.knotra.KnotraConfig;
import io.knotra.LifecycleState;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BiConsumer;
import java.util.function.IntConsumer;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Owns the activation and cleanup state machine.
 *
 * <p>The coordinator is the only TransitionDriver implementation and owns transition scheduling
 * plus retired provider leases. Host transactions borrow the scheduler through narrow accessors;
 * user start/cleanup and lease drains always run outside the coordinator lock.</p>
 */
final class ActivationCoordinator implements StructuralPostCommitPort {
    private final Object coordinator;
    private final KernelStateStore kernelState;
    private final KnotraConfig configuration;
    private final Executor executor;
    private final LongSupplier ticker;
    private final TransitionScheduler transitionScheduler;
    private final ActivationCandidateFactory activationCandidates;
    private final RetiredProviderLeaseRegistry retiredProviderLeases =
            new RetiredProviderLeaseRegistry();
    private final ActivationHost host;

    // Package test probes; all are outside the production API.
    volatile Runnable activationDecisionProbe;
    volatile Runnable activationPrepublishProbe;
    volatile Runnable activationFinalPublishProbe;
    volatile Runnable activationPostPublishEffectProbe;
    volatile Runnable transitionPublicationProbe;
    volatile IntConsumer providerLeaseRetireFaultProbe;
    volatile Runnable activationRollbackCommitProbe;
    volatile Runnable cleanupFinalCommitProbe;
    // 包内测试探针：拦截 postcommit 失败路径对原始 future 的异常完成派发。
    // 测试可先持有 completion、稍后运行，但必须最终运行，否则原始 future 永久 pending。
    volatile BiConsumer<CompletableFuture<ComponentState>, Runnable>
            activationFailureCompletionGate;

    ActivationCoordinator(
            Object coordinator,
            KernelStateStore kernelState,
            KnotraConfig configuration,
            Executor executor,
            LongSupplier ticker,
            ActivationHost host) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.kernelState = Objects.requireNonNull(kernelState, "kernelState");
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.ticker = Objects.requireNonNull(ticker, "ticker");
        this.host = Objects.requireNonNull(host, "host");
        this.activationCandidates = new ActivationCandidateFactory(configuration);
        this.transitionScheduler = new TransitionScheduler(
                coordinator, executor, this::driveTransition, ticker);
    }

    TransitionScheduler scheduler() {
        return transitionScheduler;
    }

    @Override
    public PreparedTransitions prepare(
            PublishedKernelState base,
            RuntimeView.Draft draft,
            Set<String> dirty,
            ExecutableCommitPlan executable,
            KernelStateDraft index) {
        assert Thread.holdsLock(coordinator);
        List<ComponentRuntime.Reservation> reservations = new ArrayList<>();
        TransitionPlan plan;
        try {
            plan = transitionScheduler.prepare(
                    base, draft, dirty, executable, index, reservations);
        } catch (Throwable preparationError) {
            // prepare 可能已创建预约再失败；先在协调器内清槽，出锁后异常完成 future。
            List<CompletableFuture<ComponentState>> cancelled =
                    transitionScheduler.cancelCreated(reservations);
            dispatchCancellation(cancelled);
            throw preparationError;
        }
        return new PreparedTransitions(plan, reservations);
    }

    @Override
    public void applyCommittedEffectsLocked(
            PreparedTransitions transitions,
            ExecutableCommitPlan executable,
            ExecutionIndex committedIndex) {
        assert Thread.holdsLock(coordinator);
        requestContextCleanupIntents(committedIndex, executable.cleanupRetryIntents);
        runTransitionPublicationProbe();
    }

    @Override
    public CommittedEffects finishCommittedEffects(
            PreparedTransitions transitions,
            ExecutableCommitPlan executable) {
        assert !Thread.holdsLock(coordinator);
        CommittedLeaseRetirement retirement = retireCommittedRegistrations(
                executable.retiredRegistrations, "structural postcommit");
        String failure = PostCommitFaults.append(
                retirement.failure(), transitionScheduler.drive(transitions.transitionPlan()));
        return new CommittedEffects(retirement.drains(), failure);
    }

    @Override
    public void cancel(PreparedTransitions transitions) {
        assert !Thread.holdsLock(coordinator);
        transitionScheduler.completeCancelled(
                transitionScheduler.cancelCreated(transitions.reservations()));
    }

    List<ProviderLeaseRuntime> pendingProviderLeases() {
        return retiredProviderLeases.pending();
    }

    @Override
    public List<CompletableFuture<ComponentState>> schedule(Set<String> dirty) {
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

    void runTransitionPublicationProbe() {
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
                        ? currentState(handleId)
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
            ExecutableIndexSynchronizer.removeRemovedComponent(
                    state.index, indexDraft, component.handleId());
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

    private ComponentState currentState(String handleId) {
        RuntimeView.ComponentData data =
                kernelState.read().view.components.get(handleId);
        return data == null ? ComponentState.DISPOSED : data.state();
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
        ActivationContext context = host.activationContext(activation, plans);
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
                        postCommitFailure = PostCommitFaults.append(
                                postCommitFailure, finalPublishFailure);
                    }
                }
            }
        }
        if (candidate != null) {
            CommittedLeaseRetirement retirement = retireCommittedRegistrations(
                    candidate.postCommitEffects().leasesToRetire(),
                    "activation postcommit");
            postCommitFailure = PostCommitFaults.append(
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
        postCommitFailure = PostCommitFaults.append(
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
            // failTransition 已在此清槽，后续 cleanup 不得再竞争完成该 future。
            dispatchPrimaryFailureCompletion(
                    future,
                    runtime.failTransition(
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

    // 候选草稿完成后注入 transition 预约；工厂本身始终不触碰调度器。
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
                state.index,
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
            ExecutionIndex base,
            RuntimeView.Draft draft,
            KernelStateDraft indexDraft,
            ExecutableCommitPlan executable,
            List<ChildMountPlan> plans) {
        for (String handleId : executable.removedComponents.keySet()) {
            if (draft.components.containsKey(handleId)) {
                continue;
            }
            ExecutableIndexSynchronizer.removeRemovedComponent(
                    base, indexDraft, handleId);
        }
        for (String registrationId : executable.retiredRegistrations.keySet()) {
            ExecutableIndexSynchronizer.removeRetiredRegistration(
                    base, indexDraft, registrationId);
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
        ExecutableIndexSynchronizer.synchronizePublicationSlotRefs(
                base, indexDraft, executable);
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
            ComponentRuntime.RestartReservation restartReservation = null;
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
                                Instant.now());
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
                            ExecutableIndexSynchronizer.removeRemovedComponent(
                                    kernelSnapshot.index,
                                    indexDraft,
                                    runtime.handleId());
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
                    if (restart) {
                        // 原子区分三种所有权：仍拥有旧 future 则望远镜替换；
                        // primary 失败已清槽则独立 restart；已有新所有者则不重复预约。
                        restartReservation = runtime.reserveRestart(
                                future,
                                ticker.getAsLong(),
                                "component restart");
                    } else {
                        transitionCompletion = prepareTransitionCompletion(runtime, future, state);
                    }
                }
            }

            dispatchCompletion(transitionCompletion);
            if (restartReservation != null && restartReservation.created()) {
                transitionScheduler.driveReservation(restartReservation.reservation());
                // INDEPENDENT 分支绝不链接/完成旧 future：旧 F 的异常完成由 primary
                // 失败路径独占；这里只推进新 restart future 自身收敛。
                if (restartReservation.linksOriginalFuture()) {
                    restartReservation.reservation().future().whenComplete((next, error) -> {
                        if (error != null) {
                            future.completeExceptionally(error);
                        } else {
                            future.complete(next);
                        }
                    });
                }
            } else if (restartReservation != null) {
                attachForeignRestartHandoff(runtime, future, restartReservation);
            }
        });
    }

    // cleanup 将重启责任交给外部预约时，必须由该预约的完成事件重新驱动收敛。
    // whenCompleteAsync 保证即使 future 已完成，回调也不会在当前 coordinator 调用栈中执行。
    private void attachForeignRestartHandoff(
            ComponentRuntime runtime,
            CompletableFuture<ComponentState> completedFuture,
            ComponentRuntime.RestartReservation reservation) {
        CompletableFuture<ComponentState> foreignFuture =
                reservation.foreignFuture();
        if (foreignFuture == null) {
            return;
        }
        // reserveRestart 已保证 FOREIGN 与旧 future 不同；这里保留显式防护，
        // 避免异常自环在完成回调中无限续接。
        assert foreignFuture != completedFuture;
        if (foreignFuture == completedFuture) {
            return;
        }
        foreignFuture.whenCompleteAsync(
                (ignored, error) -> reconcileAfterForeignOwner(
                        runtime,
                        reservation.restartExpectedHandleId(),
                        foreignFuture),
                this::dispatchForeignHandoffTask);
    }

    private void dispatchForeignHandoffTask(Runnable task) {
        try {
            executor.execute(task);
        } catch (RejectedExecutionException rejection) {
            // 关闭竞态下宁可丢失自动 restart，也不能向公共池注入关闭后副作用。
            if (host.isClosing()) {
                return;
            }
            ForkJoinPool.commonPool().execute(() -> {
                if (!host.isClosing()) {
                    task.run();
                }
            });
        }
    }

    private void reconcileAfterForeignOwner(
            ComponentRuntime expected,
            String expectedHandleId,
            CompletableFuture<ComponentState> completedForeignFuture) {
        if (host.isClosing()) {
            return;
        }
        ComponentRuntime.RestartReservation restartReservation = null;
        synchronized (coordinator) {
            if (host.isClosing()) {
                return;
            }
            PublishedKernelState state = kernelState.read();
            ComponentRuntime current =
                    state.index.components.get(expectedHandleId);
            RuntimeView.ComponentData data =
                    state.view.components.get(expectedHandleId);
            if (current != expected || data == null) {
                return;
            }
            boolean autoConvergent =
                    (data.state() == ComponentState.WAITING
                            || data.state() == ComponentState.FAILED)
                    && data.currentActivationId() == null
                    && data.goal() == ComponentGoal.RUNNING
                    && !current.suppressAutoRestart()
                    && !current.blockedNonConvergent()
                    && !explicitRetryGateBlocks(current);
            if (autoConvergent) {
                restartReservation = current.reserveRestart(
                        null,
                        ticker.getAsLong(),
                        "component restart after foreign owner");
            }
        }
        if (restartReservation == null) {
            return;
        }
        if (restartReservation.created()) {
            transitionScheduler.driveReservation(restartReservation.reservation());
        } else {
            // 链式 FOREIGN 同样离开临界区后挂下一层完成事件；回调只捕获下一层
            // future 与组件 token，已完成的上一层不进入新回调闭包。
            attachForeignRestartHandoff(
                    expected,
                    completedForeignFuture,
                    restartReservation);
        }
    }

    private boolean explicitRetryGateBlocks(ComponentRuntime runtime) {
        // handoff 只恢复 cleanup 原本计划的自动收敛；显式 retry 仍由 retryAsync 驱动。
        return runtime.pendingStartFailure() || runtime.failedCleanup() != null;
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

    private void dispatchCancellation(
            List<CompletableFuture<ComponentState>> cancelled) {
        if (cancelled.isEmpty()) {
            return;
        }
        try {
            executor.execute(() -> transitionScheduler.completeCancelled(cancelled));
        } catch (RejectedExecutionException rejection) {
            transitionScheduler.completeCancelled(cancelled);
        }
    }

    // postcommit 失败是原始 future 的 primary completion owner；测试探针可持有
    // completion 以构造确定性窗口，但生产行为仍是锁外派发异常完成。
    private void dispatchPrimaryFailureCompletion(
            CompletableFuture<ComponentState> future,
            Runnable completion) {
        BiConsumer<CompletableFuture<ComponentState>, Runnable> gate =
                activationFailureCompletionGate;
        if (gate != null) {
            gate.accept(future, completion);
            return;
        }
        dispatchCompletion(completion);
    }

    private void dispatchCompletion(Runnable completion) {
        if (completion == null) {
            return;
        }
        try {
            executor.execute(completion);
        } catch (RejectedExecutionException error) {
            completion.run();
        }
    }

    CommittedLeaseRetirement retireCommittedRegistrations(
            Map<String, ProviderLeaseRuntime> retiredRegistrations,
            String failureScope) {
        List<CompletableFuture<Void>> drains = new ArrayList<>();
        String failure = null;
        int leaseIndex = 0;
        for (Map.Entry<String, ProviderLeaseRuntime> entry
                : retiredRegistrations.entrySet()) {
            try {
                drains.add(retireProviderLease(entry.getKey(), entry.getValue()));
                runProviderLeaseRetireFaultProbe(leaseIndex++);
            } catch (Throwable retireError) {
                failure = PostCommitFaults.append(failure, PostCommitFaults.failure(
                        failureScope,
                        "provider lease retire",
                        retireError));
            }
        }
        return new CommittedLeaseRetirement(List.copyOf(drains), failure);
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

    /** Narrow host boundary used only while user start() is outside the lock. */
    interface ActivationHost {
        ActivationContext activationContext(
                ActivationRuntime activation,
                List<ChildMountPlan> plans);

        default boolean isClosing() {
            return false;
        }
    }

    record CommittedLeaseRetirement(
            List<CompletableFuture<Void>> drains,
            String failure) {
    }
}
