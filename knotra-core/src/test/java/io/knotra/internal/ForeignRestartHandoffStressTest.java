package io.knotra.internal;

import io.knotra.ActivationContext;
import io.knotra.Component;
import io.knotra.ComponentDescriptor;
import io.knotra.ComponentFactory;
import io.knotra.ComponentState;
import io.knotra.ConfiguredMountHandle;
import io.knotra.KnotraRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Repeatedly races foreign-owner cancellation with cleanup's restart handoff. */
final class ForeignRestartHandoffStressTest {
    private static final int ROUNDS = Math.max(1, Integer.getInteger(
            "knotra.foreign.handoff.rounds", 200));

    private final KnotraRuntime runtime = KnotraRuntime.create();
    private final DefaultKnotraRuntime internal = (DefaultKnotraRuntime) runtime;

    @AfterEach
    void tearDown() {
        runtime.close();
    }

    @Test
    void cancelledForeignOwnersAlwaysRestoreAutomaticConvergence() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            runRound(round);
        }
    }

    private void runRound(int round) throws Exception {
        String mountId = "foreign-handoff-" + round;
        CountDownLatch startEntered = new CountDownLatch(1);
        CountDownLatch releaseStart = new CountDownLatch(1);
        CountDownLatch completionCaptured = new CountDownLatch(1);
        AtomicReference<CompletableFuture<ComponentState>> gatedFuture =
                new AtomicReference<>();
        AtomicReference<Runnable> gatedCompletion = new AtomicReference<>();
        AtomicBoolean publicationFaulted = new AtomicBoolean();
        AtomicBoolean foreignInjected = new AtomicBoolean();
        AtomicReference<ComponentRuntime.Reservation> foreign = new AtomicReference<>();
        AtomicInteger starts = new AtomicInteger();

        ConfiguredMountHandle<String> handle = mount(mountId, config -> {
            if (starts.incrementAndGet() == 1) {
                startEntered.countDown();
                assertTrue(releaseStart.await(10, TimeUnit.SECONDS), "round " + round);
            }
        });
        assertTrue(startEntered.await(10, TimeUnit.SECONDS), "round " + round);

        internal.activationCoordinator().activationFailureCompletionGate =
                (future, completion) -> {
                    gatedFuture.set(future);
                    gatedCompletion.set(completion);
                    completionCaptured.countDown();
                };
        CompletableFuture<ComponentState> reconfigured =
                handle.reconfigureAsync("v2").toCompletableFuture();
        internal.activationCoordinator().transitionPublicationProbe = () -> {
            if (!publicationFaulted.getAndSet(true)) {
                throw new IllegalStateException("injected publication fault");
            }
        };
        internal.activationCoordinator().cleanupFinalCommitProbe = () -> {
            if (foreignInjected.getAndSet(true)) {
                return;
            }
            ComponentRuntime component =
                    internal.publishedState().index.components.get(handle.handleId());
            foreign.set(component.reserveTransition(
                    internal.activationCoordinator().scheduler().pendingTime(),
                    "foreign restart owner"));
        };

        releaseStart.countDown();
        assertTrue(completionCaptured.await(10, TimeUnit.SECONDS), "round " + round);
        assertEquals(ComponentState.WAITING, awaitState(
                handle.handleId(), ComponentState.WAITING), "round " + round);

        ComponentRuntime.Reservation reservation = foreign.get();
        assertNotNull(reservation, "round " + round);
        ComponentRuntime component =
                internal.publishedState().index.components.get(handle.handleId());
        assertTrue(component.cancelTransition(reservation.future()), "round " + round);
        internal.activationCoordinator().scheduler().completeCancelled(
                List.of(reservation.future()));

        assertEquals(ComponentState.ACTIVE, awaitState(
                handle.handleId(), ComponentState.ACTIVE), "round " + round);
        assertEquals(2, starts.get(), "round " + round);
        awaitNoPending(handle.handleId(), round);

        gatedCompletion.get().run();
        assertThrows(
                ExecutionException.class,
                () -> reconfigured.get(10, TimeUnit.SECONDS),
                "round " + round);
    }

    private ConfiguredMountHandle<String> mount(String mountId, ConfiguredStart start) {
        ComponentFactory<String> factory = new ComponentFactory<>() {
            @Override
            public String factoryId() {
                return mountId;
            }

            @Override
            public Component<String> create() {
                return new Component<>() {
                    @Override
                    public ComponentDescriptor descriptor() {
                        return ComponentDescriptor.named(mountId);
                    }

                    @Override
                    public void start(ActivationContext context, String config)
                            throws Exception {
                        start.start(config);
                    }
                };
            }
        };
        return runtime.advanced().transact(transaction -> transaction.mount(
                runtime.root(), mountId, factory, "v1")).value();
    }

    private ComponentState awaitState(String handleId, ComponentState expected)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        ComponentState state = internal.componentState(handleId);
        while (state != expected) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("component did not converge to "
                        + expected + ", now " + state);
            }
            Thread.sleep(1);
            state = internal.componentState(handleId);
        }
        return state;
    }

    private void awaitNoPending(String handleId, int round) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (runtime.advanced().pendingOperations().operations().stream()
                .anyMatch(operation -> operation.targetId().equals(handleId))) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("round " + round
                        + " left pending transition metadata");
            }
            Thread.sleep(1);
        }
    }

    @FunctionalInterface
    private interface ConfiguredStart {
        void start(String config) throws Exception;
    }
}
