package io.knotra.internal;

import io.knotra.ActivationContext;
import io.knotra.Component;
import io.knotra.ComponentDescriptor;
import io.knotra.ComponentFactory;
import io.knotra.ComponentState;
import io.knotra.KnotraRuntime;
import io.knotra.MountHandle;
import io.knotra.NoConfig;
import io.knotra.RegistrationHandle;
import io.knotra.TransactionReceipt;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TransitionRaceRegressionTest {
    private static final io.knotra.CapabilityKey<String> A =
            io.knotra.CapabilityKey.of("transition-race-a", String.class);

    private final KnotraRuntime publicRuntime = KnotraRuntime.create();
    private final DefaultKnotraRuntime runtime = (DefaultKnotraRuntime) publicRuntime;

    @AfterEach
    void tearDown() throws Exception {
        publicRuntime.close();
    }

    @Test
    void activationDecisionRechecksStructuralDisposeBeforeCompleting() throws Exception {
        final MountHandle handle = publicRuntime.advanced().transact(transaction ->
                transaction.mount(publicRuntime.root(), "decision-race", factory()))
                .value();
        assertEquals(ComponentState.WAITING, handle.whenSettled()
                .toCompletableFuture().get(10, TimeUnit.SECONDS));

        CountDownLatch decisionEntered = new CountDownLatch(1);
        CountDownLatch releaseDecision = new CountDownLatch(1);
        runtime.activationDecisionProbe = () -> {
            decisionEntered.countDown();
            try {
                assertTrue(releaseDecision.await(10, TimeUnit.SECONDS));
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        };

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CompletableFuture<TransactionReceipt<RegistrationHandle>> provide =
                    CompletableFuture.supplyAsync(() -> publicRuntime.advanced().transact(
                            mutation -> mutation.provide(publicRuntime.root(), A, "v")), executor);
            assertTrue(decisionEntered.await(10, TimeUnit.SECONDS));

            CompletableFuture<ComponentState> dispose =
                    CompletableFuture.supplyAsync(() ->
                            handle.disposeAsync().toCompletableFuture().join(), executor);
            awaitComponentState(handle, ComponentState.STOPPING);
            releaseDecision.countDown();

            provide.get(10, TimeUnit.SECONDS);
            assertEquals(ComponentState.DISPOSED, dispose.get(10, TimeUnit.SECONDS));
            assertEquals(ComponentState.DISPOSED, handle.whenSettled()
                    .toCompletableFuture().get(10, TimeUnit.SECONDS));
        } finally {
            releaseDecision.countDown();
            runtime.activationDecisionProbe = null;
            executor.shutdown();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void provideAndDisposeRaceAlwaysHasOneTransitionDriver() throws Exception {
        assertProvideAndDisposeRaceHasOneDriver(500);
        assertProvideAndDisposeRaceHasOneDriver(1000);
    }

    private void assertProvideAndDisposeRaceHasOneDriver(int rounds) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(4);
        boolean diagnosticFailure = false;
        try {
            for (int round = 0; round < rounds; round++) {
                final int mountRound = round;
                final MountHandle handle = publicRuntime.advanced().transact(transaction ->
                        transaction.mount(publicRuntime.root(), "race-" + mountRound, factory()))
                        .value();
                assertEquals(ComponentState.WAITING, handle.whenSettled()
                        .toCompletableFuture().get(10, TimeUnit.SECONDS));

                CompletableFuture<TransactionReceipt<RegistrationHandle>> provide =
                        CompletableFuture.supplyAsync(() -> publicRuntime.advanced().transact(
                                        mutation -> mutation.provide(publicRuntime.root(), A, "v")),
                                executor);
                CompletableFuture<ComponentState> dispose = CompletableFuture.supplyAsync(() ->
                        handle.disposeAsync().toCompletableFuture().join(), executor);

                provide.get(10, TimeUnit.SECONDS);
                ComponentState state;
                try {
                    state = dispose.get(10, TimeUnit.SECONDS);
                } catch (java.util.concurrent.TimeoutException error) {
                    diagnosticFailure = true;
                    throw new AssertionError(diagnostic(round, handle), error);
                }
                final int failureRound = round;
                assertEquals(ComponentState.DISPOSED, state,
                        () -> diagnostic(failureRound, handle));

                RegistrationHandle registration = provide.join().value();
                publicRuntime.advanced().transact(transaction -> {
                    transaction.revoke(registration);
                    return null;
                });
                assertEquals(ComponentState.DISPOSED, handle.state());
            }
        } finally {
            if (diagnosticFailure) {
                executor.shutdownNow();
            } else {
                executor.shutdown();
                assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
            }
        }
    }

    private static void awaitComponentState(MountHandle handle, ComponentState expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (handle.state() != expected) {
            if (System.nanoTime() - deadline >= 0) {
                throw new AssertionError(
                        "expected " + expected + " but remained " + handle.state());
            }
            Thread.yield();
        }
    }

    private String diagnostic(int round, MountHandle handle) {
        ComponentRuntime component = runtime.publishedState()
                .index.components.get(handle.handleId());
        return "round=" + round
                + " state=" + handle.state()
                + " transition=" + (component == null
                ? "component-missing"
                : component.transitionDiagnostic())
                + " snapshot=" + publicRuntime.advanced().snapshot();
    }

    private static ComponentFactory<NoConfig> factory() {
        return new ComponentFactory<>() {
            @Override
            public String factoryId() {
                return "transition-race";
            }

            @Override
            public Component<NoConfig> create() {
                return new Component<>() {
                    @Override
                    public ComponentDescriptor descriptor() {
                        return ComponentDescriptor.named(
                                "transition-race",
                                io.knotra.CapabilityRequirement.required(A));
                    }

                    @Override
                    public void start(ActivationContext context, NoConfig config) {
                    }
                };
            }
        };
    }
}
