package io.knotra.internal;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.Objects;

/**
 * Structural post-commit boundary shared by host transactions and Context disposal.
 *
 * <p>The caller owns the coordinator critical section and the final KernelStateStore publish.
 * Implementations reserve transitions before that publish, then apply live effects only after
 * the caller reports a successful commit. If preparation fails after creating reservations, the
 * implementation must clear those slots and complete their futures outside the caller's lock.
 * The port deliberately hides the scheduler and retired provider-lease registry from Context
 * orchestration.</p>
 */
interface StructuralPostCommitPort {
    PreparedTransitions prepare(
            PublishedKernelState base,
            RuntimeView.Draft draft,
            Set<String> dirty,
            ExecutableCommitPlan executable,
            KernelStateDraft index);

    /** Must be called while the coordinator lock is held and after the candidate is published. */
    void applyCommittedEffectsLocked(
            PreparedTransitions transitions,
            ExecutableCommitPlan executable,
            ExecutionIndex committedIndex);

    /** Must be called after leaving the coordinator lock following a successful publish. */
    CommittedEffects finishCommittedEffects(
            PreparedTransitions transitions,
            ExecutableCommitPlan executable);

    /** Cancel and exceptionally complete reservations created by a rejected preparation. */
    void cancel(PreparedTransitions transitions);

    /** Schedule convergence for an already-published structural state. */
    List<CompletableFuture<io.knotra.ComponentState>> schedule(Set<String> dirty);

    record PreparedTransitions(
            TransitionPlan transitionPlan,
            List<ComponentRuntime.Reservation> reservations) {

        public PreparedTransitions {
            Objects.requireNonNull(transitionPlan, "transitionPlan");
            reservations = List.copyOf(reservations);
        }

        List<CompletableFuture<io.knotra.ComponentState>> reservationFutures() {
            return reservations.stream()
                    .map(ComponentRuntime.Reservation::future)
                    .toList();
        }
    }

    record CommittedEffects(
            List<CompletableFuture<Void>> registrationDrains,
            String failure) {

        public CommittedEffects {
            registrationDrains = List.copyOf(registrationDrains);
        }
    }
}
