package io.knotra.internal;

import io.knotra.CapabilityKey;
import io.knotra.CapabilityRequirement;
import io.knotra.ComponentDescriptor;
import io.knotra.ComponentState;
import io.knotra.ContextHandle;
import io.knotra.KnotraConfig;
import io.knotra.KnotraRuntime;
import io.knotra.MountFactory;
import io.knotra.MountHandle;
import io.knotra.MountOptions;
import io.knotra.NoConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Direct prepublish contracts for the pure Activation candidate factory. */
final class ActivationCandidateFactoryTest {
    private static final CapabilityKey<String> HOST =
            CapabilityKey.of("candidate-factory-host", String.class);
    private static final CapabilityKey<String> OWNED =
            CapabilityKey.of("candidate-factory-owned", String.class);
    private static final CapabilityKey<String> MISSING =
            CapabilityKey.of("candidate-factory-missing", String.class);

    private final KnotraRuntime publicRuntime = KnotraRuntime.create();
    private final DefaultKnotraRuntime runtime =
            (DefaultKnotraRuntime) publicRuntime;
    private final ActivationCandidateFactory factory =
            new ActivationCandidateFactory(KnotraConfig.defaults());
    private final CountDownLatch releaseStart = new CountDownLatch(1);
    private final AtomicBoolean reservationProbeInvoked = new AtomicBoolean();

    @AfterEach
    void tearDown() {
        runtime.activationCoordinator().scheduler().transitionReservationProbe = null;
        releaseStart.countDown();
        publicRuntime.close();
    }

    @Test
    void factoryHasOnlyItsPureConfigurationDependency() {
        assertEquals(
                1,
                ActivationCandidateFactory.class.getDeclaredFields().length,
                () -> Arrays.toString(
                        ActivationCandidateFactory.class.getDeclaredFields()));
        assertTrue(Modifier.isFinal(
                ActivationCandidateFactory.class.getModifiers()));
    }

    @Test
    void successBuildsDraftAndStructuralEffectsWithoutLiveMutation() {
        BlockedShadow fixture = blockedShadow();
        ActivationCandidateFactory.FrozenActivationInputs inputs =
                frozenInputs(fixture, List.of(fixture.candidateChild()));
        LiveSnapshot before = LiveSnapshot.capture(fixture);

        CommitDecision decision = factory.validate(inputs, fixture.state());
        assertTrue(decision.successful(), decision.message());
        ActivationCandidateFactory.PreparedCandidate prepared =
                factory.prepare(inputs, fixture.state(), decision);

        RuntimeView.ComponentData owner =
                prepared.draft().components.get(fixture.activation().owner.handleId());
        assertEquals(ComponentState.ACTIVE, owner.state());
        assertEquals(ComponentState.WAITING, prepared.draft().components
                .get(fixture.candidateChild().handle().handleId()).state());
        assertNotNull(prepared.draft().registrations.get(
                fixture.stagedRegistration().registrationId()));
        assertTrue(prepared.dirty().contains(fixture.consumer().handleId()));
        assertTrue(prepared.executable().removedComponents.containsKey(
                fixture.waitingChildId()));
        assertTrue(prepared.executable().retiredRegistrations.containsKey(
                fixture.ownedRegistrationId()));

        assertPurity(fixture, before);
    }

    @Test
    void validationCoversStartFailureStaleAndConfigurationChange() {
        BlockedShadow fixture = blockedShadow();
        StartFailureEvidence startFailure = StartFailureEvidence.capture(
                new IllegalStateException("factory start failed"),
                runtime.configuration.failureDetailPolicy());
        assertEquals(
                "java.lang.IllegalStateException: factory start failed",
                factory.validate(
                        frozenInputs(fixture, List.of(), startFailure),
                        fixture.state()).message());

        ActivationCandidateFactory.FrozenActivationInputs stale =
                frozenInputs(fixture, List.of(), StartFailureEvidence.none(), true);
        CommitDecision staleDecision = factory.validate(stale, fixture.state());
        assertTrue(staleDecision.staleCandidate());
        assertEquals("activation became stale", staleDecision.message());

        ActivationCandidateFactory.FrozenActivationInputs configurationChanged =
                frozenInputs(
                        fixture.ownerFacts(99),
                        fixture.activationFacts(),
                        List.of(),
                        fixture.activation().stagedRegistrations,
                        StartFailureEvidence.none(),
                        false);
        CommitDecision configurationDecision =
                factory.validate(configurationChanged, fixture.state());
        assertTrue(configurationDecision.staleCandidate());
        assertEquals("configuration changed", configurationDecision.message());
    }

    @Test
    void validationRejectsBindingCycleAndChildConflicts() {
        BlockedShadow fixture = blockedShadow();
        RuntimeView.Draft cycleDraft = new RuntimeView.Draft(fixture.state().view);
        RuntimeView.ComponentData ownerData = cycleDraft.components.get(
                fixture.activation().owner.handleId());
        cycleDraft.components.put(ownerData.handleId(), new RuntimeView.ComponentData(
                ownerData.handleId(),
                ownerData.contextId(),
                ownerData.mountId(),
                ownerData.componentId(),
                ownerData.factoryId(),
                ownerData.options().origin(),
                ownerData.ownerActivationId(),
                ownerData.parentHandleId(),
                ownerData.state(),
                ownerData.goal(),
                ownerData.configRevision(),
                ownerData.currentActivationId(),
                ownerData.lastActivationId(),
                ComponentDescriptor.named(
                        ownerData.componentId(),
                        CapabilityRequirement.required(HOST)),
                ownerData.options()));
        PublishedKernelState cycleState = new PublishedKernelState(
                cycleDraft.publishOnce(), fixture.state().index);
        Map<String, RuntimeView.BindingData> bindings = new HashMap<>();
        bindings.put(HOST.name(), new RuntimeView.BindingData(
                fixture.stagedRegistration().registrationId(),
                true,
                CapabilityRequirement.Mode.REQUIRED,
                CapabilityRequirement.CapabilityBinding.PINNED));
        ActivationCandidateFactory.FrozenActivationInputs cycleInputs =
                frozenInputs(
                        fixture.ownerFacts(),
                        new ActivationCandidateFactory.FrozenActivationInputs.ActivationFacts(
                                fixture.activation().activationId,
                                fixture.activation().configRevision,
                                bindings,
                                fixture.activation().initialDynamicRequiredPresence,
                                fixture.activation().stale.get()),
                        List.of(),
                        fixture.activation().stagedRegistrations,
                        StartFailureEvidence.none(),
                        false,
                        cycleState);
        CommitDecision cycle = factory.validate(cycleInputs, cycleState);
        assertTrue(cycle.suppressCycle());
        assertEquals("binding cycle rejected: " + fixture.activation().owner.handleId(),
                cycle.message());

        ActivationCandidateFactory.FrozenActivationInputs latestViewConflict =
                frozenInputs(fixture, List.of(fixture.childPlan(fixture.consumer().mountId())));
        CommitDecision childConflict = factory.validate(
                latestViewConflict, fixture.state());
        assertTrue(childConflict.message().contains(
                "staged child mountId conflicts latest view"));

        ChildMountPlan first = fixture.childPlan("duplicate-child");
        ActivationCandidateFactory.FrozenActivationInputs duplicate =
                frozenInputs(fixture, List.of(first, first));
        assertTrue(factory.validate(duplicate, fixture.state()).message().contains(
                "staged child mountId conflicts in transaction"));
    }

    @Test
    void abortedAndEmergencyCandidatesStopWithoutPublishingStagedResources() {
        BlockedShadow fixture = blockedShadow();
        ActivationCandidateFactory.FrozenActivationInputs inputs =
                frozenInputs(fixture, List.of(fixture.candidateChild()));
        LiveSnapshot before = LiveSnapshot.capture(fixture);

        ActivationCandidateFactory.PreparedCandidate aborted = factory.prepareAborted(
                inputs,
                fixture.state(),
                CommitDecision.startFailed("activation rejected"));
        assertEquals(
                ComponentState.STOPPING,
                aborted.draft().components.get(
                        fixture.activation().owner.handleId()).state());
        assertEquals(
                io.knotra.ActivationState.STOPPING,
                aborted.draft().activations.get(
                        fixture.activation().activationId).state());
        assertNull(aborted.draft().components.get(
                fixture.candidateChild().handle().handleId()));
        assertNull(aborted.draft().registrations.get(
                fixture.stagedRegistration().registrationId()));
        assertEquals(
                Map.of(fixture.activation().owner.handleId(), true),
                Map.of(aborted.dirty().iterator().next(), true));
        assertTrue(aborted.scheduleTransitions());

        ActivationCandidateFactory.PreparedCandidate emergency = factory.prepareEmergency(
                inputs,
                fixture.state(),
                "emergency factory rollback",
                true);
        assertEquals(
                ComponentState.FAILED,
                emergency.draft().components.get(
                        fixture.activation().owner.handleId()).state());
        assertEquals(
                io.knotra.ActivationState.FAILED,
                emergency.draft().activations.get(
                        fixture.activation().activationId).state());
        assertFalse(emergency.scheduleTransitions());
        ActivationCommitCandidate emergencyCandidate =
                emergency.toCandidate(TransitionPlan.EMPTY);
        assertEquals("emergency factory rollback",
                emergencyCandidate.emergencyMessage());

        assertPurity(fixture, before);
    }

    @Test
    void shadowedPublicationOwnerRecordsSlotAndLeaseEffects() {
        BlockedShadow fixture = blockedShadow();
        RuntimeView.Draft draft = new RuntimeView.Draft(fixture.state().view);
        RuntimeView.RegistrationData hostRegistration = draft.registrations.values()
                .stream()
                .filter(registration -> registration.key().equals(HOST))
                .findFirst()
                .orElseThrow();
        String consumerActivationId = fixture.state().view.components
                .get(fixture.consumer().handleId()).currentActivationId();
        draft.registrations.put(
                hostRegistration.registrationId(),
                new RuntimeView.RegistrationData(
                        hostRegistration.registrationId(),
                        hostRegistration.key(),
                        hostRegistration.contextId(),
                        new RuntimeView.OwnerData.Activation(consumerActivationId),
                        hostRegistration.value(),
                        hostRegistration.leases(),
                        hostRegistration.publicationSlotId()));
        Map<String, RegistrationHandleImpl> registrationHandles = new HashMap<>(
                fixture.state().index.registrationHandles);
        registrationHandles.remove(hostRegistration.registrationId());
        PublishedKernelState state = new PublishedKernelState(
                draft.publishOnce(),
                new ExecutionIndex(
                        fixture.state().index.components,
                        fixture.state().index.componentHandles,
                        fixture.state().index.activations,
                        registrationHandles,
                        fixture.state().index.providerLeases,
                        fixture.state().index.contextHandles,
                        fixture.state().index.publicationSlotRefs));
        ActivationCandidateFactory.FrozenActivationInputs inputs =
                frozenInputs(
                        fixture.ownerFacts(),
                        fixture.activationFacts(),
                        List.of(fixture.candidateChild()),
                        fixture.activation().stagedRegistrations,
                        StartFailureEvidence.none(),
                        false,
                        state);

        ActivationCandidateFactory.PreparedCandidate prepared =
                factory.prepare(inputs, state, CommitDecision.success());
        RuntimeView.PublicationSlotData slot = fixture.state().view.publicationSlots.get(
                hostRegistration.publicationSlotId());
        assertNotNull(slot);
        assertNull(prepared.draft().publicationSlots.get(slot.slotId()));
        assertNull(prepared.draft().activePublicationSlots.get(
                new RuntimeView.PublicationSlotKey(
                        slot.contextId(), slot.capabilityName())));
        ExecutableCommitPlan.PublicationTerminalEffect terminal =
                prepared.executable().terminalPublicationSlots.get(slot.slotId());
        assertNotNull(terminal);
        assertEquals(
                io.knotra.PublicationState.DISPLACED,
                terminal.terminalData().state());
        assertSame(hostRegistration.leases(),
                prepared.executable().retiredRegistrations.get(
                        hostRegistration.registrationId()));

        assertSame(fixture.state().view.publicationSlots.get(slot.slotId()), slot);
    }

    private ActivationCandidateFactory.FrozenActivationInputs frozenInputs(
            BlockedShadow fixture,
            List<ChildMountPlan> plans) {
        return frozenInputs(
                fixture.ownerFacts(),
                fixture.activationFacts(),
                plans,
                fixture.activation().stagedRegistrations,
                StartFailureEvidence.none(),
                false,
                fixture.state());
    }

    private ActivationCandidateFactory.FrozenActivationInputs frozenInputs(
            BlockedShadow fixture,
            List<ChildMountPlan> plans,
            StartFailureEvidence evidence) {
        return frozenInputs(
                fixture.ownerFacts(),
                fixture.activationFacts(),
                plans,
                fixture.activation().stagedRegistrations,
                evidence,
                false,
                fixture.state());
    }

    private ActivationCandidateFactory.FrozenActivationInputs frozenInputs(
            BlockedShadow fixture,
            List<ChildMountPlan> plans,
            StartFailureEvidence evidence,
            boolean stale) {
        return frozenInputs(
                fixture.ownerFacts(),
                fixture.activationFacts(),
                plans,
                fixture.activation().stagedRegistrations,
                evidence,
                stale,
                fixture.state());
    }

    private ActivationCandidateFactory.FrozenActivationInputs frozenInputs(
            ActivationCandidateFactory.FrozenActivationInputs.OwnerFacts owner,
            ActivationCandidateFactory.FrozenActivationInputs.ActivationFacts activation,
            List<ChildMountPlan> plans,
            Map<String, RuntimeView.RegistrationData> staged,
            StartFailureEvidence evidence,
            boolean stale) {
        return frozenInputs(
                owner,
                activation,
                plans,
                staged,
                evidence,
                stale,
                runtime.publishedState());
    }

    private ActivationCandidateFactory.FrozenActivationInputs frozenInputs(
            ActivationCandidateFactory.FrozenActivationInputs.OwnerFacts owner,
            ActivationCandidateFactory.FrozenActivationInputs.ActivationFacts activation,
            List<ChildMountPlan> plans,
            Map<String, RuntimeView.RegistrationData> staged,
            StartFailureEvidence evidence,
            boolean stale,
            PublishedKernelState state) {
        List<ActivationCandidateFactory.FrozenActivationInputs.PendingActivation>
                pending = new ArrayList<>();
        state.index.activations.forEach((activationId, pendingActivation) -> {
            boolean pendingStale = stale
                    && activationId.equals(activation.activationId());
            pending.add(new ActivationCandidateFactory.FrozenActivationInputs
                    .PendingActivation(
                            activationId,
                            pendingStale || pendingActivation.stale.get(),
                            pendingActivation.stagedRegistrations));
        });
        Map<String, Class<?>> capabilityTypes = new HashMap<>();
        state.view.registrations.values().forEach(registration ->
                capabilityTypes.putIfAbsent(
                        registration.key().name(), registration.key().type()));
        state.view.components.values().forEach(component -> component.descriptor()
                .sortedRequirements()
                .forEach(requirement -> capabilityTypes.putIfAbsent(
                        requirement.key().name(), requirement.key().type())));
        return new ActivationCandidateFactory.FrozenActivationInputs(
                state,
                plans,
                staged,
                evidence,
                capabilityTypes,
                pending,
                owner,
                stale
                        ? new ActivationCandidateFactory.FrozenActivationInputs.ActivationFacts(
                                activation.activationId(),
                                activation.configRevision(),
                                activation.bindings(),
                                activation.initialDynamicRequiredPresence(),
                                true)
                        : activation);
    }

    private BlockedShadow blockedShadow() {
        publicRuntime.publish(HOST, "host-value");
        ContextHandle workspace = publicRuntime.advanced()
                .childContext(publicRuntime.root(), "candidate-factory-workspace");
        AtomicReference<MountHandle> waitingChild = new AtomicReference<>();
        MountHandle consumer = publicRuntime.advanced().transact(transaction ->
                transaction.mount(workspace, "consumer", MountFactory.of(
                        "consumer",
                        ComponentDescriptor.named(
                                "consumer", CapabilityRequirement.required(HOST)),
                        context -> {
                            context.provide(OWNED, "owned-value");
                            waitingChild.set(context.mountChild(
                                    "waiting-child",
                                    MountFactory.of(
                                            "waiting-child",
                                            ComponentDescriptor.named(
                                                    "waiting-child",
                                                    CapabilityRequirement.required(MISSING)),
                                            inner -> {
                                            })));
                        }))).value();
        await(consumer, ComponentState.ACTIVE);
        await(waitingChild.get(), ComponentState.WAITING);

        CountDownLatch startEntered = new CountDownLatch(1);
        MountHandle shadow = publicRuntime.advanced().transact(transaction ->
                transaction.mount(workspace, "shadow", MountFactory.of(
                        "shadow",
                        ComponentDescriptor.named("shadow"),
                        context -> {
                            context.provide(HOST, "shadow-value");
                            startEntered.countDown();
                            releaseStart.await();
                        }))).value();
        try {
            assertTrue(startEntered.await(10, TimeUnit.SECONDS));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError(error);
        }
        PublishedKernelState state = runtime.publishedState();
        state.validateInvariants();
        RuntimeView.ComponentData shadowData =
                state.view.components.get(shadow.handleId());
        ActivationRuntime activation =
                state.index.activations.get(shadowData.currentActivationId());
        assertNotNull(activation);
        runtime.activationCoordinator().scheduler().transitionReservationProbe =
                () -> reservationProbeInvoked.set(true);
        return new BlockedShadow(
                state,
                consumer,
                waitingChild.get().handleId(),
                shadow,
                activation,
                ownedRegistrationId(state));
    }

    private static String ownedRegistrationId(PublishedKernelState state) {
        return state.view.registrations.values().stream()
                .filter(registration -> registration.key().equals(OWNED))
                .map(RuntimeView.RegistrationData::registrationId)
                .findFirst()
                .orElseThrow();
    }

    private ChildMountPlan childPlan(String mountId) {
        MountFactory childFactory = MountFactory.of(
                mountId,
                ComponentDescriptor.named(mountId),
                context -> {
                });
        PreparedComponent<NoConfig> prepared = PreparedComponent.prepare(
                childFactory, NoConfig.INSTANCE, MountOptions.DEFAULT);
        ConfiguredMountHandleImpl<NoConfig> handle = new ConfiguredMountHandleImpl<>(
                runtime,
                "handle-" + mountId,
                new MountHandleImpl.Identity(
                        mountId,
                        prepared.descriptor().componentId(),
                        prepared.factoryId(),
                        runtime.root().contextId()));
        return new ChildMountPlan(handle, mountId, prepared);
    }

    private ChildMountPlan candidateChild() {
        return childPlan("candidate-child");
    }

    private void await(MountHandle handle, ComponentState expected) {
        ComponentState actual;
        try {
            actual = handle.whenSettled().toCompletableFuture()
                    .get(10, TimeUnit.SECONDS);
        } catch (Exception error) {
            throw new AssertionError(error);
        }
        assertEquals(expected, actual, handle.mountId());
    }

    private void assertPurity(BlockedShadow fixture, LiveSnapshot before) {
        assertSame(fixture.state(), runtime.publishedState());
        assertEquals(before, LiveSnapshot.capture(fixture));
        assertEquals(
                Map.copyOf(fixture.activation().stagedRegistrations),
                before.stagedRegistrations);
        assertFalse(fixture.activation().stale.get());
        assertFalse(reservationProbeInvoked.get(),
                "candidate factory must not reserve transitions");
    }

    private record LiveSnapshot(
            Object desired,
            Object slots,
            Object failure,
            Object reconcile,
            boolean activationStale,
            boolean activationClosed,
            Map<String, RuntimeView.RegistrationData> stagedRegistrations) {
        static LiveSnapshot capture(BlockedShadow fixture) {
            ComponentRuntime owner = fixture.activation().owner;
            return new LiveSnapshot(
                    owner.desiredState(),
                    owner.slots(),
                    owner.failureState(),
                    owner.reconcileState(),
                    fixture.activation().stale.get(),
                    fixture.activation().closed.get(),
                    Map.copyOf(fixture.activation().stagedRegistrations));
        }
    }

    private final class BlockedShadow {
        private final PublishedKernelState state;
        private final MountHandle consumer;
        private final String waitingChildId;
        private final MountHandle shadow;
        private final ActivationRuntime activation;
        private final String ownedRegistrationId;

        private BlockedShadow(
                PublishedKernelState state,
                MountHandle consumer,
                String waitingChildId,
                MountHandle shadow,
                ActivationRuntime activation,
                String ownedRegistrationId) {
            this.state = state;
            this.consumer = consumer;
            this.waitingChildId = waitingChildId;
            this.shadow = shadow;
            this.activation = activation;
            this.ownedRegistrationId = ownedRegistrationId;
        }

        private PublishedKernelState state() {
            return state;
        }

        private MountHandle consumer() {
            return consumer;
        }

        private ActivationRuntime activation() {
            return activation;
        }

        private String waitingChildId() {
            return waitingChildId;
        }

        private String ownedRegistrationId() {
            return ownedRegistrationId;
        }

        private RuntimeView.RegistrationData stagedRegistration() {
            return activation.stagedRegistrations.get(HOST.name());
        }

        private ActivationCandidateFactory.FrozenActivationInputs.OwnerFacts ownerFacts() {
            return ownerFacts(activation.owner.desiredState().revision());
        }

        private ChildMountPlan childPlan(String mountId) {
            return ActivationCandidateFactoryTest.this.childPlan(mountId);
        }

        private ChildMountPlan candidateChild() {
            return ActivationCandidateFactoryTest.this.candidateChild();
        }

        private ActivationCandidateFactory.FrozenActivationInputs.OwnerFacts ownerFacts(
                long desiredRevision) {
            ComponentRuntime.ComponentFailureState failure =
                    activation.owner.failureState();
            return new ActivationCandidateFactory.FrozenActivationInputs.OwnerFacts(
                    activation.owner.handleId(),
                    desiredRevision,
                    failure.pendingStartFailure(),
                    failure.lastStartError(),
                    failure.lastStartFailure(),
                    activation.owner.suppressAutoRestart());
        }

        private ActivationCandidateFactory.FrozenActivationInputs.ActivationFacts
                activationFacts() {
            return new ActivationCandidateFactory.FrozenActivationInputs.ActivationFacts(
                    activation.activationId,
                    activation.configRevision,
                    activation.bindings,
                    activation.initialDynamicRequiredPresence,
                    activation.stale.get());
        }
    }
}
