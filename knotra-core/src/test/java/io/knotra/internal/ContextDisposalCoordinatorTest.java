package io.knotra.internal;

import io.knotra.ActivationState;
import io.knotra.CapabilityKey;
import io.knotra.ComponentDescriptor;
import io.knotra.ComponentGoal;
import io.knotra.ComponentOrigin;
import io.knotra.ComponentState;
import io.knotra.ContextState;
import io.knotra.KnotraConfig;
import io.knotra.MountFactory;
import io.knotra.MountOptions;
import io.knotra.NoConfig;
import io.knotra.PublicationState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.ref.WeakReference;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** Direct orchestration tests with a real kernel store and a fake structural post-commit port. */
final class ContextDisposalCoordinatorTest {
    private static final CapabilityKey<String> KEY =
            CapabilityKey.of("context-disposal-key", String.class);

    private final Harness harness = new Harness();

    @AfterEach
    void tearDown() {
        harness.close();
    }

    @Test
    void directDisposalPublishesStopGraphAndFinalizesNamespace() throws Exception {
        ContextHandleImpl parent = harness.seedContext("parent", "ctx-root");
        harness.seedContext("child", "parent");
        harness.seedComponent("parent-component", "parent", ComponentState.ACTIVE);
        harness.seedComponent("child-component", "child", ComponentState.ACTIVE);

        CompletableFuture<Void> disposed = harness.coordinator.dispose(parent, false)
                .toCompletableFuture();
        disposed.get(10, TimeUnit.SECONDS);

        RuntimeView view = harness.store.read().view;
        assertFalse(view.contexts.containsKey("parent"));
        assertFalse(view.contexts.containsKey("child"));
        assertFalse(view.components.containsKey("parent-component"));
        assertFalse(view.components.containsKey("child-component"));
        assertFalse(harness.store.read().index.contextHandles.containsKey("parent"));
        assertTrue(harness.port.prepared > 0);
        assertTrue(harness.port.finished > 0);
        assertTrue(harness.coordinator.pending().isEmpty());
        harness.store.read().validateInvariants();
    }

    @Test
    void transactionalDisposalUsesPurePreparationAndSettlesAfterHostPublish()
            throws Exception {
        ContextHandleImpl context = harness.seedContext("transactional", "ctx-root");
        harness.seedComponent("transactional-component", "transactional",
                ComponentState.ACTIVE);

        PublishedKernelState base;
        RuntimeView.Draft draft;
        KernelStateDraft index;
        ExecutableCommitPlan executable = new ExecutableCommitPlan();
        Set<String> dirty = new LinkedHashSet<>();
        synchronized (harness.lock) {
            base = harness.store.read();
            draft = new RuntimeView.Draft(base.view);
            index = new KernelStateDraft(base);
            assertTrue(harness.coordinator.prepareTransactionalDisposal(
                    base, draft, index, "transactional", executable, dirty));
            assertFalse(executable.contextDisposals.isEmpty());
            assertFalse(executable.cleanupRetryIntents.isEmpty());
            for (String removed : executable.removedComponents.keySet()) {
                index.components().remove(removed);
                index.componentHandles().remove(removed);
            }
            executable.retiredRegistrations.keySet().forEach(
                    index.registrationHandles()::remove);
            harness.store.commitLocked(base, index.publish(draft.publishOnce()));
        }

        List<CompletableFuture<Void>> settlements = harness.coordinator
                .settleTransactionalDisposals(executable, CompletableFuture.completedFuture(null));
        assertEquals(1, settlements.size());
        CompletableFuture.allOf(settlements.toArray(CompletableFuture[]::new))
                .get(10, TimeUnit.SECONDS);

        assertFalse(harness.store.read().view.contexts.containsKey("transactional"));
        assertFalse(harness.store.read().index.contextHandles.containsKey("transactional"));
        harness.store.read().validateInvariants();
    }

    @Test
    void concurrentParentAndChildDisposalsDoNotOrphanChildFuture() throws Exception {
        ContextHandleImpl parent = harness.seedContext("concurrent-parent", "ctx-root");
        ContextHandleImpl child = harness.seedContext("concurrent-child", "concurrent-parent");
        harness.seedComponent("concurrent-component", "concurrent-child",
                ComponentState.ACTIVE);

        AtomicReference<Throwable> parentFailure = new AtomicReference<>();
        CompletableFuture<Void> childDisposal = CompletableFuture.runAsync(
                () -> harness.coordinator.dispose(child, false)
                        .whenComplete((ignored, error) -> {
                            if (error != null) {
                                parentFailure.compareAndSet(null, error);
                            }
                        }),
                harness.callbackExecutor);
        CompletableFuture<Void> parentDisposal = CompletableFuture.runAsync(
                () -> harness.coordinator.dispose(parent, false),
                harness.callbackExecutor);
        parentDisposal.get(10, TimeUnit.SECONDS);
        childDisposal.get(10, TimeUnit.SECONDS);
        assertNull(parentFailure.get());
        assertTrue(harness.coordinator.pending().isEmpty());
    }

    @Test
    void failedCleanupRetryIntentIsAppliedOnlyAfterDisposalCommit() throws Exception {
        ContextHandleImpl context = harness.seedContext("failed-cleanup", "ctx-root");
        harness.port.markCleanupFailed = true;
        harness.seedFailedCleanupComponent(
                "failed-cleanup-component", "failed-cleanup");
        ComponentRuntime component = harness.component("failed-cleanup-component");
        assertSame(ComponentRuntime.RetryIntent.NONE, component.peekRetryIntent());

        CompletableFuture<Void> failed = harness.coordinator.dispose(context, false)
                .toCompletableFuture();
        CompletionFailure failure = awaitFailure(failed);
        assertTrue(failure.message().contains("context cleanup failed"), failure.message());
        assertTrue(harness.port.appliedCleanupIntents.contains("failed-cleanup-component"));
        assertSame(ComponentRuntime.RetryIntent.CLEANUP, component.peekRetryIntent());
        assertEquals(ContextState.FAILED, harness.store.read().view.contexts
                .get("failed-cleanup").state());
    }

    @Test
    void publicationDisplacementAndProviderLeaseRetirementCrossThePort() throws Exception {
        ContextHandleImpl context = harness.seedContext("publication", "ctx-root");
        harness.seedComponent("publication-owner", "publication", ComponentState.ACTIVE);
        PublicationSlotTerminalRef ref = harness.seedPublication(
                "publication", "publication-slot", "publication-registration");

        CompletableFuture<Void> disposed = harness.coordinator.dispose(context, false)
                .toCompletableFuture();
        disposed.get(10, TimeUnit.SECONDS);

        assertEquals(List.of("publication-registration"), harness.port.retiredRegistrations);
        assertSame(PublicationState.DISPLACED, ref.terminalData().state());
        assertFalse(harness.store.read().view.publicationSlots.containsKey("publication-slot"));
        assertFalse(harness.store.read().index.publicationSlotRefs
                .containsKey("publication-slot"));
        harness.store.read().validateInvariants();
    }

    @Test
    void rootContextCannotEnterDirectDisposalWithoutRuntimeClose() {
        CompletionFailure failure = awaitFailure(
                harness.coordinator.dispose(harness.rootHandle(), false)
                        .toCompletableFuture());
        assertTrue(failure.message().contains(
                "root context must be disposed through runtime close"),
                failure.message());
        assertEquals(ContextState.ACTIVE, harness.store.read().view
                .contexts.get("ctx-root").state());
    }

    @Test
    void rootCloseFinalizesChildrenButRetainsRootNamespace() throws Exception {
        ContextHandleImpl root = harness.rootHandle();
        harness.seedContext("close-child", "ctx-root");
        harness.seedComponent("close-component", "close-child", ComponentState.ACTIVE);

        harness.coordinator.dispose(root, true).toCompletableFuture().get(10, TimeUnit.SECONDS);

        RuntimeView view = harness.store.read().view;
        assertEquals(ContextState.DISPOSED, view.contexts.get("ctx-root").state());
        assertFalse(view.contexts.containsKey("close-child"));
        assertFalse(view.components.containsKey("close-component"));
        assertSame(root, harness.store.read().index.contextHandles.get("ctx-root"));
        harness.store.read().validateInvariants();
    }

    @Test
    void preparationFailureCompletesRequestAndClearsPendingRegistration() {
        ContextHandleImpl context = harness.seedContext("prepare-failure", "ctx-root");
        harness.port.failPreparation = true;

        CompletionFailure failure = awaitFailure(
                harness.coordinator.dispose(context, false).toCompletableFuture());
        assertTrue(failure.message().contains("context disposal commit failed"),
                failure.message());
        assertTrue(harness.coordinator.pending().isEmpty());
        assertEquals(1, harness.port.cancelled);
    }

    @Test
    void staleFinalizationCommitFailsFutureAndClearsDeduplication() throws Exception {
        ContextHandleImpl context = harness.seedContext("stale-finalization", "ctx-root");
        AtomicBoolean injected = new AtomicBoolean();
        harness.coordinator.contextFinalCommitProbe = () -> {
            if (!injected.getAndSet(true)) {
                harness.commitUnrelatedGeneration();
            }
        };

        CompletionFailure failure = awaitFailure(
                harness.coordinator.dispose(context, false).toCompletableFuture());
        assertTrue(failure.message().contains("context finalization publish failed"),
                failure.message());
        assertTrue(harness.coordinator.pending().isEmpty());
        harness.store.read().validateInvariants();
    }

    @Test
    void contextFutureCompletionRunsOutsideCoordinatorLock() throws Exception {
        ContextHandleImpl context = harness.seedContext("outside-lock", "ctx-root");
        CountDownLatch completed = new CountDownLatch(1);
        AtomicBoolean completedUnderLock = new AtomicBoolean();

        harness.coordinator.dispose(context, false).whenComplete((ignored, error) -> {
            completedUnderLock.set(Thread.holdsLock(harness.lock));
            completed.countDown();
        });
        assertTrue(completed.await(10, TimeUnit.SECONDS));
        assertFalse(completedUnderLock.get());
    }

    @Test
    void pendingSampleUsesMonotonicAgeAndRegistryDoesNotRetainHandles()
            throws Exception {
        PendingRequest request = startPendingDisposal();
        WeakReference<ContextHandleImpl> weakHandle =
                new WeakReference<>(new ContextHandleImpl(null, "pending-registry-gc"));
        CompletableFuture<Void> pending = request.future();
        assertFalse(pending.isDone());
        List<PendingOperationSample> samples = harness.coordinator.pending();
        assertEquals(1, samples.size());
        assertEquals("pending", samples.get(0).targetId());
        harness.nanos.incrementAndGet();
        Duration age = samples.get(0).toOperation(harness.nanos.get()).age();
        assertTrue(age.toNanos() > 0, String.valueOf(age));

        synchronized (harness.lock) {
            PublishedKernelState state = harness.store.read();
            RuntimeView.Draft draft = new RuntimeView.Draft(state.view);
            KernelStateDraft index = new KernelStateDraft(state);
            draft.components.remove("pending-component");
            index.components().remove("pending-component");
            index.componentHandles().remove("pending-component");
            harness.store.commitLocked(state, index.publish(draft.publishOnce()));
        }
        for (ComponentRuntime.Reservation reservation : harness.port.blockedReservations) {
            reservation.component().finishTransition(reservation.future());
            reservation.future().complete(ComponentState.DISPOSED);
        }
        pending.get(10, TimeUnit.SECONDS);
        assertFalse(harness.store.read().index.contextHandles.containsKey("pending"));

        for (int attempt = 0; attempt < 20 && weakHandle.get() != null; attempt++) {
            System.gc();
            TimeUnit.MILLISECONDS.sleep(10);
        }
        assertNull(weakHandle.get(), "registry must key only by context id");
        assertTrue(harness.coordinator.pending().isEmpty());
    }

    private record PendingRequest(CompletableFuture<Void> future) {
    }

    private PendingRequest startPendingDisposal() {
        ContextHandleImpl context = harness.seedContext("pending", "ctx-root");
        harness.seedStoppingComponent("pending-component", "pending");
        harness.port.blockTransitions = true;
        CompletableFuture<Void> future = harness.coordinator.dispose(context, false)
                .toCompletableFuture();
        return new PendingRequest(future);
    }

    private record CompletionFailure(String message) {
    }

    private static CompletionFailure awaitFailure(CompletableFuture<?> future) {
        try {
            future.get(10, TimeUnit.SECONDS);
            fail("expected exceptional completion");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            fail(error);
        } catch (Exception error) {
            Throwable cause = error;
            StringBuilder message = new StringBuilder(String.valueOf(cause.getMessage()));
            while ((cause = cause.getCause()) != null) {
                message.append("; ").append(cause.getMessage());
            }
            return new CompletionFailure(message.toString());
        }
        throw new AssertionError("unreachable");
    }

    private static final class Harness implements AutoCloseable {
        final Object lock = new Object();
        final KernelStateStore store;
        final FakeStructuralPort port = new FakeStructuralPort();
        final ContextDisposalCoordinator coordinator;
        final AtomicLong nanos = new AtomicLong();
        private final ExecutorService executor =
                Executors.newVirtualThreadPerTaskExecutor();
        private final ExecutorService callbackExecutor =
                Executors.newFixedThreadPool(2);

        private Harness() {
            ContextHandleImpl root = new ContextHandleImpl(null, "ctx-root");
            store = KernelStateStore.initial(lock, root);
            coordinator = new ContextDisposalCoordinator(
                    lock,
                    store,
                    executor,
                    nanos::incrementAndGet,
                    KnotraConfig.defaults(),
                    port);
        }

        ContextHandleImpl rootHandle() {
            return store.read().index.contextHandles.get("ctx-root");
        }

        ContextHandleImpl seedContext(String contextId, String parentId) {
            synchronized (lock) {
                PublishedKernelState state = store.read();
                RuntimeView.Draft draft = new RuntimeView.Draft(state.view);
                KernelStateDraft index = new KernelStateDraft(state);
                ContextHandleImpl handle = new ContextHandleImpl(null, contextId);
                draft.contexts.put(contextId, new RuntimeView.ContextData(
                        contextId,
                        parentId,
                        contextId,
                        ContextState.ACTIVE,
                        "/" + contextId));
                index.contextHandles().put(contextId, handle);
                store.commitLocked(state, index.publish(draft.publishOnce()));
                return handle;
            }
        }

        void seedComponent(
                String handleId,
                String contextId,
                ComponentState state) {
            PreparedComponent<NoConfig> prepared = PreparedComponent.prepare(
                    MountFactory.of(
                            handleId,
                            ComponentDescriptor.named(handleId),
                            ignored -> {
                            }),
                    NoConfig.INSTANCE,
                    MountOptions.DEFAULT);
            synchronized (lock) {
                PublishedKernelState current = store.read();
                RuntimeView.Draft draft = new RuntimeView.Draft(current.view);
                KernelStateDraft index = new KernelStateDraft(current);
                ComponentRuntime runtime = new ComponentRuntime(
                        handleId, contextId, handleId, prepared, lock);
                PlainMountHandleImpl handle = new PlainMountHandleImpl(
                        null,
                        handleId,
                        new MountHandleImpl.Identity(
                                handleId, handleId, handleId, contextId));
                draft.components.put(handleId, componentData(
                        handleId, contextId, prepared, state));
                index.components().put(handleId, runtime);
                index.componentHandles().put(handleId, handle);
                store.commitLocked(current, index.publish(draft.publishOnce()));
            }
        }

        void seedFailedCleanupComponent(String handleId, String contextId) {
            PreparedComponent<NoConfig> prepared = PreparedComponent.prepare(
                    MountFactory.of(
                            handleId,
                            ComponentDescriptor.named(handleId),
                            ignored -> {
                            }),
                    NoConfig.INSTANCE,
                    MountOptions.DEFAULT);
            synchronized (lock) {
                PublishedKernelState current = store.read();
                RuntimeView.Draft draft = new RuntimeView.Draft(current.view);
                KernelStateDraft index = new KernelStateDraft(current);
                ComponentRuntime runtime = new ComponentRuntime(
                        handleId, contextId, handleId, prepared, lock);
                ActivationRuntime activation = new ActivationRuntime(
                        "activation-" + handleId,
                        runtime,
                        NoConfig.INSTANCE,
                        1,
                        Map.of(),
                        List.of(),
                        nanos::incrementAndGet);
                runtime.claimCurrentLocked(activation);
                runtime.markFailedCleanupLocked(activation);
                String activationId = activation.activationId;
                draft.components.put(handleId, componentData(
                        handleId, contextId, prepared, ComponentState.FAILED)
                        .withActivation(activationId));
                draft.activations.put(activationId, new RuntimeView.ActivationData(
                        activationId,
                        handleId,
                        ActivationState.FAILED,
                        1,
                        Map.of(),
                        prepared.descriptor(),
                        activation.scope.scopeId()));
                index.components().put(handleId, runtime);
                index.componentHandles().put(handleId, new PlainMountHandleImpl(
                        null,
                        handleId,
                        new MountHandleImpl.Identity(
                                handleId, handleId, handleId, contextId)));
                index.activations().put(activationId, activation);
                store.commitLocked(current, index.publish(draft.publishOnce()));
            }
        }
        void seedStoppingComponent(String handleId, String contextId) {
            PreparedComponent<NoConfig> prepared = PreparedComponent.prepare(
                    MountFactory.of(
                            handleId,
                            ComponentDescriptor.named(handleId),
                            ignored -> {
                            }),
                    NoConfig.INSTANCE,
                    MountOptions.DEFAULT);
            synchronized (lock) {
                PublishedKernelState current = store.read();
                RuntimeView.Draft draft = new RuntimeView.Draft(current.view);
                KernelStateDraft index = new KernelStateDraft(current);
                ComponentRuntime runtime = new ComponentRuntime(
                        handleId, contextId, handleId, prepared, lock);
                PlainMountHandleImpl handle = new PlainMountHandleImpl(
                        null,
                        handleId,
                        new MountHandleImpl.Identity(
                                handleId, handleId, handleId, contextId));
                draft.components.put(handleId, componentData(
                        handleId, contextId, prepared, ComponentState.STOPPING)
                        .withActivation("activation-" + handleId));
                index.components().put(handleId, runtime);
                index.componentHandles().put(handleId, handle);
                store.commitLocked(current, index.publish(draft.publishOnce()));
            }
        }

        PublicationSlotTerminalRef seedPublication(
                String contextId,
                String slotId,
                String registrationId) {
            PublicationSlotTerminalRef ref = new PublicationSlotTerminalRef(slotId);
            synchronized (lock) {
                PublishedKernelState state = store.read();
                RuntimeView.Draft draft = new RuntimeView.Draft(state.view);
                KernelStateDraft index = new KernelStateDraft(state);
                RuntimeView.PublicationSlotData slot =
                        new RuntimeView.PublicationSlotData(
                                slotId,
                                contextId,
                                KEY.name(),
                                KEY.typeName(),
                                registrationId,
                                null,
                                0,
                                state.view.generation);
                draft.registrations.put(registrationId, new RuntimeView.RegistrationData(
                        registrationId,
                        KEY,
                        contextId,
                        RuntimeView.OwnerData.Host.INSTANCE,
                        "value",
                        new ProviderLeaseRuntime(registrationId, nanos::incrementAndGet),
                        slotId));
                draft.publicationSlots.put(slotId, slot);
                draft.activePublicationSlots.put(
                        new RuntimeView.PublicationSlotKey(contextId, KEY.name()), slot);
                index.registrationHandles().put(
                        registrationId, new RegistrationHandleImpl(null, registrationId));
                index.publicationSlotRefs().put(slotId, ref);
                store.commitLocked(state, index.publish(draft.publishOnce()));
                return ref;
            }
        }

        ComponentRuntime component(String handleId) {
            return Objects.requireNonNull(
                    store.read().index.components.get(handleId), handleId);
        }

        void commitUnrelatedGeneration() {
            synchronized (lock) {
                PublishedKernelState state = store.read();
                RuntimeView.Draft draft = new RuntimeView.Draft(state.view);
                KernelStateDraft index = new KernelStateDraft(state);
                draft.diagnostics.add(new io.knotra.RuntimeDiagnostic(
                        io.knotra.DiagnosticCode.INVALID_LIFECYCLE_OPERATION,
                        "unrelated",
                        "generation"));
                store.commitLocked(state, index.publish(draft.publishOnce()));
            }
        }

        private RuntimeView.ComponentData componentData(
                String handleId,
                String contextId,
                PreparedComponent<?> prepared,
                ComponentState state) {
            return new RuntimeView.ComponentData(
                    handleId,
                    contextId,
                    handleId,
                    prepared.descriptor().componentId(),
                    prepared.factoryId(),
                    ComponentOrigin.host(),
                    null,
                    null,
                    state,
                    state == ComponentState.DISPOSED
                            ? ComponentGoal.DISPOSED
                            : ComponentGoal.RUNNING,
                    1,
                    null,
                    null,
                    prepared.descriptor(),
                    prepared.options());
        }

        @Override
        public void close() {
            executor.shutdownNow();
            callbackExecutor.shutdownNow();
        }
    }

    private static final class FakeStructuralPort implements StructuralPostCommitPort {
        int prepared;
        int finished;
        int cancelled;
        boolean failPreparation;
        boolean blockTransitions;
        boolean markCleanupFailed;
        final List<String> appliedCleanupIntents = new ArrayList<>();
        final List<String> retiredRegistrations = new ArrayList<>();
        final List<ComponentRuntime.Reservation> blockedReservations = new ArrayList<>();

        @Override
        public PreparedTransitions prepare(
                PublishedKernelState base,
                RuntimeView.Draft draft,
                Set<String> dirty,
                ExecutableCommitPlan executable,
                KernelStateDraft index) {
            prepared++;
            if (failPreparation) {
                throw new IllegalStateException("injected preparation failure");
            }
            if (!blockTransitions) {
                return new PreparedTransitions(TransitionPlan.EMPTY, List.of());
            }
            List<ComponentRuntime.Reservation> reservations = new ArrayList<>();
            for (String handleId : dirty) {
                ComponentRuntime component = index.components().get(handleId);
                if (component != null) {
                    reservations.add(component.reserveTransition(
                            0L, "blocked context cleanup"));
                }
            }
            blockedReservations.addAll(reservations);
            return new PreparedTransitions(
                    TransitionPlan.of(reservations, List.of(), List.copyOf(dirty)),
                    reservations);
        }

        @Override
        public void applyCommittedEffectsLocked(
                PreparedTransitions transitions,
                ExecutableCommitPlan executable,
                ExecutionIndex committedIndex) {
            for (String handleId : executable.cleanupRetryIntents) {
                ComponentRuntime component = committedIndex.components.get(handleId);
                if (component != null) {
                    component.requestRetryLocked(ComponentRuntime.RetryIntent.CLEANUP);
                    if (markCleanupFailed) {
                        appliedCleanupIntents.add(handleId);
                    }
                }
            }
        }

        @Override
        public CommittedEffects finishCommittedEffects(
                PreparedTransitions transitions,
                ExecutableCommitPlan executable) {
            finished++;
            retiredRegistrations.addAll(executable.retiredRegistrations.keySet());
            String failure = markCleanupFailed
                    ? "context cleanup failed"
                    : null;
            return new CommittedEffects(List.of(), failure);
        }

        @Override
        public void cancel(PreparedTransitions transitions) {
            cancelled++;
        }

        @Override
        public List<CompletableFuture<ComponentState>> schedule(Set<String> dirty) {
            return List.of();
        }
    }
}
