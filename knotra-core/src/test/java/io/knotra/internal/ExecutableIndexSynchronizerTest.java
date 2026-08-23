package io.knotra.internal;

import io.knotra.CapabilityKey;
import io.knotra.ComponentDescriptor;
import io.knotra.ComponentGoal;
import io.knotra.ComponentOrigin;
import io.knotra.ComponentState;
import io.knotra.MountFactory;
import io.knotra.MountOptions;
import io.knotra.NoConfig;
import io.knotra.PublicationState;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ExecutableIndexSynchronizerTest {
    private static final CapabilityKey<String> KEY =
            CapabilityKey.of("executable-index-key", String.class);

    private enum CommitPath {
        HOST,
        ACTIVATION,
        CONTEXT
    }

    private final Object lock = new Object();
    private final AtomicLong nanos = new AtomicLong();
    private final KernelStateStore store;
    private final PublicationSlotTerminalRef oldPublicationRef;
    private final PublicationSlotTerminalRef survivingPublicationRef;

    ExecutableIndexSynchronizerTest() {
        ContextHandleImpl root = new ContextHandleImpl(null, "ctx-root");
        store = KernelStateStore.initial(lock, root);
        seedComponent("removed-child");
        seedComponent("surviving-child");
        oldPublicationRef = seedPublication("old-publication", "old-registration");
        survivingPublicationRef =
                seedPublication("surviving-publication", "surviving-registration");
    }

    @Test
    void hostActivationAndContextPathsProduceTheSameIndexEffect() {
        Result host = applyCommit(CommitPath.HOST);
        Result activation = applyCommit(CommitPath.ACTIVATION);
        Result context = applyCommit(CommitPath.CONTEXT);

        for (Result result : new Result[] {host, activation, context}) {
            PublishedKernelState candidate = result.candidate();

            assertFalse(candidate.index.components.containsKey("removed-child"));
            assertFalse(candidate.index.componentHandles.containsKey("removed-child"));
            assertFalse(candidate.index.registrationHandles.containsKey("old-registration"));
            assertFalse(candidate.index.providerLeases.containsKey("old-registration"));
            assertFalse(candidate.index.publicationSlotRefs.containsKey("old-publication"));

            assertSame(
                    store.read().index.components.get("surviving-child"),
                    candidate.index.components.get("surviving-child"));
            assertSame(
                    store.read().index.componentHandles.get("surviving-child"),
                    candidate.index.componentHandles.get("surviving-child"));
            assertSame(
                    store.read().index.registrationHandles.get("surviving-registration"),
                    candidate.index.registrationHandles.get("surviving-registration"));
            assertSame(
                    store.read().index.providerLeases.get("surviving-registration"),
                    candidate.index.providerLeases.get("surviving-registration"));
            assertSame(
                    survivingPublicationRef,
                    candidate.index.publicationSlotRefs.get("surviving-publication"));
            assertSame(
                    result.newRegistrationHandle(),
                    candidate.index.registrationHandles.get("new-registration"));
            assertSame(
                    result.newPublicationRef(),
                    candidate.index.publicationSlotRefs.get("new-publication"));
            assertSame(
                    result.newLease(),
                    candidate.index.providerLeases.get("new-registration"));

            assertTrue(result.oldLeaseRemovedBeforePublish());
            assertSame(
                    PublicationState.UNPUBLISHED,
                    oldPublicationRef.terminalData().state());
            candidate.validateInvariants();
        }

        PublishedKernelState base = store.read();
        assertTrue(base.view.components.containsKey("removed-child"));
        assertTrue(base.index.registrationHandles.containsKey("old-registration"));
        assertTrue(base.index.providerLeases.containsKey("old-registration"));
        assertSame(
                oldPublicationRef,
                base.index.publicationSlotRefs.get("old-publication"));
        base.validateInvariants();
    }

    @Test
    void createAndTerminalizeInTheSameSlotUsesTerminalMembership() {
        PublishedKernelState base = store.read();
        KernelStateDraft indexDraft = new KernelStateDraft(base);
        ExecutableCommitPlan executable = new ExecutableCommitPlan();
        PublicationSlotTerminalRef ref = new PublicationSlotTerminalRef("same-slot");
        executable.createdPublicationSlots.put("same-slot", ref);
        executable.terminalPublicationSlots.put(
                "same-slot",
                new ExecutableCommitPlan.PublicationTerminalEffect(
                        ref,
                        new PublicationSlotTerminalRef.TerminalData(
                                PublicationState.DISPLACED,
                                base.view.generation + 1)));

        ExecutableIndexSynchronizer.synchronizePublicationSlotRefs(
                base.index, indexDraft, executable);

        assertFalse(indexDraft.publicationSlotRefs().containsKey("same-slot"));
    }

    @Test
    void rejectsADraftFromAnotherBaseGeneration() {
        PublishedKernelState first = store.read();
        PublishedKernelState second;
        synchronized (lock) {
            RuntimeView.Draft draft = new RuntimeView.Draft(first.view);
            KernelStateDraft index = new KernelStateDraft(first);
            second = index.publish(draft.publishOnce());
            store.commitLocked(first, second);
        }

        KernelStateDraft staleDraft = new KernelStateDraft(first);
        assertThrows(
                IllegalArgumentException.class,
                () -> ExecutableIndexSynchronizer.removeRemovedComponent(
                        second.index, staleDraft, "removed-child"));
    }

    private Result applyCommit(CommitPath path) {
        PublishedKernelState base = store.read();
        RuntimeView.Draft draft = new RuntimeView.Draft(base.view);
        KernelStateDraft indexDraft = new KernelStateDraft(base);
        ExecutableCommitPlan executable = new ExecutableCommitPlan();

        draft.components.remove("removed-child");
        RuntimeView.RegistrationData oldRegistration =
                draft.registrations.remove("old-registration");
        RuntimeView.PublicationSlotData oldSlot =
                draft.publicationSlots.remove("old-publication");
        draft.activePublicationSlots.remove(new RuntimeView.PublicationSlotKey(
                oldSlot.contextId(), oldSlot.capabilityName()));

        long nextGeneration = base.view.generation + 1;
        ProviderLeaseRuntime newLease =
                new ProviderLeaseRuntime("new-registration", nanos::incrementAndGet);
        RegistrationHandleImpl newRegistrationHandle =
                new RegistrationHandleImpl(null, "new-registration");
        PublicationSlotTerminalRef newPublicationRef =
                new PublicationSlotTerminalRef("new-publication");
        RuntimeView.PublicationSlotData newSlot =
                new RuntimeView.PublicationSlotData(
                        "new-publication",
                        "ctx-root",
                        KEY.name(),
                        KEY.typeName(),
                        "new-registration",
                        null,
                        0,
                        nextGeneration);
        draft.registrations.put(
                "new-registration",
                new RuntimeView.RegistrationData(
                        "new-registration",
                        KEY,
                        "ctx-root",
                        RuntimeView.OwnerData.Host.INSTANCE,
                        "next-value",
                        newLease,
                        "new-publication"));
        draft.publicationSlots.put("new-publication", newSlot);
        draft.activePublicationSlots.put(
                new RuntimeView.PublicationSlotKey("ctx-root", KEY.name()), newSlot);
        indexDraft.registrationHandles().put("new-registration", newRegistrationHandle);

        executable.removedComponents.put(
                "removed-child", new ExecutableCommitPlan.RemovedMount("removed-child"));
        executable.retiredRegistrations.put("old-registration", oldRegistration.leases());
        executable.createdPublicationSlots.put("new-publication", newPublicationRef);
        executable.terminalPublicationSlots.put(
                "old-publication",
                new ExecutableCommitPlan.PublicationTerminalEffect(
                        oldPublicationRef,
                        new PublicationSlotTerminalRef.TerminalData(
                                PublicationState.UNPUBLISHED, nextGeneration)));

        RuntimeView next = draft.publishOnce();
        for (String handleId : executable.removedComponents.keySet()) {
            boolean removedFromPathView = switch (path) {
                case HOST -> true;
                case ACTIVATION -> !draft.components.containsKey(handleId);
                case CONTEXT -> !next.components.containsKey(handleId);
            };
            if (removedFromPathView) {
                ExecutableIndexSynchronizer.removeRemovedComponent(
                        base.index, indexDraft, handleId);
            }
        }
        for (String registrationId : executable.retiredRegistrations.keySet()) {
            ExecutableIndexSynchronizer.removeRetiredRegistration(
                    base.index, indexDraft, registrationId);
        }
        ExecutableIndexSynchronizer.synchronizePublicationSlotRefs(
                base.index, indexDraft, executable);
        boolean oldLeaseRemovedBeforePublish =
                !indexDraft.providerLeases().containsKey("old-registration");

        oldPublicationRef.completeForCommit(
                executable.terminalPublicationSlots.get("old-publication").terminalData());
        PublishedKernelState candidate = indexDraft.publish(next);
        return new Result(
                candidate,
                newRegistrationHandle,
                newPublicationRef,
                newLease,
                oldLeaseRemovedBeforePublish);
    }

    private void seedComponent(String handleId) {
        PreparedComponent<NoConfig> prepared = PreparedComponent.prepare(
                MountFactory.of(
                        handleId,
                        ComponentDescriptor.named(handleId),
                        ignored -> {
                        }),
                NoConfig.INSTANCE,
                MountOptions.DEFAULT);
        synchronized (lock) {
            PublishedKernelState base = store.read();
            RuntimeView.Draft draft = new RuntimeView.Draft(base.view);
            KernelStateDraft index = new KernelStateDraft(base);
            ComponentRuntime runtime =
                    new ComponentRuntime(handleId, "ctx-root", handleId, prepared, lock);
            PlainMountHandleImpl handle = new PlainMountHandleImpl(
                    null,
                    handleId,
                    new MountHandleImpl.Identity(handleId, handleId, handleId, "ctx-root"));
            draft.components.put(handleId, componentData(handleId, prepared));
            index.components().put(handleId, runtime);
            index.componentHandles().put(handleId, handle);
            store.commitLocked(base, index.publish(draft.publishOnce()));
        }
    }

    private PublicationSlotTerminalRef seedPublication(
            String slotId,
            String registrationId) {
        PublicationSlotTerminalRef ref = new PublicationSlotTerminalRef(slotId);
        synchronized (lock) {
            PublishedKernelState base = store.read();
            RuntimeView.Draft draft = new RuntimeView.Draft(base.view);
            KernelStateDraft index = new KernelStateDraft(base);
            RuntimeView.PublicationSlotData slot =
                    new RuntimeView.PublicationSlotData(
                            slotId,
                            "ctx-root",
                            KEY.name(),
                            KEY.typeName(),
                            registrationId,
                            null,
                            0,
                            base.view.generation + 1);
            draft.registrations.put(
                    registrationId,
                    new RuntimeView.RegistrationData(
                            registrationId,
                            KEY,
                            "ctx-root",
                            RuntimeView.OwnerData.Host.INSTANCE,
                            registrationId + "-value",
                            new ProviderLeaseRuntime(registrationId, nanos::incrementAndGet),
                            slotId));
            draft.publicationSlots.put(slotId, slot);
            draft.activePublicationSlots.put(
                    new RuntimeView.PublicationSlotKey("ctx-root", KEY.name()), slot);
            index.registrationHandles().put(
                    registrationId, new RegistrationHandleImpl(null, registrationId));
            index.publicationSlotRefs().put(slotId, ref);
            store.commitLocked(base, index.publish(draft.publishOnce()));
        }
        return ref;
    }

    private static RuntimeView.ComponentData componentData(
            String handleId, PreparedComponent<?> prepared) {
        return new RuntimeView.ComponentData(
                handleId,
                "ctx-root",
                handleId,
                prepared.descriptor().componentId(),
                prepared.factoryId(),
                ComponentOrigin.host(),
                null,
                null,
                ComponentState.WAITING,
                ComponentGoal.RUNNING,
                1,
                null,
                null,
                prepared.descriptor(),
                prepared.options());
    }

    private record Result(
            PublishedKernelState candidate,
            RegistrationHandleImpl newRegistrationHandle,
            PublicationSlotTerminalRef newPublicationRef,
            ProviderLeaseRuntime newLease,
            boolean oldLeaseRemovedBeforePublish) {
    }
}
