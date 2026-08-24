package io.knotra.internal;

import io.knotra.ActivationContext;
import io.knotra.CapabilityKey;
import io.knotra.CapabilityRequirement;
import io.knotra.ComponentDescriptor;
import io.knotra.ComponentGoal;
import io.knotra.ComponentState;
import io.knotra.ConfiguredMountHandle;
import io.knotra.ContextInfo;
import io.knotra.ContextState;
import io.knotra.DynamicCapability;
import io.knotra.KnotraConfig;
import io.knotra.LifecycleScope;
import io.knotra.MountFactory;
import io.knotra.MountHandle;
import io.knotra.MountOptions;
import io.knotra.NoConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Direct state-machine tests for ActivationCoordinator without the runtime facade. */
final class ActivationCoordinatorTest {
    private static final CapabilityKey<String> KEY_A =
            CapabilityKey.of("coordinator-key-a", String.class);
    private static final CapabilityKey<String> KEY_B =
            CapabilityKey.of("coordinator-key-b", String.class);

    private final Harness harness = new Harness();

    @AfterEach
    void tearDown() {
        harness.close();
    }

    @Test
    void successRunsUserStartOutsideCoordinatorAndPublishesActiveState()
            throws Exception {
        CountDownLatch startEntered = new CountDownLatch(1);
        harness.addComponent("owner", activation -> {
            assertFalse(Thread.holdsLock(harness.lock));
            startEntered.countDown();
        });
        CompletableFuture<ComponentState> future = harness.drive("owner");

        assertTrue(startEntered.await(10, TimeUnit.SECONDS));
        assertEquals(ComponentState.ACTIVE, future.get(10, TimeUnit.SECONDS));
        RuntimeView.ComponentData data = harness.data("owner");
        assertEquals(ComponentState.ACTIVE, data.state());
        assertEquals(ComponentGoal.RUNNING, data.goal());
        assertTrue(harness.component("owner").current() != null);
        future.whenComplete((ignored, error) ->
                assertFalse(Thread.holdsLock(harness.lock)));
    }

    @Test
    void startErrorConvergesToFailedWithoutImplicitRetry() throws Exception {
        harness.addComponent("owner", activation -> {
            throw new IllegalStateException("user start failed");
        });
        CompletableFuture<ComponentState> future = harness.drive("owner");

        assertEquals(ComponentState.FAILED, future.get(10, TimeUnit.SECONDS));
        assertEquals(ComponentState.FAILED, harness.data("owner").state());
        assertTrue(harness.component("owner").pendingStartFailure());
        assertFalse(harness.component("owner").current() != null);
    }

    @Test
    void staleActivationCleansUpAndStartsANewGeneration() throws Exception {
        AtomicBoolean first = new AtomicBoolean(true);
        AtomicReference<ActivationRuntime> firstActivation =
                new AtomicReference<>();
        harness.addComponent("owner", activation -> {
            if (!first.getAndSet(false)) {
                return;
            }
            firstActivation.set(activation);
            activation.markStale();
        });
        CompletableFuture<ComponentState> firstFuture = harness.drive("owner");

        assertEquals(ComponentState.ACTIVE, firstFuture.get(10, TimeUnit.SECONDS));
        ActivationRuntime current = harness.component("owner").current();
        assertTrue(current != null);
        assertTrue(current != firstActivation.get());
        assertFalse(harness.component("owner").pendingStartFailure());
    }

    @Test
    void bindingCycleIsRejectedAndSuppressesAutomaticRestart() throws Exception {
        harness.seedChildContext("cycle-context");
        harness.seedHostRegistration(KEY_A, "root-value");
        harness.addComponent(
                "cycle-provider",
                "cycle-context",
                activation -> activation.stage(KEY_B, "provider-value"),
                CapabilityRequirement.required(KEY_A));
        assertEquals(ComponentState.ACTIVE, harness.drive("cycle-provider")
                .get(10, TimeUnit.SECONDS));

        harness.addComponent(
                "cyclic",
                "cycle-context",
                activation -> activation.stage(KEY_A, "cyclic-value"),
                CapabilityRequirement.required(KEY_B));
        CompletableFuture<ComponentState> future = harness.drive("cyclic");

        assertEquals(ComponentState.WAITING, future.get(10, TimeUnit.SECONDS));
        ComponentRuntime component = harness.component("cyclic");
        assertTrue(component.suppressAutoRestart(), () -> String.valueOf(
                harness.store.read().view.diagnostics));
        assertFalse(component.pendingStartFailure());
        assertEquals(ComponentState.WAITING, harness.data("cyclic").state());
    }

    @Test
    void postpublishFaultKeepsCommittedStructureAndFailsOriginalFuture()
            throws Exception {
        AtomicBoolean injected = new AtomicBoolean();
        harness.activationCoordinator.activationPostPublishEffectProbe = () -> {
            assertTrue(Thread.holdsLock(harness.lock));
            if (!injected.getAndSet(true)) {
                throw new IllegalStateException("injected effect fault");
            }
        };
        harness.addComponent("owner", activation -> {
        });
        CompletableFuture<ComponentState> future = harness.drive("owner");

        Exception failure = assertThrows(Exception.class,
                () -> future.get(10, TimeUnit.SECONDS));
        assertTrue(failure.getCause().getMessage().contains("injected effect fault"),
                String.valueOf(failure.getCause()));
        assertEquals(ComponentState.ACTIVE, harness.data("owner").state());
        assertTrue(harness.component("owner").current() != null);
    }

    @Test
    void emergencyRollbackFailsFutureWhenRecoveryCandidateCannotBeReserved()
            throws Exception {
        harness.addComponent("owner", activation -> {
        });
        harness.activationCoordinator.activationPrepublishProbe = () -> {
            throw new IllegalStateException("injected prepublish fault");
        };
        harness.activationCoordinator.scheduler().transitionReservationFaultProbe =
                index -> {
                    throw new IllegalStateException("injected recovery fault");
                };

        CompletableFuture<ComponentState> future = harness.drive("owner");
        Exception failure = assertThrows(Exception.class,
                () -> future.get(10, TimeUnit.SECONDS));
        assertTrue(failure.getCause().getMessage().contains("emergency activation rollback"),
                String.valueOf(failure.getCause()));
        assertEquals(ComponentState.FAILED, harness.data("owner").state());
    }

    @Test
    void cleanupRetryRunsOnlyAfterExplicitRetryIntent() throws Exception {
        AtomicBoolean firstCleanup = new AtomicBoolean(true);
        harness.addComponent("owner", activation -> activation.scope.onClose(
                "flaky cleanup",
                () -> {
                    assertFalse(Thread.holdsLock(harness.lock));
                    if (firstCleanup.getAndSet(false)) {
                        throw new IllegalStateException("cleanup failed");
                    }
                }));
        assertEquals(ComponentState.ACTIVE, harness.drive("owner").get(10, TimeUnit.SECONDS));
        harness.markStopping("owner", ComponentGoal.DISPOSED);
        CompletableFuture<ComponentState> failedCleanup =
                harness.enqueue("owner");

        assertEquals(ComponentState.FAILED, failedCleanup.get(10, TimeUnit.SECONDS));
        assertTrue(harness.component("owner").failedCleanup() != null);

        synchronized (harness.lock) {
            harness.component("owner").requestRetryLocked(
                    ComponentRuntime.RetryIntent.CLEANUP);
        }
        CompletableFuture<ComponentState> retried = harness.enqueue("owner");
        assertEquals(ComponentState.DISPOSED, retried.get(10, TimeUnit.SECONDS));
        assertFalse(harness.store.read().view.components.containsKey("owner"));
        assertFalse(harness.store.read().index.components.containsKey("owner"));
    }

    @Test
    void foreignRestartHandoffSurvivesHandoffExecutorRejection() throws Exception {
        AtomicInteger starts = new AtomicInteger();
        harness.addComponent("owner", ignored -> starts.incrementAndGet());
        assertEquals(ComponentState.ACTIVE, harness.drive("owner")
                .get(10, TimeUnit.SECONDS));

        AtomicReference<ComponentRuntime.Reservation> cleanupReservation =
                new AtomicReference<>();
        AtomicInteger rejectedHandoffs = new AtomicInteger();
        ActivationCoordinator rejecting = new ActivationCoordinator(
                harness.lock,
                harness.store,
                KnotraConfig.defaults(),
                task -> {
                    if (rejectedHandoffs.compareAndSet(0, 1)) {
                        throw new RejectedExecutionException("handoff executor closed");
                    }
                    harness.executor.execute(task);
                },
                harness.nanos::incrementAndGet,
                (activation, plans) -> new EmptyActivationContext(
                        activation,
                        harness.startActions.get(activation.owner.handleId())));
        AtomicReference<ComponentRuntime.Reservation> foreign =
                new AtomicReference<>();
        rejecting.cleanupFinalCommitProbe = () -> {
            if (foreign.get() == null) {
                ComponentRuntime component = harness.component("owner");
                // 模拟 primary failure 已清掉旧槽，cleanup 只剩外部 owner 可交接。
                assertTrue(component.cancelTransition(
                        cleanupReservation.get().future()));
                foreign.set(component.reserveTransition(
                        rejecting.scheduler().pendingTime(),
                        "foreign restart owner"));
            }
        };

        harness.markStopping("owner", ComponentGoal.RUNNING);
        cleanupReservation.set(harness.component("owner").reserveTransition(
                harness.nanos.incrementAndGet(), "cleanup"));
        rejecting.driveTransition(
                harness.component("owner"),
                cleanupReservation.get().future());
        awaitState("owner", ComponentState.WAITING);
        assertNotNull(foreign.get());
        assertTrue(harness.component("owner").cancelTransition(foreign.get().future()));
        rejecting.scheduler().completeCancelled(List.of(foreign.get().future()));

        awaitState("owner", ComponentState.ACTIVE);
        assertEquals(2, starts.get());
        assertEquals(1, rejectedHandoffs.get());
    }

    @Test
    void chainedForeignRestartOwnersContinueHandoffUntilIndependentRestart() throws Exception {
        chainedForeignRestartOwnersConverge(false);
    }

    @Test
    void normalSecondForeignOwnerDoesNotStartTwice() throws Exception {
        chainedForeignRestartOwnersConverge(true);
    }

    @Test
    void closingHostSuppressesForeignHandoffFallbackAfterExecutorRejection() throws Exception {
        AtomicInteger starts = new AtomicInteger();
        harness.addComponent("owner", ignored -> starts.incrementAndGet());
        assertEquals(ComponentState.ACTIVE, harness.drive("owner")
                .get(10, TimeUnit.SECONDS));

        AtomicInteger executorRejections = new AtomicInteger();
        AtomicInteger closingChecks = new AtomicInteger();
        ActivationCoordinator.ActivationHost closingHost =
                new ActivationCoordinator.ActivationHost() {
                    @Override
                    public ActivationContext activationContext(
                            ActivationRuntime activation,
                            List<ChildMountPlan> plans) {
                        return new EmptyActivationContext(
                                activation,
                                harness.startActions.get(activation.owner.handleId()));
                    }

                    @Override
                    public boolean isClosing() {
                        closingChecks.incrementAndGet();
                        return true;
                    }
                };
        ActivationCoordinator closing = new ActivationCoordinator(
                harness.lock,
                harness.store,
                KnotraConfig.defaults(),
                task -> {
                    executorRejections.incrementAndGet();
                    throw new RejectedExecutionException("runtime closing");
                },
                harness.nanos::incrementAndGet,
                closingHost);
        AtomicReference<ComponentRuntime.Reservation> cleanupReservation =
                new AtomicReference<>();
        AtomicReference<ComponentRuntime.Reservation> foreign =
                new AtomicReference<>();
        closing.cleanupFinalCommitProbe = () -> {
            if (foreign.get() == null) {
                ComponentRuntime component = harness.component("owner");
                assertTrue(component.cancelTransition(
                        cleanupReservation.get().future()));
                foreign.set(component.reserveTransition(
                        closing.scheduler().pendingTime(),
                        "foreign restart owner while closing"));
            }
        };

        harness.markStopping("owner", ComponentGoal.RUNNING);
        cleanupReservation.set(harness.component("owner").reserveTransition(
                harness.nanos.incrementAndGet(), "cleanup"));
        closing.driveTransition(
                harness.component("owner"),
                cleanupReservation.get().future());
        awaitState("owner", ComponentState.WAITING);
        assertNotNull(foreign.get());

        assertTrue(harness.component("owner").cancelTransition(foreign.get().future()));
        closing.scheduler().completeCancelled(List.of(foreign.get().future()));

        assertEquals(1, executorRejections.get());
        assertTrue(closingChecks.get() > 0, "fallback must consult the closing host");
        assertEquals(ComponentState.WAITING, harness.data("owner").state());
        assertEquals(1, starts.get(), "closing handoff must not restart the component");
        assertNull(harness.component("owner").pendingSnapshot());
    }

    private void chainedForeignRestartOwnersConverge(boolean driveSecondOwner) throws Exception {
        AtomicInteger starts = new AtomicInteger();
        harness.addComponent("owner", ignored -> starts.incrementAndGet());
        assertEquals(ComponentState.ACTIVE, harness.drive("owner")
                .get(10, TimeUnit.SECONDS));

        List<Runnable> handoffTasks = new ArrayList<>();
        ActivationCoordinator chained = new ActivationCoordinator(
                harness.lock,
                harness.store,
                KnotraConfig.defaults(),
                handoffTasks::add,
                harness.nanos::incrementAndGet,
                (activation, plans) -> new EmptyActivationContext(
                        activation,
                        harness.startActions.get(activation.owner.handleId())));
        AtomicReference<ComponentRuntime.Reservation> cleanupReservation =
                new AtomicReference<>();
        AtomicReference<ComponentRuntime.Reservation> firstForeign =
                new AtomicReference<>();
        chained.cleanupFinalCommitProbe = () -> {
            if (firstForeign.get() == null) {
                ComponentRuntime component = harness.component("owner");
                assertTrue(component.cancelTransition(
                        cleanupReservation.get().future()));
                firstForeign.set(component.reserveTransition(
                        chained.scheduler().pendingTime(),
                        "foreign restart owner 1"));
            }
        };

        harness.markStopping("owner", ComponentGoal.RUNNING);
        cleanupReservation.set(harness.component("owner").reserveTransition(
                harness.nanos.incrementAndGet(), "cleanup"));
        chained.driveTransition(
                harness.component("owner"),
                cleanupReservation.get().future());
        awaitState("owner", ComponentState.WAITING);
        assertNotNull(firstForeign.get());

        // F1 完成后，回调看到槽位已被 F2 占用，必须继续挂 F2 的完成事件。
        ComponentRuntime component = harness.component("owner");
        assertTrue(component.cancelTransition(firstForeign.get().future()));
        ComponentRuntime.Reservation secondForeign = component.reserveTransition(
                chained.scheduler().pendingTime(), "foreign restart owner 2");
        chained.scheduler().completeCancelled(List.of(firstForeign.get().future()));
        assertEquals(1, handoffTasks.size(), "F1 completion must schedule one handoff callback");
        handoffTasks.remove(0).run();

        if (driveSecondOwner) {
            assertTrue(component.ownsTransition(secondForeign.future()));
            chained.scheduler().driveReservation(secondForeign);
            assertEquals(1, handoffTasks.size(), "F2 driver must be submitted once");
            handoffTasks.remove(0).run();

            awaitState("owner", ComponentState.ACTIVE);
            assertEquals(2, starts.get(), "normal F2 owner must start exactly once");
            for (int drain = 0; drain < 4 && !handoffTasks.isEmpty(); drain++) {
                handoffTasks.remove(0).run();
            }
            assertTrue(handoffTasks.isEmpty());
            assertEquals(2, starts.get(), "F2 completion callback must not duplicate restart");
            assertNull(component.pendingSnapshot());
            return;
        }
        // F2 完成后同样必须交接到 F3，而不是只处理一层 FOREIGN。
        assertTrue(component.cancelTransition(secondForeign.future()));
        ComponentRuntime.Reservation thirdForeign = component.reserveTransition(
                chained.scheduler().pendingTime(), "foreign restart owner 3");
        chained.scheduler().completeCancelled(List.of(secondForeign.future()));
        assertEquals(1, handoffTasks.size(), "F2 completion must schedule the next handoff");
        handoffTasks.remove(0).run();

        assertTrue(component.cancelTransition(thirdForeign.future()));
        chained.scheduler().completeCancelled(List.of(thirdForeign.future()));
        assertEquals(1, handoffTasks.size(), "F3 completion must schedule reconciliation");
        handoffTasks.remove(0).run();
        assertEquals(1, handoffTasks.size(), "independent restart must be driven once");
        handoffTasks.remove(0).run();

        awaitState("owner", ComponentState.ACTIVE);
        assertEquals(2, starts.get(), "three foreign owners must produce one restart");
        assertNull(component.pendingSnapshot());
        // restart 的正常 completion 也走同一 executor；
        // 排空少量合法任务后必须静止。
        for (int drain = 0; drain < 4 && !handoffTasks.isEmpty(); drain++) {
            handoffTasks.remove(0).run();
        }
        assertTrue(handoffTasks.isEmpty(), "converged handoff must not leave callbacks");
    }

    @Test
    void cleanupWaitsForDynamicCallsAndProviderLeases() throws Exception {
        AtomicReference<ActivationRuntime> activation = new AtomicReference<>();
        AtomicReference<ProviderLeaseRuntime> lease = new AtomicReference<>();
        harness.addComponent("provider", candidate -> {
            candidate.dynamicCalls.tryAcquire();
            RuntimeView.RegistrationData registration = candidate.stage(KEY_A, "value");
            lease.set(registration.leases());
            assertTrue(registration.leases().tryAcquire());
            activation.set(candidate);
        });
        assertEquals(ComponentState.ACTIVE, harness.drive("provider")
                .get(10, TimeUnit.SECONDS));
        harness.markStopping("provider", ComponentGoal.DISPOSED);
        CompletableFuture<ComponentState> disposed = harness.enqueue("provider");

        awaitTrue(() -> activation.get().dynamicCalls.isClosed());
        assertFalse(disposed.isDone());
        activation.get().dynamicCalls.release();
        awaitTrue(() -> !harness.activationCoordinator.pendingProviderLeases().isEmpty());
        assertTrue(lease.get().isRetired());
        assertFalse(disposed.isDone());
        lease.get().release();

        assertEquals(ComponentState.DISPOSED, disposed.get(10, TimeUnit.SECONDS));
        assertTrue(harness.activationCoordinator.pendingProviderLeases().isEmpty());
    }

    @Test
    void dependentCleansBeforeProviderDuringDisposal() throws Exception {
        List<String> cleanupOrder = new ArrayList<>();
        harness.addComponent("provider", activation -> {
            activation.stage(KEY_A, "provider");
            activation.scope.onClose("provider cleanup", () -> cleanupOrder.add("provider"));
        });
        assertEquals(ComponentState.ACTIVE, harness.drive("provider")
                .get(10, TimeUnit.SECONDS));

        harness.addComponent(
                "consumer",
                activation -> activation.scope.onClose(
                        "consumer cleanup", () -> cleanupOrder.add("consumer")),
                CapabilityRequirement.required(KEY_A));
        assertEquals(ComponentState.ACTIVE, harness.drive("consumer")
                .get(10, TimeUnit.SECONDS));

        harness.markStopping("provider", ComponentGoal.DISPOSED);
        harness.markStopping("consumer", ComponentGoal.DISPOSED);
        CompletableFuture<ComponentState> providerDisposed = harness.enqueue("provider");

        assertEquals(ComponentState.DISPOSED, providerDisposed.get(10, TimeUnit.SECONDS));
        assertEquals(List.of("consumer", "provider"), cleanupOrder);
    }

    private void awaitState(String handleId, ComponentState expected)
            throws InterruptedException {
        awaitTrue(() -> {
            RuntimeView.ComponentData data = harness.data(handleId);
            return data.state() == expected;
        });
    }

    private static void awaitTrue(BooleanSupplier supplier) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!supplier.getAsBoolean()) {
            assertTrue(System.nanoTime() < deadline, "condition was not reached");
            Thread.sleep(10);
        }
    }

    private interface BooleanSupplier {
        boolean getAsBoolean();
    }

    private static final class Harness implements AutoCloseable {
        final Object lock = new Object();
        final KernelStateStore store;
        final ActivationCoordinator activationCoordinator;
        private final ExecutorService executor =
                Executors.newVirtualThreadPerTaskExecutor();
        private final AtomicLong nanos = new AtomicLong();
        private final Map<String, Consumer<ActivationRuntime>> startActions =
                new java.util.HashMap<>();

        private Harness() {
            ContextHandleImpl root = new ContextHandleImpl(null, "ctx-root");
            store = KernelStateStore.initial(lock, root);
            activationCoordinator = new ActivationCoordinator(
                    lock,
                    store,
                    KnotraConfig.defaults(),
                    executor,
                    nanos::incrementAndGet,
                    (activation, plans) -> {
                        Consumer<ActivationRuntime> action =
                                startActions.get(activation.owner.handleId());
                        return new EmptyActivationContext(activation, action);
                    });
        }

        void addComponent(
                String handleId,
                Consumer<ActivationRuntime> startAction,
                CapabilityRequirement... requirements) {
            addComponent(handleId, "ctx-root", startAction, requirements);
        }

        void addComponent(
                String handleId,
                String contextId,
                Consumer<ActivationRuntime> startAction,
                CapabilityRequirement... requirements) {
            PreparedComponent<NoConfig> prepared = PreparedComponent.prepare(
                    MountFactory.of(
                            handleId,
                            ComponentDescriptor.named(handleId, requirements),
                            EmptyActivationContext::runStart),
                    NoConfig.INSTANCE,
                    MountOptions.DEFAULT);
            synchronized (lock) {
                PublishedKernelState state = store.read();
                RuntimeView.Draft draft = new RuntimeView.Draft(state.view);
                KernelStateDraft index = new KernelStateDraft(state);
                ComponentRuntime component = new ComponentRuntime(
                        handleId,
                        contextId,
                        handleId,
                        prepared,
                        lock);
                PlainMountHandleImpl handle = new PlainMountHandleImpl(
                        null,
                        handleId,
                        new MountHandleImpl.Identity(
                                handleId,
                                handleId,
                                handleId,
                                contextId));
                draft.components.put(handleId, componentData(handleId, contextId, prepared));
                index.components().put(handleId, component);
                index.componentHandles().put(handleId, handle);
                store.commitLocked(state, index.publish(draft.publishOnce()));
                startActions.put(handleId, startAction);
            }
        }

        void seedChildContext(String contextId) {
            synchronized (lock) {
                PublishedKernelState state = store.read();
                RuntimeView.Draft draft = new RuntimeView.Draft(state.view);
                KernelStateDraft index = new KernelStateDraft(state);
                draft.contexts.put(contextId, new RuntimeView.ContextData(
                        contextId,
                        "ctx-root",
                        contextId,
                        ContextState.ACTIVE,
                        "/root/" + contextId));
                index.contextHandles().put(contextId, new ContextHandleImpl(null, contextId));
                store.commitLocked(state, index.publish(draft.publishOnce()));
            }
        }

        void seedHostRegistration(CapabilityKey<String> key, String value) {
            synchronized (lock) {
                PublishedKernelState state = store.read();
                RuntimeView.Draft draft = new RuntimeView.Draft(state.view);
                KernelStateDraft index = new KernelStateDraft(state);
                draft.registrations.put("reg-host-" + key.name(), new RuntimeView.RegistrationData(
                        "reg-host-" + key.name(),
                        key,
                        "ctx-root",
                        new RuntimeView.OwnerData.Host(),
                        value,
                        new ProviderLeaseRuntime("reg-host-" + key.name(), nanos::incrementAndGet),
                        null));
                index.registrationHandles().put(
                        "reg-host-" + key.name(),
                        new RegistrationHandleImpl(null, "reg-host-" + key.name()));
                store.commitLocked(state, index.publish(draft.publishOnce()));
            }
        }

        CompletableFuture<ComponentState> drive(String handleId) {
            ComponentRuntime component = component(handleId);
            ComponentRuntime.Reservation reservation = component.reserveTransition(
                    nanos.incrementAndGet(), "direct coordinator drive");
            activationCoordinator.driveTransition(component, reservation.future());
            return reservation.future();
        }

        CompletableFuture<ComponentState> enqueue(String handleId) {
            return activationCoordinator.scheduler().enqueue(component(handleId));
        }

        void markStopping(String handleId, ComponentGoal goal) {
            synchronized (lock) {
                PublishedKernelState state = store.read();
                RuntimeView.Draft draft = new RuntimeView.Draft(state.view);
                KernelStateDraft index = new KernelStateDraft(state);
                RuntimeView.ComponentData data = draft.components.get(handleId);
                Objects.requireNonNull(data, handleId);
                draft.components.put(
                        handleId,
                        data.withState(ComponentState.STOPPING).withGoal(goal));
                store.commitLocked(state, index.publish(draft.publishOnce()));
            }
        }

        RuntimeView.ComponentData data(String handleId) {
            RuntimeView.ComponentData data = store.read().view.components.get(handleId);
            return Objects.requireNonNull(data, handleId);
        }

        ComponentRuntime component(String handleId) {
            ComponentRuntime component = store.read().index.components.get(handleId);
            return Objects.requireNonNull(component, handleId);
        }

        private RuntimeView.ComponentData componentData(
                String handleId,
                String contextId,
                PreparedComponent<?> prepared) {
            return new RuntimeView.ComponentData(
                    handleId,
                    contextId,
                    handleId,
                    prepared.descriptor().componentId(),
                    prepared.factoryId(),
                    prepared.options().origin(),
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

        @Override
        public void close() {
            executor.shutdownNow();
        }
    }

    private static final class EmptyActivationContext implements ActivationContext {
        private final ActivationRuntime activation;
        private final Consumer<ActivationRuntime> startAction;

        private EmptyActivationContext(
                ActivationRuntime activation,
                Consumer<ActivationRuntime> startAction) {
            this.activation = activation;
            this.startAction = startAction;
        }

        private static void runStart(ActivationContext context) {
            ((EmptyActivationContext) context).runStart();
        }

        private void runStart() {
            if (startAction != null) {
                startAction.accept(activation);
            }
        }

        @Override
        public <T> T require(CapabilityKey<T> key) {
            throw new IllegalArgumentException("unsupported in coordinator harness");
        }

        @Override
        public <T> Optional<T> find(CapabilityKey<T> key) {
            return Optional.empty();
        }

        @Override
        public <T> DynamicCapability<T> subscribe(CapabilityKey<T> key) {
            throw new IllegalArgumentException("unsupported in coordinator harness");
        }

        @Override
        public <T> void provide(CapabilityKey<T> key, T value) {
            throw new IllegalArgumentException("unsupported in coordinator harness");
        }

        @Override
        public <C> ConfiguredMountHandle<C> mountChild(
                String mountId,
                io.knotra.ComponentFactory<C> factory,
                C config) {
            throw new IllegalArgumentException("unsupported in coordinator harness");
        }

        @Override
        public <C> ConfiguredMountHandle<C> mountChild(
                String mountId,
                io.knotra.ComponentFactory<C> factory,
                C config,
                MountOptions options) {
            throw new IllegalArgumentException("unsupported in coordinator harness");
        }

        @Override
        public MountHandle mountChild(
                String mountId,
                io.knotra.ComponentFactory<NoConfig> factory) {
            throw new IllegalArgumentException("unsupported in coordinator harness");
        }

        @Override
        public MountHandle mountChild(
                String mountId,
                io.knotra.ComponentFactory<NoConfig> factory,
                MountOptions options) {
            throw new IllegalArgumentException("unsupported in coordinator harness");
        }

        @Override
        public LifecycleScope lifecycle() {
            throw new IllegalArgumentException("unsupported in coordinator harness");
        }

        @Override
        public ContextInfo info() {
            throw new IllegalArgumentException("unsupported in coordinator harness");
        }
    }
}
