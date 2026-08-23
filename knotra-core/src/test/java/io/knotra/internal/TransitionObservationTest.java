package io.knotra.internal;

import io.knotra.ActivationContext;
import io.knotra.Component;
import io.knotra.ComponentDescriptor;
import io.knotra.ComponentFactory;
import io.knotra.ComponentState;
import io.knotra.CapabilityRequirement;
import io.knotra.ContextHandle;
import io.knotra.KnotraRuntime;
import io.knotra.MountHandle;
import io.knotra.NoConfig;
import io.knotra.TransactionReceipt;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
final class TransitionObservationTest {
    private final KnotraRuntime publicRuntime = KnotraRuntime.create();
    private final DefaultKnotraRuntime runtime = (DefaultKnotraRuntime) publicRuntime;

    @AfterEach
    void tearDown() throws Exception {
        runtime.transitionPublicationProbe = null;
        runtime.transitionReservationProbe = null;
        runtime.activationDecisionProbe = null;
        publicRuntime.close();
    }

    @Test
    void waitingPublicationIsObservedThroughReservedTransition() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<CompletableFuture<ComponentState>> observed = new AtomicReference<>();
        runtime.transitionPublicationProbe = () -> {
            MountHandleImpl handle = runtime.componentHandles.values().stream()
                    .filter(candidate -> candidate.mountId().equals("observed"))
                    .findFirst()
                    .orElseThrow();
            observed.set(handle.whenSettled().toCompletableFuture());
            entered.countDown();
            try {
                assertTrue(release.await(10, TimeUnit.SECONDS));
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        };

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            CompletableFuture<TransactionReceipt<MountHandle>> committed = CompletableFuture.supplyAsync(
                    () -> publicRuntime.advanced().transact(transaction -> transaction.mount(
                            publicRuntime.root(),
                            "observed",
                            factory())),
                    executor);
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            CompletableFuture<ComponentState> pending = observed.get();
            assertNotNull(pending);
            assertFalse(pending.isDone(), "whenSettled returned before the WAITING transition was driven");

            release.countDown();
            MountHandle handle = committed.get(10, TimeUnit.SECONDS).value();
            assertEquals(ComponentState.ACTIVE, pending.get(10, TimeUnit.SECONDS));
            assertEquals(ComponentState.ACTIVE, handle.state());
        } finally {
            release.countDown();
            executor.shutdown();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }
    @Test
    void reservePublishDriveWindowsShareOneReservedFuture() throws Exception {
        io.knotra.CapabilityKey<String> key =
                io.knotra.CapabilityKey.of("reserve-publish-drive", String.class);
        MountHandle handle = publicRuntime.advanced().transact(transaction -> transaction.mount(
                publicRuntime.root(),
                "reserve-publish-drive",
                componentFactory(CapabilityRequirement.required(key))))
                .value();
        assertEquals(ComponentState.WAITING, handle.whenSettled()
                .toCompletableFuture().get(10, TimeUnit.SECONDS));

        CountDownLatch reserveEntered = new CountDownLatch(1);
        CountDownLatch releaseReserve = new CountDownLatch(1);
        CountDownLatch publishEntered = new CountDownLatch(1);
        CountDownLatch releasePublish = new CountDownLatch(1);
        AtomicReference<CompletableFuture<ComponentState>> reserved =
                new AtomicReference<>();

        runtime.transitionReservationProbe = () -> {
            reserved.set(handle.whenSettled().toCompletableFuture());
            reserveEntered.countDown();
            await(releaseReserve);
        };
        runtime.transitionPublicationProbe = () -> {
            publishEntered.countDown();
            await(releasePublish);
        };

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            CompletableFuture<?> provide = CompletableFuture.supplyAsync(() ->
                    publicRuntime.advanced().transact(transaction ->
                            transaction.provide(publicRuntime.root(), key, "value")), executor);
            assertTrue(reserveEntered.await(10, TimeUnit.SECONDS));
            CompletableFuture<ComponentState> beforePublish = reserved.get();
            assertNotNull(beforePublish);
            assertFalse(beforePublish.isDone());

            releaseReserve.countDown();
            assertTrue(publishEntered.await(10, TimeUnit.SECONDS));
            CompletableFuture<ComponentState> afterPublish =
                    handle.whenSettled().toCompletableFuture();
            assertSame(beforePublish, afterPublish);
            assertFalse(afterPublish.isDone());

            releasePublish.countDown();
            provide.get(10, TimeUnit.SECONDS);
            assertEquals(ComponentState.ACTIVE, afterPublish.get(10, TimeUnit.SECONDS));
        } finally {
            releaseReserve.countDown();
            releasePublish.countDown();
            runtime.transitionReservationProbe = null;
            runtime.transitionPublicationProbe = null;
            executor.shutdown();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void stoppingPublicationIsObservedThroughReservedTransition() throws Exception {
        MountHandle handle = publicRuntime.advanced().transact(transaction -> transaction.mount(
                publicRuntime.root(), "stopping", factory())).value();
        assertEquals(ComponentState.ACTIVE, handle.whenSettled()
                .toCompletableFuture().get(10, TimeUnit.SECONDS));

        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<CompletableFuture<ComponentState>> observed = new AtomicReference<>();
        runtime.transitionPublicationProbe = () -> {
            observed.set(handle.whenSettled().toCompletableFuture());
            entered.countDown();
            try {
                assertTrue(release.await(10, TimeUnit.SECONDS));
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        };

        ExecutorService disposeExecutor = Executors.newSingleThreadExecutor();
        CompletableFuture<ComponentState> disposed = CompletableFuture.supplyAsync(
                () -> handle.disposeAsync().toCompletableFuture().join(), disposeExecutor);
        assertTrue(entered.await(10, TimeUnit.SECONDS));
        CompletableFuture<ComponentState> pending = observed.get();
        assertNotNull(pending);
        assertFalse(pending.isDone(), "whenSettled returned STOPPING before cleanup was driven");

        release.countDown();
        disposeExecutor.shutdown();
        assertTrue(disposeExecutor.awaitTermination(10, TimeUnit.SECONDS));
        assertEquals(ComponentState.DISPOSED, disposed.get(10, TimeUnit.SECONDS));
        assertEquals(ComponentState.DISPOSED, pending.get(10, TimeUnit.SECONDS));
    }

    @Test
    void globalDynamicTopologyChangeResetsSuppressedWaitingComponent() throws Exception {
        io.knotra.CapabilityKey<String> required =
                io.knotra.CapabilityKey.of("reset-required", String.class);
        io.knotra.CapabilityKey<String> optional =
                io.knotra.CapabilityKey.of("reset-optional", String.class);
        MountHandle suppressed = publicRuntime.advanced().transact(transaction -> transaction.mount(
                publicRuntime.root(),
                "suppressed",
                componentFactory(CapabilityRequirement.required(required))))
                .value();
        assertEquals(ComponentState.WAITING, suppressed.whenSettled()
                .toCompletableFuture().get(10, TimeUnit.SECONDS));
        ComponentRuntime component = runtime.components.get(suppressed.handleId());
        component.suppressAutoRestart = true;

        AtomicReference<io.knotra.RegistrationHandle> edge =
                new AtomicReference<>();
        publicRuntime.advanced().transact(transaction -> {
            edge.set(transaction.provide(publicRuntime.root(), optional, "edge"));
            return transaction.mount(
                    publicRuntime.root(),
                    "dynamic-edge",
                    componentFactory(CapabilityRequirement.dynamicOptional(optional)));
        });
        assertFalse(component.suppressAutoRestart,
                () -> publicRuntime.advanced().snapshot().toString());

        component.suppressAutoRestart = true;
        publicRuntime.advanced().transact(transaction -> {
            transaction.revoke(edge.get());
            return null;
        });
        assertFalse(component.suppressAutoRestart,
                () -> publicRuntime.advanced().snapshot().toString());
    }

    @Test
    void dynamicRequiredOwnerEdgeChangeResetsSuppressedComponent() throws Exception {
        io.knotra.CapabilityKey<String> missing =
                io.knotra.CapabilityKey.of("owner-reset-missing", String.class);
        io.knotra.CapabilityKey<String> provided =
                io.knotra.CapabilityKey.of("owner-reset-provided", String.class);
        ContextHandle child = publicRuntime.advanced().childContext(
                publicRuntime.root(), "owner-reset");
        publicRuntime.advanced().transact(transaction -> transaction.provide(
                publicRuntime.root(), provided, "host"));

        MountHandle suppressed = publicRuntime.advanced().transact(transaction -> transaction.mount(
                publicRuntime.root(),
                "suppressed",
                componentFactory(CapabilityRequirement.required(missing))))
                .value();
        assertEquals(ComponentState.WAITING, suppressed.whenSettled()
                .toCompletableFuture().get(10, TimeUnit.SECONDS));
        ComponentRuntime component = runtime.components.get(suppressed.handleId());
        component.suppressAutoRestart = true;

        MountHandle consumer = publicRuntime.advanced().transact(transaction -> transaction.mount(
                child,
                "consumer",
                componentFactory(CapabilityRequirement.dynamicRequired(provided))))
                .value();
        assertEquals(ComponentState.ACTIVE, consumer.whenSettled()
                .toCompletableFuture().get(10, TimeUnit.SECONDS));

        MountHandle provider = publicRuntime.advanced().transact(transaction -> transaction.mount(
                child,
                "provider",
                factory(context -> context.provide(provided, "activation"))))
                .value();
        assertEquals(ComponentState.ACTIVE, provider.whenSettled()
                .toCompletableFuture().get(10, TimeUnit.SECONDS));
        assertFalse(component.suppressAutoRestart,
                () -> publicRuntime.advanced().snapshot().toString());
    }

    @Test
    void transactionContextDisposeRetriesFailedCleanupUntilSettled() throws Exception {
        ContextHandle context = publicRuntime.advanced().childContext(
                publicRuntime.root(), "retry-cleanup");
        java.util.concurrent.atomic.AtomicInteger cleanupAttempts =
                new java.util.concurrent.atomic.AtomicInteger();
        MountHandle handle = publicRuntime.advanced().transact(transaction -> transaction.mount(
                context,
                "blocked",
                factory(configuredContext -> configuredContext.lifecycle().onClose(
                        "cleanup",
                        () -> {
                            int attempt = cleanupAttempts.incrementAndGet();
                            if (attempt == 1) {
                                throw new IllegalStateException("first cleanup failure");
                            }
                        })))).value();
        assertEquals(ComponentState.ACTIVE, handle.whenSettled()
                .toCompletableFuture().get(10, TimeUnit.SECONDS));

        CompletableFuture<?> firstDispose = runtime
                .disposeContext((ContextHandleImpl) context)
                .toCompletableFuture();
        assertTrue(assertFailure(firstDispose).contains("context cleanup failed"));
        assertEquals(ComponentState.FAILED, handle.state());

        TransactionReceipt<?> receipt = publicRuntime.advanced().transact(
                transaction -> {
                    transaction.dispose(context);
                    return null;
                });
        receipt.settlement().whenSettled().toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertEquals(ComponentState.DISPOSED, handle.state());
        assertEquals(io.knotra.ContextState.DISPOSED, context.state());
        assertEquals(2, cleanupAttempts.get());
    }

    private static ComponentFactory<NoConfig> componentFactory(
            CapabilityRequirement... requirements) {
        ComponentDescriptor descriptor =
                ComponentDescriptor.named("declared-component", requirements);
        return new ComponentFactory<>() {
            @Override
            public String factoryId() {
                return "declared-factory";
            }

            @Override
            public Component<NoConfig> create() {
                return new Component<>() {
                    @Override
                    public ComponentDescriptor descriptor() {
                        return descriptor;
                    }

                    @Override
                    public void start(ActivationContext context, NoConfig config) {
                    }
                };
            }
        };
    }

    @Test
    void emergencyRollbackLeavesFailedCleanupRetryableAndCompletesFuture() throws Exception {
        java.util.concurrent.atomic.AtomicInteger starts =
                new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger cleanups =
                new java.util.concurrent.atomic.AtomicInteger();
        MountHandle handle = publicRuntime.advanced().transact(transaction -> transaction.mount(
                publicRuntime.root(),
                "emergency",
                factory(context -> {
                    starts.incrementAndGet();
                    context.lifecycle().onClose("cleanup", () -> {
                        int attempt = cleanups.incrementAndGet();
                        if (attempt < 2) {
                            throw new IllegalStateException("cleanup failure " + attempt);
                        }
                    });
                }))).value();
        assertEquals(ComponentState.ACTIVE, handle.whenSettled()
                .toCompletableFuture().get(10, TimeUnit.SECONDS));

        ComponentRuntime component = runtime.components.get(handle.handleId());
        ActivationRuntime activation = component.current;
        ComponentRuntime.Reservation reservation = component.replaceTransition(
                System.nanoTime(), "test transition");
        component.pendingStartFailure = true;
        synchronized (runtime.coordinator) {
            runtime.emergencyRollbackActivation(component, activation);
        }
        component.failTransition(
                reservation.future(),
                new IllegalStateException("commit and rollback failed"));

        assertTrue(assertFailure(reservation.future()).contains("commit and rollback failed"));
        assertEquals(ComponentState.FAILED, handle.state());

        assertEquals(ComponentState.FAILED, handle.retryAsync()
                .toCompletableFuture().get(10, TimeUnit.SECONDS));
        assertEquals(ComponentState.FAILED, handle.retryAsync()
                .toCompletableFuture().get(10, TimeUnit.SECONDS));
        assertEquals(ComponentState.ACTIVE, handle.retryAsync()
                .toCompletableFuture().get(10, TimeUnit.SECONDS));
        assertTrue(starts.get() >= 2, () -> "starts=" + starts.get());
        assertTrue(cleanups.get() >= 2, () -> "cleanups=" + cleanups.get());
    }

    private static ComponentFactory<NoConfig> factory() {
        return factory(context -> {
        });
    }

    private static ComponentFactory<NoConfig> factory(ComponentStart start) {
        return new ComponentFactory<>() {
            @Override
            public String factoryId() {
                return "test-factory";
            }

            @Override
            public Component<NoConfig> create() {
                return new Component<>() {
                    @Override
                    public ComponentDescriptor descriptor() {
                        return ComponentDescriptor.named("test-component");
                    }

                    @Override
                    public void start(ActivationContext context, NoConfig config) throws Exception {
                        start.start(context);
                    }
                };
            }
        };
    }

    private interface ComponentStart {
        void start(ActivationContext context) throws Exception;
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError(error);
        }
    }

    private static String assertFailure(CompletableFuture<?> future) throws Exception {
        try {
            future.get(10, TimeUnit.SECONDS);
            throw new AssertionError("expected cleanup failure");
        } catch (java.util.concurrent.ExecutionException error) {
            Throwable cause = error.getCause();
            StringBuilder detail = new StringBuilder();
            while (cause != null) {
                detail.append(cause.getMessage()).append("; ");
                cause = cause.getCause();
            }
            return detail.toString();
        }
    }
}
