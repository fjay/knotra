package io.knotra;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable operation-scoped settlement report for one committed structural change.
 *
 * <p>Normal completion means propagation and drain converged. A mount in the affected set may still
 * be FAILED: that outcome is represented here rather than by failing the settlement future. Diagnostics
 * belong to the affected mounts, never to an unrelated global snapshot.</p>
 */
public record SettlementReport(
        long generation,
        List<MountOutcome> mountOutcomes,
        List<RuntimeDiagnostic> diagnostics) {

    public SettlementReport {
        if (generation < 0) {
            throw new IllegalArgumentException("generation must not be negative");
        }
        mountOutcomes = mountOutcomes.stream()
                .sorted(Comparator.comparing(MountOutcome::handleId))
                .toList();
        diagnostics = diagnostics.stream().sorted().toList();
    }

    /**
     * Returns whether any affected mount ended in FAILED.
     * An empty affected set has no failed mounts.
     */
    public boolean hasFailedMounts() {
        return !failedMounts().isEmpty();
    }

    /**
     * Returns true only when at least one mount was affected and every one is ACTIVE.
     * WAITING, FAILED, and DISPOSED are therefore not "all active".
     */
    public boolean allActive() {
        return !mountOutcomes.isEmpty()
                && mountOutcomes.stream().allMatch(outcome -> outcome.state() == ComponentState.ACTIVE);
    }

    public List<MountOutcome> failedMounts() {
        return mountOutcomes.stream()
                .filter(outcome -> outcome.state() == ComponentState.FAILED)
                .toList();
    }

    public Optional<MountOutcome> outcome(String handleId) {
        Objects.requireNonNull(handleId, "handleId");
        return mountOutcomes.stream()
                .filter(outcome -> outcome.handleId().equals(handleId))
                .findFirst();
    }


    public record MountOutcome(
            String handleId,
            String mountId,
            ComponentState state,
            List<RuntimeDiagnostic> diagnostics) {

        public MountOutcome {
            Objects.requireNonNull(handleId, "handleId");
            Objects.requireNonNull(mountId, "mountId");
            Objects.requireNonNull(state, "state");
            diagnostics = List.copyOf(diagnostics).stream().sorted().toList();
        }
    }
}
