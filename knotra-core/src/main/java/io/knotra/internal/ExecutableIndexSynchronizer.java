package io.knotra.internal;

import java.util.Map;
import java.util.Objects;

/**
 * Copy-on-write primitives shared by the executable-index commit paths.
 *
 * <p>Callers keep path-specific structural decisions (which Draft or published view proves an
 * ID is gone, child additions, stale transitions, contexts, and config updates). These methods
 * only mutate the caller-owned {@link KernelStateDraft} and never publish state, retire leases,
 * complete futures, or read live kernel state.</p>
 */
final class ExecutableIndexSynchronizer {
    private ExecutableIndexSynchronizer() {
    }

    static void removeRemovedComponent(
            ExecutionIndex base,
            KernelStateDraft indexDraft,
            String handleId) {
        requireSameBase(base, indexDraft);
        Objects.requireNonNull(handleId, "handleId");
        indexDraft.components().remove(handleId);
        indexDraft.componentHandles().remove(handleId);
    }

    static void removeRetiredRegistration(
            ExecutionIndex base,
            KernelStateDraft indexDraft,
            String registrationId) {
        requireSameBase(base, indexDraft);
        Objects.requireNonNull(registrationId, "registrationId");
        indexDraft.registrationHandles().remove(registrationId);
        indexDraft.providerLeases().remove(registrationId);
    }

    static void synchronizePublicationSlotRefs(
            ExecutionIndex base,
            KernelStateDraft indexDraft,
            ExecutableCommitPlan executable) {
        requireSameBase(base, indexDraft);
        Objects.requireNonNull(executable, "executable");
        if (executable.createdPublicationSlots.isEmpty()
                && executable.terminalPublicationSlots.isEmpty()) {
            return;
        }
        Map<String, PublicationSlotTerminalRef> refs = indexDraft.publicationSlotRefs();
        executable.createdPublicationSlots.forEach(refs::putIfAbsent);
        executable.terminalPublicationSlots.keySet().forEach(refs::remove);
    }

    private static void requireSameBase(
            ExecutionIndex base,
            KernelStateDraft indexDraft) {
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(indexDraft, "indexDraft");
        if (indexDraft.base().index != base) {
            throw new IllegalArgumentException(
                    "execution index draft belongs to a different base generation");
        }
    }
}
