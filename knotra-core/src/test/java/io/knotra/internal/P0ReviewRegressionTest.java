package io.knotra.internal;

import io.knotra.CapabilityKey;
import io.knotra.CapabilityRequirement;
import io.knotra.ComponentDescriptor;
import io.knotra.ComponentState;
import io.knotra.ContextHandle;
import io.knotra.KnotraRuntime;
import io.knotra.MountFactory;
import io.knotra.MountHandle;
import io.knotra.RegistrationHandle;
import io.knotra.TransactionReceipt;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class P0ReviewRegressionTest {
    private final KnotraRuntime publicRuntime = KnotraRuntime.create();
    private final DefaultKnotraRuntime runtime =
            (DefaultKnotraRuntime) publicRuntime;

    @AfterEach
    void tearDown() {
        runtime.transitionScheduler.transitionReservationProbe = null;
        runtime.transitionPublicationProbe = null;
        runtime.activationRollbackCommitProbe = null;
        publicRuntime.close();
    }

    @Test
    void settlementWaitsForTransitionAlreadyAdvancedToStarting() throws Exception {
        CapabilityKey<String> key =
                CapabilityKey.of("settlement-start-gate", String.class);
        AtomicReference<CountDownLatch> startEntered = new AtomicReference<>();
        AtomicReference<CountDownLatch> releaseStart = new AtomicReference<>();
        MountFactory factory = MountFactory.of(
                "settlement-start-gate",
                ComponentDescriptor.named(
                        "settlement-start-gate",
                        CapabilityRequirement.required(key)),
                context -> {
                    startEntered.get().countDown();
                    releaseStart.get().await();
                });

        for (int round = 0; round < 1000; round++) {
            String mountId = "gate-" + round;
            MountHandle handle = publicRuntime.advanced().transact(transaction ->
                    transaction.mount(publicRuntime.root(), mountId, factory))
                    .value();
            assertEquals(ComponentState.WAITING, handle.whenSettled()
                    .toCompletableFuture().get(10, TimeUnit.SECONDS));

            startEntered.set(new CountDownLatch(1));
            releaseStart.set(new CountDownLatch(1));
            TransactionReceipt<RegistrationHandle> receipt = publicRuntime.advanced().transact(
                    transaction -> transaction.provide(publicRuntime.root(), key, "value"));
            assertTrue(startEntered.get().await(10, TimeUnit.SECONDS),
                    "round " + round + " did not enter start");
            assertFalse(receipt.settlement().whenSettled().toCompletableFuture().isDone(),
                    "round " + round + " settled while STARTING transition was live");

            releaseStart.get().countDown();
            receipt.settlement().whenSettled().toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertEquals(ComponentState.ACTIVE, handle.whenSettled()
                    .toCompletableFuture().get(10, TimeUnit.SECONDS));

            publicRuntime.advanced().transact(transaction -> {
                transaction.revoke(receipt.value());
                return null;
            });
            handle.disposeAsync().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void prepublishCancelCompletesFutureMergedByObserver() throws Exception {
        MountHandle handle = publicRuntime.advanced().transact(transaction ->
                transaction.mount(publicRuntime.root(), "cancelled", MountFactory.of(
                        "cancelled",
                        ComponentDescriptor.named("cancelled"),
                        context -> {
                        })))
                .value();
        assertEquals(ComponentState.ACTIVE, handle.whenSettled()
                .toCompletableFuture().get(10, TimeUnit.SECONDS));

        AtomicReference<CompletableFuture<ComponentState>> observed = new AtomicReference<>();
        runtime.transitionScheduler.transitionReservationProbe = () -> {
            observed.set(handle.whenSettled().toCompletableFuture());
            throw new IllegalStateException("reservation commit failed");
        };
        assertThrows(RuntimeException.class, () -> publicRuntime.advanced().transact(
                transaction -> {
                    transaction.dispose(handle);
                    return null;
                }));

        CompletableFuture<ComponentState> pending = observed.get();
        ExecutionException failure = assertThrows(
                ExecutionException.class,
                () -> pending.get(10, TimeUnit.SECONDS));
        assertTrue(failure.getCause() instanceof TransitionCancelledStateException,
                () -> String.valueOf(failure.getCause()));
    }

    @Test
    void publishedDirtyReservationsSurviveRollbackCommitFailure() throws Exception {
        assertDirtyReservationsSurviveRollback(true);
    }

    @Test
    void publishedDirtyReservationsSurviveFirstCommitFailure() throws Exception {
        assertDirtyReservationsSurviveRollback(false);
    }

    private void assertDirtyReservationsSurviveRollback(boolean failRollbackCommit)
            throws Exception {
        CapabilityKey<String> key = CapabilityKey.of(
                (failRollbackCommit ? "rollback" : "first-commit") + "-shadow", String.class);
        ContextHandle child = publicRuntime.advanced().childContext(
                publicRuntime.root(), failRollbackCommit ? "rollback" : "first-commit");
        publicRuntime.advanced().transact(transaction -> transaction.provide(
                publicRuntime.root(), key, "host"));
        MountHandle consumer = publicRuntime.advanced().transact(transaction ->
                transaction.mount(child, "consumer", MountFactory.of(
                        "consumer",
                        ComponentDescriptor.named(
                                "consumer",
                                CapabilityRequirement.required(key)),
                        context -> {
                        })))
                .value();
        assertEquals(ComponentState.ACTIVE, consumer.whenSettled()
                .toCompletableFuture().get(10, TimeUnit.SECONDS));

        AtomicInteger publicationCalls = new AtomicInteger();
        runtime.transitionPublicationProbe = () -> {
            if (publicationCalls.incrementAndGet() == 2) {
                throw new IllegalStateException("injected activation commit failure");
            }
        };
        if (failRollbackCommit) {
            runtime.activationRollbackCommitProbe = () -> {
                throw new IllegalStateException("injected rollback commit failure");
            };
        }

        TransactionReceipt<MountHandle> receipt = publicRuntime.advanced().transact(
                transaction -> transaction.mount(child, "shadow", MountFactory.of(
                        "shadow",
                        ComponentDescriptor.named("shadow"),
                        context -> context.provide(key, "activation"))));
        MountHandle shadow = receipt.value();
        try {
            receipt.settlement().whenSettled().toCompletableFuture()
                    .get(10, TimeUnit.SECONDS);
        } catch (ExecutionException expected) {
            // Emergency rollback fails the operation settlement while preserving convergence.
        }
        shadow.whenSettled().toCompletableFuture().get(10, TimeUnit.SECONDS);
        consumer.whenSettled().toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertNoOrphanedStopping(shadow);
        assertNoOrphanedStopping(consumer);
    }

    @Test
    void executorRejectionCompletesReservedAndImmediateTransitions() throws Exception {
        MountHandle queued = publicRuntime.advanced().transact(transaction ->
                transaction.mount(publicRuntime.root(), "queued", MountFactory.of(
                        "queued",
                        ComponentDescriptor.named("queued"),
                        context -> {
                        })))
                .value();
        MountHandle immediate = publicRuntime.advanced().transact(transaction ->
                transaction.mount(publicRuntime.root(), "immediate", MountFactory.of(
                        "immediate",
                        ComponentDescriptor.named("immediate"),
                        context -> {
                        })))
                .value();
        assertEquals(ComponentState.ACTIVE, queued.whenSettled()
                .toCompletableFuture().get(10, TimeUnit.SECONDS));
        assertEquals(ComponentState.ACTIVE, immediate.whenSettled()
                .toCompletableFuture().get(10, TimeUnit.SECONDS));

        runtime.executor.shutdown();
        ComponentRuntime queuedRuntime = runtime.publishedState()
                .index.components.get(queued.handleId());
        ComponentRuntime.Reservation queuedReservation = queuedRuntime.reserveTransition(
                System.nanoTime(), "test transition");
        runtime.transitionScheduler.driveReservation(queuedReservation);
        ExecutionException queuedFailure = assertThrows(
                ExecutionException.class,
                () -> queuedReservation.future().get(10, TimeUnit.SECONDS));
        assertTrue(queuedFailure.getCause() instanceof TransitionRejectedStateException);
        assertTrue(queuedRuntime.noLongerOwnsTransition(queuedReservation.future()));

        ComponentRuntime immediateRuntime = runtime.publishedState()
                .index.components.get(immediate.handleId());
        ComponentRuntime.Reservation immediateReservation =
                immediateRuntime.reserveTransition(
                        System.nanoTime(), "test transition");
        runtime.driveTransition(immediateRuntime, immediateReservation.future());
        assertEquals(ComponentState.ACTIVE, immediateReservation.future()
                .get(10, TimeUnit.SECONDS));
        assertTrue(immediateRuntime.noLongerOwnsTransition(immediateReservation.future()));
    }

    @Test
    void missingComponentDriverClearsItsReservedSlot() throws Exception {
        MountHandle handle = publicRuntime.advanced().transact(transaction ->
                transaction.mount(publicRuntime.root(), "missing", MountFactory.of(
                        "missing",
                        ComponentDescriptor.named("missing"),
                        context -> {
                        })))
                .value();
        assertEquals(ComponentState.ACTIVE, handle.whenSettled()
                .toCompletableFuture().get(10, TimeUnit.SECONDS));
        ComponentRuntime component = runtime.publishedState()
                .index.components.get(handle.handleId());
        handle.disposeAsync().toCompletableFuture().get(10, TimeUnit.SECONDS);
        ComponentRuntime.Reservation reservation = component.reserveTransition(
                System.nanoTime(), "test transition");
        runtime.driveTransition(component, reservation.future());
        assertEquals(ComponentState.DISPOSED, reservation.future()
                .get(10, TimeUnit.SECONDS));
        assertTrue(component.noLongerOwnsTransition(reservation.future()));
    }

    private void assertNoOrphanedStopping(MountHandle handle) {
        ComponentRuntime component = runtime.publishedState()
                .index.components.get(handle.handleId());
        if (handle.state() == ComponentState.STOPPING) {
            assertTrue(component != null && component.transitionDiagnostic().contains("slot="),
                    () -> handle.handleId() + " is STOPPING without a transition slot");
        }
    }
}
