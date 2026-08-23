package io.knotra.internal;

import io.knotra.CapabilityKey;
import io.knotra.CapabilityRequirement;
import io.knotra.ComponentFactory;
import io.knotra.Component;
import io.knotra.ComponentDescriptor;
import io.knotra.ComponentState;
import io.knotra.ConfiguredMountHandle;
import io.knotra.ContextHandle;
import io.knotra.KnotraRuntime;
import io.knotra.MountHandle;
import io.knotra.NoConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class KernelStateInvariantTest {
    private final KnotraRuntime runtime = KnotraRuntime.create();
    private final DefaultKnotraRuntime internal =
            (DefaultKnotraRuntime) runtime;

    @AfterEach
    void tearDown() throws Exception {
        runtime.close();
    }

    @Test
    void structuralTransactionPublishesContextRegistrationAndMountTogether() throws Exception {
        CountDownLatch publicationEntered = new CountDownLatch(1);
        CountDownLatch releasePublication = new CountDownLatch(1);
        internal.transitionPublicationProbe = () -> {
            publicationEntered.countDown();
            try {
                assertTrue(releasePublication.await(10, TimeUnit.SECONDS));
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        };
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicBoolean finished = new AtomicBoolean();
        try {
            CompletableFuture<MountHandle> committed = CompletableFuture.supplyAsync(() ->
                    runtime.advanced().transact(transaction -> {
                        var context = transaction.childContext(runtime.root(), "atomic");
                        transaction.provide(context, CapabilityKey.of(
                                "atomic-capability", String.class), "value");
                        return transaction.mount(context, "atomic", waitingFactory());
                    }).value(), executor);
            assertTrue(publicationEntered.await(10, TimeUnit.SECONDS));
            PublishedKernelState barrier = internal.publishedState();
            barrier.validateInvariants();
            String barrierHandle = barrier.view.components.values().stream()
                    .filter(component -> "atomic".equals(component.mountId()))
                    .map(RuntimeView.ComponentData::handleId)
                    .findFirst()
                    .orElseThrow();
            assertTrue(barrier.view.registrations.values().stream()
                    .anyMatch(registration -> registration.key().name()
                            .equals("atomic-capability")));
            assertNotNull(barrier.index.components.get(barrierHandle));
            assertFalse(internal.whenSettled(barrierHandle).toCompletableFuture().isDone());
            releasePublication.countDown();
            internal.transitionPublicationProbe = null;
            while (!finished.get()) {
                PublishedKernelState state = internal.publishedState();
                state.validateInvariants();
            String contextId = state.view.contexts.values().stream()
                    .filter(context -> "atomic".equals(context.name()))
                    .map(RuntimeView.ContextData::contextId)
                    .findFirst()
                    .orElse(null);
            String handleId = contextId == null
                    ? null
                    : state.view.components.values().stream()
                            .filter(component -> contextId.equals(component.contextId())
                                    && "atomic".equals(component.mountId()))
                            .map(RuntimeView.ComponentData::handleId)
                            .findFirst()
                            .orElse(null);
            boolean registrationPublished = state.view.registrations.values().stream()
                    .anyMatch(registration -> registration.key().name()
                            .equals("atomic-capability"));
                assertEquals(contextId != null, handleId != null,
                        () -> stateDiagnostic(state));
            assertEquals(contextId != null, registrationPublished,
                    () -> stateDiagnostic(state));
                if (handleId != null) {
                    assertNotNull(state.index.components.get(handleId));
                    assertNotNull(state.index.componentHandles.get(handleId));
                    runtime.advanced().snapshot();
                    internal.whenSettled(handleId);
                }
                if (committed.isDone()) {
                    finished.set(true);
                }
            }
            assertEquals(ComponentState.ACTIVE, committed.get(10, TimeUnit.SECONDS)
                    .whenSettled().toCompletableFuture().get(10, TimeUnit.SECONDS));
        } finally {
            releasePublication.countDown();
            internal.transitionPublicationProbe = null;
            executor.shutdown();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void startingActivationIsPublishedWithItsExactRuntimeIdentity() throws Exception {
        CapabilityKey<String> key = CapabilityKey.of("starting-identity", String.class);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        MountHandle handle = runtime.advanced().transact(transaction ->
                transaction.mount(runtime.root(), "starting", new ComponentFactory<>() {
                    @Override public String factoryId() { return "starting"; }

                    @Override public Component<NoConfig> create() {
                        return new Component<>() {
                            @Override public ComponentDescriptor descriptor() {
                                return ComponentDescriptor.named(
                                        "starting",
                                        io.knotra.CapabilityRequirement.required(key));
                            }

                            @Override public void start(
                                    io.knotra.ActivationContext context,
                                    NoConfig config) throws InterruptedException {
                                started.countDown();
                                assertTrue(release.await(10, TimeUnit.SECONDS));
                            }
                        };
                    }
                })).value();
        assertEquals(ComponentState.WAITING, handle.whenSettled()
                .toCompletableFuture().get(10, TimeUnit.SECONDS));

        CompletableFuture<?> provide = CompletableFuture.runAsync(() ->
                runtime.advanced().transact(transaction ->
                        transaction.provide(runtime.root(), key, "value")));
        assertTrue(started.await(10, TimeUnit.SECONDS));
        PublishedKernelState state = internal.publishedState();
        state.validateInvariants();
        String activationId = state.view.components.get(handle.handleId())
                .currentActivationId();
        assertNotNull(activationId);
        ActivationRuntime activation = state.index.activations.get(activationId);
        assertNotNull(activation);
        assertSame(activation, state.index.activations.get(activationId));
        assertFalse(handle.whenSettled().toCompletableFuture().isDone());

        release.countDown();
        provide.get(10, TimeUnit.SECONDS);
        assertEquals(ComponentState.ACTIVE, handle.whenSettled()
                .toCompletableFuture().get(10, TimeUnit.SECONDS));
    }

    @Test
    void whenSettledReadsCurrentGenerationAfterDisposeClearsSlot() throws Exception {
        MountHandle handle = runtime.advanced().transact(transaction -> transaction.mount(
                runtime.root(), "stale-observation", waitingFactory())).value();
        assertEquals(ComponentState.ACTIVE, handle.whenSettled()
                .toCompletableFuture().get(10, TimeUnit.SECONDS));

        CountDownLatch observationEntered = new CountDownLatch(1);
        CountDownLatch releaseObservation = new CountDownLatch(1);
        internal.whenSettledObservationProbe = () -> {
            // 先清空自身再阻塞：处置路径内部的 whenSettled 不能被同一个探针卡住。
            internal.whenSettledObservationProbe = null;
            observationEntered.countDown();
            try {
                assertTrue(releaseObservation.await(10, TimeUnit.SECONDS));
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        };

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            CompletableFuture<ComponentState> observed = CompletableFuture.supplyAsync(
                    () -> internal.whenSettled(handle.handleId())
                            .toCompletableFuture().join(), executor);
            assertTrue(observationEntered.await(10, TimeUnit.SECONDS));

            // 停顿期间完整处置：新代发布后 view 与 index 均已清除该槽位。
            assertEquals(ComponentState.DISPOSED, handle.disposeAsync()
                    .toCompletableFuture().get(10, TimeUnit.SECONDS));
            PublishedKernelState cleared = internal.publishedState();
            cleared.validateInvariants();
            assertNull(cleared.view.components.get(handle.handleId()));
            assertNull(cleared.index.components.get(handle.handleId()));

            releaseObservation.countDown();
            // 只能返回当前 DISPOSED，不得回读调用前旧代的 ACTIVE。
            assertEquals(ComponentState.DISPOSED, observed.get(10, TimeUnit.SECONDS));
        } finally {
            releaseObservation.countDown();
            internal.whenSettledObservationProbe = null;
            executor.shutdown();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void cleanupRemovalIsObservedAtomically() throws Exception {
        io.knotra.ContextHandle context = runtime.advanced()
                .childContext(runtime.root(), "cleanup-context");
        CountDownLatch cleanupEntered = new CountDownLatch(1);
        CountDownLatch releaseCleanup = new CountDownLatch(1);
        MountHandle handle = runtime.advanced().transact(transaction ->
                transaction.mount(context, "cleanup", new ComponentFactory<>() {
                    @Override public String factoryId() { return "cleanup"; }

                    @Override public Component<NoConfig> create() {
                        return new Component<>() {
                            @Override public ComponentDescriptor descriptor() {
                                return ComponentDescriptor.named("cleanup");
                            }

                            @Override public void start(
                                    io.knotra.ActivationContext context,
                                    NoConfig config) {
                                context.lifecycle().onClose("cleanup", () -> {
                                    cleanupEntered.countDown();
                                    try {
                                        assertTrue(releaseCleanup.await(10, TimeUnit.SECONDS));
                                    } catch (InterruptedException error) {
                                        Thread.currentThread().interrupt();
                                        throw new IllegalStateException(error);
                                    }
                                });
                            }
                        };
                    }
                })).value();
        assertEquals(ComponentState.ACTIVE, handle.whenSettled()
                .toCompletableFuture().get(10, TimeUnit.SECONDS));

        CompletableFuture<?> disposed = CompletableFuture.supplyAsync(() ->
                context.disposeAsync().toCompletableFuture().join());
        assertTrue(cleanupEntered.await(10, TimeUnit.SECONDS));
        PublishedKernelState stopping = internal.publishedState();
        stopping.validateInvariants();
        assertNotNull(stopping.view.contexts.get(context.contextId()));
        assertNotNull(stopping.index.contextHandles.get(context.contextId()));
        assertNotNull(stopping.view.components.get(handle.handleId()));
        assertNotNull(stopping.index.components.get(handle.handleId()));

        releaseCleanup.countDown();
        disposed.get(10, TimeUnit.SECONDS);
        PublishedKernelState removed = internal.publishedState();
        removed.validateInvariants();
        assertNull(removed.view.contexts.get(context.contextId()));
        assertNull(removed.index.contextHandles.get(context.contextId()));
        assertNull(removed.view.components.get(handle.handleId()));
        assertNull(removed.index.components.get(handle.handleId()));
        assertNull(removed.index.componentHandles.get(handle.handleId()));
    }

    @Test
    void activationShadowRemovalPublishesViewAndIndexTogether() throws Exception {
        CapabilityKey<String> host = CapabilityKey.of("kernel-host-capability", String.class);
        CapabilityKey<String> missing = CapabilityKey.of("kernel-missing-capability", String.class);
        CapabilityKey<String> owned = CapabilityKey.of("kernel-owned-capability", String.class);
        ContextHandle workspace = runtime.advanced()
                .childContext(runtime.root(), "kernel-shadow-workspace");
        runtime.advanced().transact(transaction ->
                transaction.provide(runtime.root(), host, "root"));

        MountHandle[] childHolder = new MountHandle[1];
        ComponentFactory<NoConfig> childFactory = new ComponentFactory<>() {
            @Override public String factoryId() { return "waiting-child"; }

            @Override public Component<NoConfig> create() {
                return new Component<>() {
                    @Override public ComponentDescriptor descriptor() {
                        return ComponentDescriptor.named(
                                "waiting-child", CapabilityRequirement.required(missing));
                    }

                    @Override public void start(
                            io.knotra.ActivationContext context, NoConfig config) {
                    }
                };
            }
        };
        ComponentFactory<NoConfig> consumerFactory = new ComponentFactory<>() {
            @Override public String factoryId() { return "consumer"; }

            @Override public Component<NoConfig> create() {
                return new Component<>() {
                    @Override public ComponentDescriptor descriptor() {
                        return ComponentDescriptor.named(
                                "consumer", CapabilityRequirement.required(host));
                    }

                    @Override public void start(
                            io.knotra.ActivationContext context, NoConfig config) {
                        childHolder[0] = context.mountChild(
                                "waiting-child", childFactory, NoConfig.INSTANCE);
                        context.provide(owned, "consumer-owned");
                    }
                };
            }
        };
        MountHandle consumer = runtime.advanced().transact(transaction -> transaction.mount(
                workspace, "consumer", consumerFactory)).value();
        assertEquals(ComponentState.ACTIVE, consumer.whenSettled()
                .toCompletableFuture().get(10, TimeUnit.SECONDS));
        assertEquals(ComponentState.WAITING, childHolder[0].whenSettled()
                .toCompletableFuture().get(10, TimeUnit.SECONDS));

        PublishedKernelState before = internal.publishedState();
        before.validateInvariants();
        String childId = childHolder[0].handleId();
        assertNotNull(before.index.components.get(childId));
        assertNotNull(before.index.componentHandles.get(childId));
        String consumerActivationId = before.view.components
                .get(consumer.handleId()).currentActivationId();
        assertNotNull(consumerActivationId);
        ActivationRuntime consumerActivation =
                before.index.activations.get(consumerActivationId);
        assertNotNull(consumerActivation);

        AtomicBoolean removalPublished = new AtomicBoolean();
        internal.transitionPublicationProbe = () -> {
            PublishedKernelState state = internal.publishedState();
            state.validateInvariants();
            removalPublished.set(!state.view.components.containsKey(childId));
        };
        try {
            MountHandle shadow = runtime.advanced().transact(transaction -> transaction.mount(
                    workspace,
                    "shadow-provider",
                    factoryProviding(host, "shadow"))).value();
            assertEquals(ComponentState.ACTIVE, shadow.whenSettled()
                    .toCompletableFuture().get(10, TimeUnit.SECONDS));
            assertTrue(removalPublished.get());
        } finally {
            internal.transitionPublicationProbe = null;
        }

        PublishedKernelState after = internal.publishedState();
        after.validateInvariants();
        assertNull(after.view.components.get(childId));
        assertNull(after.index.components.get(childId));
        assertNull(after.index.componentHandles.get(childId));
        assertEquals(ComponentState.DISPOSED, childHolder[0].whenSettled()
                .toCompletableFuture().get(10, TimeUnit.SECONDS));
        assertTrue(after.view.registrations.values().stream()
                .noneMatch(registration -> registration.key().name().equals(owned.name())));
        assertTrue(consumerActivation.stale.get());
    }

    @Test
    void hostRegistrationRetirementClearsHandleIndexInSameGeneration() {
        CapabilityKey<String> key =
                CapabilityKey.of("kernel-retired-registration", String.class);
        io.knotra.RegistrationHandle handle = runtime.advanced()
                .transact(transaction -> transaction.provide(runtime.root(), key, "value"))
                .value();
        PublishedKernelState registered = internal.publishedState();
        registered.validateInvariants();
        assertNotNull(registered.index.registrationHandles.get(handle.registrationId()));

        runtime.advanced().transact(transaction -> {
            transaction.revoke(handle);
            return null;
        });
        PublishedKernelState retired = internal.publishedState();
        retired.validateInvariants();
        assertNull(retired.view.registrations.get(handle.registrationId()));
        assertNull(retired.index.registrationHandles.get(handle.registrationId()));
    }

    @Test
    void desiredConfigurationTupleNeverMixesRevisions() throws Exception {
        ConfiguredMountHandle<String> handle = runtime.advanced()
                .transact(transaction -> transaction.mount(
                        runtime.root(),
                        "configured",
                        configuredFactory(),
                        "v1"))
                .value();
        assertEquals(ComponentState.ACTIVE, handle.whenSettled()
                .toCompletableFuture().get(10, TimeUnit.SECONDS));

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            AtomicBoolean stop = new AtomicBoolean();
            CompletableFuture<?> reader = CompletableFuture.runAsync(() -> {
                while (!stop.get()) {
                    PublishedKernelState state = internal.publishedState();
                    ComponentRuntime component = state.index.components.get(handle.handleId());
                    if (component == null) {
                        continue;
                    }
                    DesiredComponentState desired = component.desiredState();
                    assertTrue(desired.revision() > 0);
                    assertEquals("v" + desired.revision(), desired.config());
                }
            });
            for (int revision = 2; revision < 52; revision++) {
                String config = "v" + revision;
                runtime.advanced().transact(transaction ->
                        transaction.reconfigure(handle, config));
            }
            stop.set(true);
            reader.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdown();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void hundredRegistrationsAndMountsRemainWithinLooseTimeBudget() {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        List<MountHandle> handles = new ArrayList<>();
        for (int index = 0; index < 100; index++) {
            int i = index;
            String capabilityName = "bulk-capability-" + i;
            String value = "value-" + i;
            String mountId = "bulk-" + i;
            runtime.advanced().transact(transaction -> transaction.provide(
                    runtime.root(),
                    CapabilityKey.of(capabilityName, String.class),
                    value));
            handles.add(runtime.advanced().transact(transaction ->
                    transaction.mount(runtime.root(), mountId, waitingFactory()))
                    .value());
            internal.publishedState().validateInvariants();
        }
        assertTrue(System.nanoTime() < deadline);
        assertEquals(100, handles.size());
    }

    private ComponentFactory<NoConfig> waitingFactory() {
        return new ComponentFactory<>() {
            @Override public String factoryId() { return "waiting"; }

            @Override public Component<NoConfig> create() {
                return new Component<>() {
                    @Override public ComponentDescriptor descriptor() {
                        return ComponentDescriptor.named("waiting");
                    }

                    @Override public void start(
                            io.knotra.ActivationContext context,
                            NoConfig config) {
                    }
                };
            }
        };
    }

    private static ComponentFactory<NoConfig> factoryProviding(
            CapabilityKey<String> key, String value) {
        return new ComponentFactory<>() {
            @Override public String factoryId() { return key.name(); }

            @Override public Component<NoConfig> create() {
                return new Component<>() {
                    @Override public ComponentDescriptor descriptor() {
                        return ComponentDescriptor.named(key.name());
                    }

                    @Override public void start(
                            io.knotra.ActivationContext context, NoConfig config) {
                        context.provide(key, value);
                    }
                };
            }
        };
    }

    private ComponentFactory<String> configuredFactory() {
        return new ComponentFactory<>() {
            @Override public String factoryId() { return "configured"; }

            @Override public Component<String> create() {
                return new Component<>() {
                    @Override public ComponentDescriptor descriptor() {
                        return ComponentDescriptor.named("configured");
                    }

                    @Override public void start(
                            io.knotra.ActivationContext context,
                            String config) {
                    }
                };
            }
        };
    }


    private static String stateDiagnostic(PublishedKernelState state) {
        return "generation=" + state.view.generation
                + " contexts=" + state.view.contexts.keySet()
                + " components=" + state.view.components.keySet()
                + " registrations=" + state.view.registrations.keySet();
    }
}
