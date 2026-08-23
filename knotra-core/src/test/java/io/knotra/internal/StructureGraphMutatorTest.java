package io.knotra.internal;

import io.knotra.CapabilityKey;
import io.knotra.CapabilityRequirement;
import io.knotra.ComponentDescriptor;
import io.knotra.ComponentState;
import io.knotra.ContextHandle;
import io.knotra.ContextState;
import io.knotra.KnotraRuntime;
import io.knotra.MountFactory;
import io.knotra.MountHandle;
import io.knotra.PublicationChange;
import io.knotra.PublicationState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class StructureGraphMutatorTest {
    private static final CapabilityKey<String> HOST =
            CapabilityKey.of("mutator-host", String.class);
    private static final CapabilityKey<String> OWNED =
            CapabilityKey.of("mutator-owned", String.class);
    private static final CapabilityKey<String> PUBLISHED =
            CapabilityKey.of("mutator-published", String.class);
    private static final CapabilityKey<String> MISSING =
            CapabilityKey.of("mutator-missing", String.class);

    @Test
    void mutatorIsStatelessAndNeverReturnsAReusableGraph() {
        assertEquals(0, StructureGraphMutator.class.getDeclaredFields().length);
        assertTrue(Arrays.stream(StructureGraphMutator.class.getDeclaredConstructors())
                .allMatch(constructor -> Modifier.isPrivate(constructor.getModifiers())));
        assertTrue(Arrays.stream(StructureGraphMutator.class.getDeclaredMethods())
                .noneMatch(method -> method.getReturnType() == RuntimeGraph.class));
    }

    private final KnotraRuntime runtime = KnotraRuntime.create();
    private final DefaultKnotraRuntime internal =
            (DefaultKnotraRuntime) runtime;

    @AfterEach
    void tearDown() {
        runtime.close();
    }

    @Test
    void activationOwnershipDisposesOnlyItsLiveTree() {
        OwnedTree tree = ownedTree(false);
        RuntimeView.Draft draft = new RuntimeView.Draft(tree.state.view);
        StructureGraphMutator.MutationResult result =
                StructureGraphMutator.disposeOwnershipForActivation(
                        draft,
                        tree.parent.handleId(),
                        tree.state.view.components
                                .get(tree.parent.handleId())
                                .currentActivationId());

        RuntimeView.ComponentData child =
                draft.components.get(tree.child.handleId());
        assertNotNull(child);
        assertEquals(io.knotra.ComponentGoal.DISPOSED, child.goal());
        assertEquals(ComponentState.ACTIVE, child.state());
        assertEquals(Set.of(tree.parent.handleId(), tree.child.handleId()), result.live());
        assertTrue(result.reportedRemovedMounts().containsKey(tree.child.handleId()));
        assertTrue(result.removedComponents().isEmpty());
        assertTrue(draft.registrations.containsKey(tree.ownedRegistrationId));
        assertKernelInvariants(tree.state, draft, result);
    }

    @Test
    void waitingOwnedChildIsRemovedImmediately() {
        OwnedTree tree = ownedTree(true);
        RuntimeView.Draft draft = new RuntimeView.Draft(tree.state.view);
        StructureGraphMutator.MutationResult result =
                StructureGraphMutator.disposeOwnershipForActivation(
                        draft,
                        tree.parent.handleId(),
                        tree.state.view.components
                                .get(tree.parent.handleId())
                                .currentActivationId());

        assertFalse(draft.components.containsKey(tree.child.handleId()));
        assertFalse(draft.activations.containsKey(
                tree.state.view.components.get(tree.child.handleId()).currentActivationId()));
        assertEquals(
                Set.of(tree.child.handleId()),
                result.removedComponents().keySet());
        assertKernelInvariants(tree.state, draft, result);
    }

    @Test
    void liveTreeDetachMarksDependentClosureAndRetiresOwnedRegistration() {
        OwnedTree tree = ownedTree(false);
        RuntimeView.Draft draft = new RuntimeView.Draft(tree.state.view);
        StructureGraphMutator.MutationResult result =
                StructureGraphMutator.disposeOwnersAndDetach(
                        draft,
                        Set.of(tree.parent.handleId()),
                        StructureGraphMutator.activePublicationSlotRefs(
                                tree.state.index, new ExecutableCommitPlan()));

        assertEquals(ComponentState.STOPPING,
                draft.components.get(tree.parent.handleId()).state());
        assertEquals(ComponentState.STOPPING,
                draft.components.get(tree.child.handleId()).state());
        assertEquals(ComponentState.STOPPING,
                draft.components.get(tree.consumer.handleId()).state());
        assertFalse(draft.registrations.containsKey(tree.ownedRegistrationId));
        assertTrue(result.retiredRegistrations()
                .containsKey(tree.ownedRegistrationId));
        assertTrue(result.staleActivations().containsAll(Set.of(
                tree.state.view.components.get(tree.parent.handleId()).currentActivationId(),
                tree.state.view.components.get(tree.child.handleId()).currentActivationId(),
                tree.state.view.components.get(tree.consumer.handleId()).currentActivationId())));
        assertTrue(draft.registrations.containsKey(tree.hostRegistrationId));
        assertKernelInvariants(tree.state, draft, result);
    }

    @Test
    void hostRegistrationLossRetiresHostAndActivationOwnedRegistrations() {
        OwnedTree tree = ownedTree(false);
        RuntimeView.Draft draft = new RuntimeView.Draft(tree.state.view);
        StructureGraphMutator.MutationResult result =
                StructureGraphMutator.detachConsumersOfRegistration(
                        draft,
                        tree.hostRegistrationId,
                        StructureGraphMutator.activePublicationSlotRefs(
                                tree.state.index, new ExecutableCommitPlan()),
                        PublicationState.DISPLACED);

        assertTrue(result.retiredRegistrations()
                .containsKey(tree.hostRegistrationId));
        assertTrue(result.retiredRegistrations()
                .containsKey(tree.ownedRegistrationId));
        assertEquals(ComponentState.STOPPING,
                draft.components.get(tree.parent.handleId()).state());
        assertEquals(ComponentState.STOPPING,
                draft.components.get(tree.consumer.handleId()).state());
        assertKernelInvariants(tree.state, draft, result);
    }

    @Test
    void publicationRemovalKeepsUpdateActiveAndRecordsTerminalReasons() {
        PublicationChange<String> publication = runtime.publish(PUBLISHED, "value");
        PublishedKernelState state = internal.publishedState();
        RuntimeView.PublicationSlotData slot = state.view.activePublicationSlots.get(
                new RuntimeView.PublicationSlotKey(
                        runtime.root().contextId(), PUBLISHED.name()));
        assertNotNull(slot);

        RuntimeView.Draft updateDraft = new RuntimeView.Draft(state.view);
        StructureGraphMutator.MutationResult update =
                StructureGraphMutator.removeRegistration(
                        updateDraft,
                        slot.currentRegistrationId(),
                        state.index.publicationSlotRefs,
                        null);
        assertFalse(updateDraft.registrations.containsKey(slot.currentRegistrationId()));
        assertTrue(updateDraft.publicationSlots.containsKey(slot.slotId()));
        assertTrue(update.terminalPublicationSlots().isEmpty());
        assertTrue(update.retiredRegistrations()
                .containsKey(slot.currentRegistrationId()));
        // UPDATE is a two-party slot swap: complete the caller side before checking invariants.
        String replacementRegistrationId = "registration-mutator-update";
        RuntimeView.RegistrationData previous =
                state.view.registrations.get(slot.currentRegistrationId());
        updateDraft.registrations.put(replacementRegistrationId,
                new RuntimeView.RegistrationData(
                        replacementRegistrationId,
                        previous.key(),
                        previous.contextId(),
                        RuntimeView.OwnerData.Host.INSTANCE,
                        "next-value",
                        new ProviderLeaseRuntime(
                                replacementRegistrationId, System::nanoTime),
                        slot.slotId()));
        RuntimeView.PublicationSlotData updatedSlot = slot.withCurrent(
                replacementRegistrationId, slot.epoch() + 1, updateDraft.generation + 1);
        updateDraft.publicationSlots.put(updatedSlot.slotId(), updatedSlot);
        updateDraft.activePublicationSlots.put(
                new RuntimeView.PublicationSlotKey(
                        updatedSlot.contextId(), updatedSlot.capabilityName()),
                updatedSlot);
        assertKernelInvariants(state, updateDraft, update);

        RuntimeView.Draft unpublishDraft = new RuntimeView.Draft(state.view);
        StructureGraphMutator.MutationResult unpublish =
                StructureGraphMutator.removeRegistration(
                        unpublishDraft,
                        slot.currentRegistrationId(),
                        state.index.publicationSlotRefs,
                        PublicationState.UNPUBLISHED);
        assertFalse(unpublishDraft.publicationSlots.containsKey(slot.slotId()));
        assertFalse(unpublishDraft.activePublicationSlots.containsKey(
                new RuntimeView.PublicationSlotKey(
                        runtime.root().contextId(), PUBLISHED.name())));
        assertEquals(PublicationState.UNPUBLISHED, unpublish
                .terminalPublicationSlots().get(slot.slotId())
                .terminalData().state());
        assertKernelInvariants(state, unpublishDraft, unpublish);

        StructureGraphMutator.MutationResult repeat =
                StructureGraphMutator.removeRegistration(
                        unpublishDraft,
                        slot.currentRegistrationId(),
                        state.index.publicationSlotRefs,
                        PublicationState.UNPUBLISHED);
        assertTrue(repeat.retiredRegistrations().isEmpty());
        assertTrue(repeat.terminalPublicationSlots().isEmpty());
        assertKernelInvariants(state, unpublishDraft, unpublish);
        assertEquals(PublicationState.PUBLISHED, publication.publication().state());
    }

    @Test
    void contextSubtreeDisposesOwnershipHostRegistrationAndPublicationSlot() {
        ContextHandle workspace = runtime.advanced()
                .childContext(runtime.root(), "mutator-workspace");
        ContextHandle nested = runtime.advanced()
                .childContext(workspace, "mutator-nested");
        PublicationChange<String> publication = runtime.publish(
                workspace, PUBLISHED, "value");
        MountHandle live = mount(
                nested,
                "live",
                ComponentDescriptor.named("live"));
        MountHandle waiting = mount(
                nested,
                "waiting",
                ComponentDescriptor.named(
                        "waiting", CapabilityRequirement.required(MISSING)));
        await(ComponentState.ACTIVE, live);
        await(ComponentState.WAITING, waiting);
        PublishedKernelState state = internal.publishedState();
        RuntimeView.Draft draft = new RuntimeView.Draft(state.view);

        StructureGraphMutator.MutationResult result =
                StructureGraphMutator.disposeContext(
                        draft,
                        workspace.contextId(),
                        state.index.publicationSlotRefs);

        assertEquals(Set.of(workspace.contextId(), nested.contextId()),
                result.subtree());
        assertEquals(ContextState.DISPOSING,
                draft.contexts.get(workspace.contextId()).state());
        assertEquals(ContextState.DISPOSING,
                draft.contexts.get(nested.contextId()).state());
        assertEquals(ComponentState.STOPPING,
                draft.components.get(live.handleId()).state());
        assertFalse(draft.components.containsKey(waiting.handleId()));
        assertTrue(result.removedComponents().containsKey(waiting.handleId()));
        assertEquals(PublicationState.DISPLACED, result
                .terminalPublicationSlots().values().iterator().next()
                .terminalData().state());
        assertKernelInvariants(state, draft, result);
        assertEquals(PublicationState.PUBLISHED, publication.publication().state());
    }

    private OwnedTree ownedTree(boolean waitingChild) {
        runtime.publish(HOST, "host-value");
        PublishedKernelState before = internal.publishedState();
        String hostRegistrationId = before.view.registrations.values().stream()
                .filter(registration -> registration.key().name().equals(HOST.name()))
                .map(RuntimeView.RegistrationData::registrationId)
                .findFirst().orElseThrow();

        AtomicReference<MountHandle> child = new AtomicReference<>();
        MountHandle parent = runtime.advanced().transact(transaction ->
                transaction.mount(runtime.root(), "parent", MountFactory.of(
                        "parent",
                        ComponentDescriptor.named(
                                "parent", CapabilityRequirement.required(HOST)),
                        context -> {
                            context.provide(OWNED, "owned-value");
                            child.set(context.mountChild(
                                    waitingChild ? "waiting-child" : "live-child",
                                    childFactory(waitingChild)));
                        }))).value();
        await(ComponentState.ACTIVE, parent);
        await(waitingChild ? ComponentState.WAITING : ComponentState.ACTIVE,
                child.get());

        MountHandle consumer = runtime.advanced().transact(transaction ->
                transaction.mount(runtime.root(), "consumer", MountFactory.of(
                        "consumer",
                        ComponentDescriptor.named(
                                "consumer", CapabilityRequirement.required(OWNED)),
                        context -> {
                        }))).value();
        await(ComponentState.ACTIVE, consumer);

        PublishedKernelState state = internal.publishedState();
        String ownedRegistrationId = state.view.registrations.values().stream()
                .filter(registration -> registration.key().name().equals(OWNED.name()))
                .map(RuntimeView.RegistrationData::registrationId)
                .findFirst().orElseThrow();
        state.validateInvariants();
        return new OwnedTree(
                state,
                parent,
                child.get(),
                consumer,
                hostRegistrationId,
                ownedRegistrationId);
    }

    private static MountFactory childFactory(boolean waiting) {
        if (waiting) {
            return MountFactory.of(
                    "waiting-child",
                    ComponentDescriptor.named(
                            "waiting-child", CapabilityRequirement.required(MISSING)),
                    context -> {
                    });
        }
        return MountFactory.of(
                "live-child",
                ComponentDescriptor.named("live-child"),
                context -> {
                });
    }

    private MountHandle mount(
            ContextHandle context,
            String mountId,
            ComponentDescriptor descriptor) {
        return runtime.advanced().transact(transaction ->
                transaction.mount(context, mountId, MountFactory.of(
                        mountId, descriptor, activationContext -> {
                        }))).value();
    }

    private static void await(ComponentState expected, MountHandle handle) {
        ComponentState actual;
        try {
            actual = handle.whenSettled().toCompletableFuture()
                    .get(10, TimeUnit.SECONDS);
        } catch (Exception error) {
            throw new AssertionError(error);
        }
        assertEquals(expected, actual, handle.mountId());
    }

    private static void assertKernelInvariants(
            PublishedKernelState state,
            RuntimeView.Draft draft,
            StructureGraphMutator.MutationResult result) {
        ExecutableCommitPlan executable = new ExecutableCommitPlan();
        result.applyTo(executable);
        KernelStateDraft indexDraft = new KernelStateDraft(state);
        executable.removedComponents.keySet().forEach(handleId -> {
            indexDraft.components().remove(handleId);
            indexDraft.componentHandles().remove(handleId);
        });
        executable.retiredRegistrations.keySet().forEach(
                indexDraft.registrationHandles()::remove);
        DefaultKnotraRuntime owner = (DefaultKnotraRuntime) state.index
                .contextHandles.get("ctx-root").runtime;
        draft.registrations.forEach((registrationId, registration) -> {
            if (registration.owner() instanceof RuntimeView.OwnerData.Host
                    && !indexDraft.registrationHandles().containsKey(registrationId)) {
                indexDraft.registrationHandles().put(
                        registrationId, new RegistrationHandleImpl(owner, registrationId));
            }
        });
        executable.terminalPublicationSlots.keySet().forEach(
                indexDraft.publicationSlotRefs()::remove);
        PublishedKernelState candidate =
                indexDraft.publish(draft.publishOnce());
        candidate.validateInvariants();
    }

    private record OwnedTree(
            PublishedKernelState state,
            MountHandle parent,
            MountHandle child,
            MountHandle consumer,
            String hostRegistrationId,
            String ownedRegistrationId) {
    }
}
