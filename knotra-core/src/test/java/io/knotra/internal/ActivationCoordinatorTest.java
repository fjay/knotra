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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
