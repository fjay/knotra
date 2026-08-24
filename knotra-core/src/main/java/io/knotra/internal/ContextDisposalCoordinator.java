package io.knotra.internal;

import io.knotra.ComponentState;
import io.knotra.ContextState;
import io.knotra.DiagnosticCode;
import io.knotra.KnotraConfig;
import io.knotra.RuntimeDiagnostic;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;

/**
 * Owns direct and transactional Context disposal orchestration.
 *
 * <p>The coordinator mutates only structural drafts while the coordinator lock is held, publishes
 * one candidate through {@link KernelStateStore}, and delegates transition scheduling plus retired
 * provider-lease effects to {@link StructuralPostCommitPort}. User callbacks and future completion
 * always run outside the lock. The request registry stores context IDs and futures only, so it
 * never retains Context handles or plugin ClassLoaders.</p>
 */
final class ContextDisposalCoordinator {
    private final Object coordinatorLock;
    private final KernelStateStore kernelState;
    private final Executor executor;
    private final LongSupplier ticker;
    private final KnotraConfig configuration;
    private final StructuralPostCommitPort postCommit;
    private final ContextDisposalRequestRegistry requests =
            new ContextDisposalRequestRegistry();

    // Package test probe; it runs immediately before the final Context namespace commit.
    volatile Runnable contextFinalCommitProbe;

    ContextDisposalCoordinator(
            Object coordinatorLock,
            KernelStateStore kernelState,
            Executor executor,
            LongSupplier ticker,
            KnotraConfig configuration,
            StructuralPostCommitPort postCommit) {
        this.coordinatorLock = Objects.requireNonNull(coordinatorLock, "coordinatorLock");
        this.kernelState = Objects.requireNonNull(kernelState, "kernelState");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.ticker = Objects.requireNonNull(ticker, "ticker");
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.postCommit = Objects.requireNonNull(postCommit, "postCommit");
    }

    List<PendingOperationSample> pending() {
        return requests.pending();
    }

    CompletionStage<Void> dispose(ContextHandleImpl handle, boolean rootClose) {
        Objects.requireNonNull(handle, "handle");
        Set<String> dirty = Set.of();
        Set<String> subtree = Set.of();
        CompletableFuture<Void> future = null;
        StructuralPostCommitPort.PreparedTransitions transitions =
                new StructuralPostCommitPort.PreparedTransitions(
                        TransitionPlan.EMPTY, List.of());
        ExecutableCommitPlan executable = new ExecutableCommitPlan();
        boolean committed = false;
        String preparationFailure = null;
        String postCommitFailure = null;
        List<CompletableFuture<Void>> registrationDrains = List.of();
        try {
            synchronized (coordinatorLock) {
                PublishedKernelState state = kernelState.read();
                RuntimeView.ContextData data =
                        state.view.contexts.get(handle.contextId());
                if (data == null || data.state() == ContextState.DISPOSED) {
                    return CompletableFuture.completedFuture(null);
                }
                if (handle.contextId().equals("ctx-root") && !rootClose) {
                    return failedFuture(
                            "root context must be disposed through runtime close");
                }
                ContextDisposalRequestRegistry.Registration registration =
                        requests.getOrCreate(handle.contextId(), ticker.getAsLong());
                if (!registration.created()) {
                    return registration.future();
                }
                future = registration.future();

                RuntimeView.Draft draft = new RuntimeView.Draft(state.view);
                KernelStateDraft indexDraft = new KernelStateDraft(state);
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

                transitions = postCommit.prepare(
                        state, draft, dirty, executable, indexDraft);
                DiagnosticSupport.refresh(draft, state.index, configuration);
                RuntimeView next = draft.publishOnce();
                synchronizeDisposalExecutable(
                        state.index, next, executable, indexDraft);
                PublishedKernelState candidate = indexDraft.publish(next);
                completeTerminalPublicationRefs(executable);
                kernelState.commitLocked(state, candidate);
                committed = true;
                try {
                    postCommit.applyCommittedEffectsLocked(
                            transitions, executable, candidate.index);
                } catch (Throwable applyError) {
                    postCommitFailure = PostCommitFaults.append(
                            null,
                            PostCommitFaults.failure(
                                    "context disposal postcommit",
                                    "committed effects",
                                    applyError));
                }
            }
        } catch (Throwable disposalError) {
            if (future == null) {
                throw disposalError;
            }
            preparationFailure = LifecycleScopeImpl.safeError(disposalError);
        } finally {
            if (committed) {
                try {
                    StructuralPostCommitPort.CommittedEffects effects =
                            postCommit.finishCommittedEffects(transitions, executable);
                    registrationDrains = effects.registrationDrains();
                    postCommitFailure = PostCommitFaults.append(
                            postCommitFailure, effects.failure());
                } catch (Throwable finishError) {
                    postCommitFailure = PostCommitFaults.append(
                            postCommitFailure,
                            "context disposal postcommit failed: "
                                    + LifecycleScopeImpl.safeError(finishError));
                }
            } else if (future != null) {
                String cancellationFailure = cancelQuietly(transitions);
                requests.remove(handle.contextId(), future);
                CompletableFuture<Void> request = future;
                String message = PostCommitFaults.append(
                        PostCommitFaults.append(
                                "context disposal commit failed",
                                preparationFailure),
                        cancellationFailure);
                dispatchCompletion(() -> request.completeExceptionally(
                        new IllegalStateException(message)));
            }
        }
        if (future != null && !committed) {
            return future;
        }

        List<CompletableFuture<?>> settlements =
                new ArrayList<>(registrationDrains);
        if (postCommitFailure != null) {
            settlements.add(failedFuture(postCommitFailure));
        }
        settlements.addAll(transitions.reservationFutures());
        CompletableFuture<Void> settlement = CompletableFuture.allOf(
                settlements.toArray(new CompletableFuture[0]));
        CompletableFuture<Void> contextFuture = future;
        String forcedFailure = postCommitFailure;
        Set<String> disposedSubtree = subtree;
        settlement.whenComplete((ignored, error) ->
                finalizeContext(disposedSubtree, contextFuture, forcedFailure));
        return future;
    }

    /**
     * Pure host-transaction preparation: it mutates only the supplied Draft and records effects.
     * The runtime remains the final publisher and invokes {@link #settleTransactionalDisposals}.
     */
    boolean prepareTransactionalDisposal(
            PublishedKernelState base,
            RuntimeView.Draft draft,
            KernelStateDraft index,
            String contextId,
            ExecutableCommitPlan executable,
            Set<String> dirty) {
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(draft, "draft");
        Objects.requireNonNull(index, "index");
        Objects.requireNonNull(contextId, "contextId");
        Objects.requireNonNull(executable, "executable");
        Objects.requireNonNull(dirty, "dirty");
        assert Thread.holdsLock(coordinatorLock);
        if (index.base() != base) {
            throw new IllegalStateException(
                    "context disposal draft is not based on caller state");
        }
        if (contextId.equals("ctx-root")) {
            throw reject(
                    DiagnosticCode.INVALID_LIFECYCLE_OPERATION,
                    contextId,
                    "root context must be disposed through runtime close");
        }
        if (!draft.contexts.containsKey(contextId)) {
            throw reject(
                    DiagnosticCode.INVALID_LIFECYCLE_OPERATION,
                    contextId,
                    "context handle does not belong to an active transaction entity");
        }
        StructureGraphMutator.MutationResult disposed =
                StructureGraphMutator.disposeContext(
                        draft,
                        contextId,
                        StructureGraphMutator.activePublicationSlotRefs(
                                base.index, executable));
        disposed.applyTo(executable);
        dirty.addAll(disposed.dirty());
        executable.cleanupRetryIntents.addAll(
                disposed.reportedRemovedMounts().keySet());
        return true;
    }

    List<CompletableFuture<Void>> settleTransactionalDisposals(
            ExecutableCommitPlan executable,
            CompletableFuture<Void> prerequisite) {
        Objects.requireNonNull(executable, "executable");
        if (executable.contextDisposals.isEmpty()) {
            return List.of();
        }
        RuntimeView committedView = kernelState.read().view;
        List<CompletableFuture<Void>> settlements = new ArrayList<>();
        for (String contextId : ContextTrees.outermostDisposals(
                committedView, executable.contextDisposals)) {
            settlements.add(settleContextDisposal(contextId, prerequisite));
        }
        return settlements;
    }

    private CompletableFuture<Void> settleContextDisposal(
            String contextId,
            CompletableFuture<Void> prerequisite) {
        CompletableFuture<Void> future;
        Set<String> subtree;
        Set<String> components;
        synchronized (coordinatorLock) {
            RuntimeView current = kernelState.read().view;
            if (!current.contexts.containsKey(contextId)) {
                return CompletableFuture.completedFuture(null);
            }
            ContextDisposalRequestRegistry.Registration registration =
                    requests.getOrCreate(contextId, ticker.getAsLong());
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

        List<CompletableFuture<io.knotra.ComponentState>> transitions;
        try {
            transitions = postCommit.schedule(components);
        } catch (Throwable scheduleError) {
            requests.remove(contextId, future);
            Throwable failure = new IllegalStateException(
                    "context disposal scheduling failed", scheduleError);
            dispatchCompletion(() -> future.completeExceptionally(failure));
            return future;
        }
        CompletableFuture<Void> subtreeSettlement = CompletableFuture.allOf(
                transitions.toArray(new CompletableFuture[0]));
        CompletableFuture<Void> settlement = prerequisite == null
                ? subtreeSettlement
                : CompletableFuture.allOf(prerequisite, subtreeSettlement);
        settlement.whenComplete((ignored, error) ->
                finalizeContext(subtree, future, null));
        return future;
    }

    private void finalizeContext(
            Set<String> subtree,
            CompletableFuture<Void> future,
            String postCommitFailure) {
        Runnable contextCompletion = null;
        synchronized (coordinatorLock) {
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
                requests.removeAll(subtree);
                if (future != null) {
                    Throwable failure = new IllegalStateException(
                            "context finalization publish failed", commitError);
                    contextCompletion = () -> future.completeExceptionally(failure);
                }
            }
            if (contextCompletion == null) {
                requests.removeAll(subtree);
                boolean finalFailed = failed;
                contextCompletion = () -> {
                    if (future == null) {
                        return;
                    }
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

    private void synchronizeDisposalExecutable(
            ExecutionIndex base,
            RuntimeView next,
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
            if (!next.components.containsKey(handleId)) {
                ExecutableIndexSynchronizer.removeRemovedComponent(
                        base, indexDraft, handleId);
            }
        }
        ExecutableIndexSynchronizer.synchronizePublicationSlotRefs(
                base, indexDraft, executable);
        for (String registrationId : executable.retiredRegistrations.keySet()) {
            ExecutableIndexSynchronizer.removeRetiredRegistration(
                    base, indexDraft, registrationId);
        }
        for (String contextId : executable.contextDisposals) {
            if (!next.contexts.containsKey(contextId)) {
                indexDraft.contextHandles().remove(contextId);
            }
        }
    }

    private static void completeTerminalPublicationRefs(
            ExecutableCommitPlan executable) {
        for (ExecutableCommitPlan.PublicationTerminalEffect effect
                : executable.terminalPublicationSlots.values()) {
            effect.ref().completeForCommit(effect.terminalData());
        }
    }

    private String cancelQuietly(
            StructuralPostCommitPort.PreparedTransitions transitions) {
        try {
            postCommit.cancel(transitions);
            return null;
        } catch (Throwable cancelError) {
            return "reservation cancellation failed: "
                    + LifecycleScopeImpl.safeError(cancelError);
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
            completion.run();
        }
    }

    private static Reject reject(
            DiagnosticCode code,
            String target,
            String message) {
        return new Reject(new RuntimeDiagnostic(code, target, message));
    }

    private static CompletableFuture<Void> failedFuture(String message) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        future.completeExceptionally(new IllegalStateException(message));
        return future;
    }
}
