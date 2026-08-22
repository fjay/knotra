package io.knotra;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class RequireActiveTest {
    static final CapabilityKey<String> DEPENDENCY =
            CapabilityKey.of("require-active-dependency", String.class);

    KnotraRuntime runtime = KnotraRuntime.create();

    @AfterEach
    void tearDown() throws Exception {
        runtime.close();
    }

    @Test
    void activeComponentReturnsItselfForBothWaitingForms() throws Exception {
        var handle = TestKit.mount(runtime, runtime.root(), "active",
                (context, config) -> {});
        assertSame(handle, handle.requireActive());
        assertSame(handle, handle.requireActive(Duration.ofSeconds(10)));
        assertEquals(ComponentState.ACTIVE, handle.state());
    }

    @Test
    void waitingComponentFailsWithMissingCapabilityDiagnostics() throws Exception {
        var handle = TestKit.mount(runtime, runtime.root(), "waiting",
                (context, config) -> {}, CapabilityRequirement.required(DEPENDENCY));
        assertEquals(ComponentState.WAITING, TestKit.settle(handle).call());

        ComponentNotActiveException error =
                assertThrows(ComponentNotActiveException.class, handle::requireActive);
        assertEquals(ComponentState.WAITING, error.state());
        assertEquals(handle.handleId(), error.handleId());
        assertTrue(error.diagnostics().stream().anyMatch(diagnostic ->
                diagnostic.code() == DiagnosticCode.MISSING_CAPABILITY));
    }

    @Test
    void failedComponentFailsWithActivationDiagnostics() throws Exception {
        var handle = TestKit.mount(runtime, runtime.root(), "failed",
                (context, config) -> {
                    throw new IllegalStateException("start failed");
                });
        assertEquals(ComponentState.FAILED, TestKit.settle(handle).call());

        ComponentNotActiveException error =
                assertThrows(ComponentNotActiveException.class, handle::requireActive);
        assertEquals(ComponentState.FAILED, error.state());
        assertTrue(error.diagnostics().stream().anyMatch(diagnostic ->
                diagnostic.code() == DiagnosticCode.ACTIVATION_FAILED));
    }

    @Test
    void disposedHandleRetainsStableLogicalIdentity() throws Exception {
        ContextHandle child = TestKit.child(runtime, runtime.root(), "identity");
        ComponentFactory<NoConfig> factory = TestKit.factory("identity-factory",
                new TestKit.Scripted<>(ComponentDescriptor.named("identity-component"),
                        (context, config) -> {}));
        var handle = runtime.transact(transaction ->
                transaction.mount(child, "identity-mount", factory)).value();
        assertEquals(ComponentState.ACTIVE, TestKit.settle(handle).call());
        assertEquals(ComponentState.DISPOSED, handle.disposeAsync()
                .toCompletableFuture().get(10, TimeUnit.SECONDS));

        assertEquals("identity-mount", handle.mountId());
        assertEquals("identity-component", handle.componentId());
        assertEquals("identity-factory", handle.factoryId());
        assertEquals(child.contextId(), handle.contextId());

        ComponentNotActiveException error =
                assertThrows(ComponentNotActiveException.class, handle::requireActive);
        assertEquals(ComponentState.DISPOSED, error.state());
        assertEquals(handle.handleId(), error.handleId());
        assertEquals("identity-mount", error.mountId());
        assertEquals("identity-component", error.componentId());
        assertEquals("identity-factory", error.factoryId());
        assertEquals(child.contextId(), error.contextId());
        assertNull(error.getCause());
    }

    @Test
    void thrownFailuresNeverClaimActiveUnderConcurrentRestart() throws Exception {
        for (int round = 0; round < 150; round++) {
            final int roundId = round;
            CapabilityKey<String> dependency =
                    CapabilityKey.of("restart-race-" + roundId, String.class);
            var handle = TestKit.mount(runtime, runtime.root(), "race-" + roundId,
                    (context, config) -> {}, CapabilityRequirement.required(dependency));
            assertEquals(ComponentState.WAITING, TestKit.settle(handle).call());

            CompletableFuture<Void> restart = CompletableFuture.runAsync(() ->
                    TestKit.provide(runtime, runtime.root(), dependency,
                            "value-" + roundId));
            try {
                handle.requireActive();
            } catch (ComponentNotActiveException error) {
                assertNotEquals(ComponentState.ACTIVE, error.state(),
                        () -> "round " + roundId + ": " + error);
                assertNotEquals(ComponentState.STARTING, error.state(),
                        () -> "settled result must be a settled state: " + error);
            } finally {
                restart.get(10, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void disposedComponentFails() throws Exception {
        var handle = TestKit.mount(runtime, runtime.root(), "disposed",
                (context, config) -> {});
        assertEquals(ComponentState.ACTIVE, TestKit.settle(handle).call());
        assertEquals(ComponentState.DISPOSED, handle.disposeAsync()
                .toCompletableFuture().get(10, TimeUnit.SECONDS));

        ComponentNotActiveException error =
                assertThrows(ComponentNotActiveException.class, handle::requireActive);
        assertEquals(ComponentState.DISPOSED, error.state());
    }

    @Test
    void boundedWaitTimesOutDuringTheCurrentTransition() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CompletableFuture<Void> gate = new CompletableFuture<>();
        var handle = TestKit.mount(runtime, runtime.root(), "starting",
                (context, config) -> {
                    entered.countDown();
                    gate.get();
                });
        assertTrue(entered.await(10, TimeUnit.SECONDS));

        Duration timeout = Duration.ofMillis(20);
        try {
            ComponentNotActiveException error = assertThrows(ComponentNotActiveException.class,
                    () -> handle.requireActive(timeout));
            assertEquals(ComponentState.STARTING, error.state());
            assertEquals(timeout, error.timeout());
        } finally {
            gate.complete(null);
        }
        assertEquals(ComponentState.ACTIVE, TestKit.settle(handle).call());
    }

    @Test
    void boundedWaitRejectsNullAndNonPositiveDurations() throws Exception {
        var handle = TestKit.mount(runtime, runtime.root(), "validation",
                (context, config) -> {});
        assertEquals(ComponentState.ACTIVE, TestKit.settle(handle).call());

        assertThrows(NullPointerException.class, () -> handle.requireActive(null));
        assertThrows(IllegalArgumentException.class, () ->
                handle.requireActive(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () ->
                handle.requireActive(Duration.ofNanos(-1)));
        assertEquals(ComponentState.ACTIVE, handle.state());
    }

    @Test
    void unboundedWaitCanBeInterrupted() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch callerReady = new CountDownLatch(1);
        CompletableFuture<Void> gate = new CompletableFuture<>();
        var handle = TestKit.mount(runtime, runtime.root(), "interrupted",
                (context, config) -> {
                    entered.countDown();
                    gate.get();
                });
        assertTrue(entered.await(10, TimeUnit.SECONDS));

        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch finished = new CountDownLatch(1);
        Thread caller = new Thread(() -> {
            try {
                callerReady.countDown();
                handle.requireActive();
            } catch (Throwable error) {
                failure.set(error);
            } finally {
                finished.countDown();
            }
        }, "require-active-caller");
        try {
            caller.start();
            assertTrue(callerReady.await(10, TimeUnit.SECONDS));
            caller.interrupt();
            assertTrue(finished.await(10, TimeUnit.SECONDS));

            ComponentNotActiveException error =
                    assertInstanceOf(ComponentNotActiveException.class, failure.get());
            assertEquals(ComponentState.STARTING, error.state());
            assertNull(error.getCause(), "exception must not retain a Throwable");
            assertTrue(error.diagnostics().stream().anyMatch(diagnostic ->
                    diagnostic.message().contains("wait interrupted")),
                    () -> error.toString());
            assertTrue(caller.isInterrupted());
        } finally {
            gate.complete(null);
            caller.join(10_000);
        }
        assertEquals(ComponentState.ACTIVE, TestKit.settle(handle).call());
    }

    @Test
    void interfaceDefaultsWorkWithoutANewAbstractMethod() {
        ImmediateHandle handle = new ImmediateHandle();
        assertSame(handle, handle.requireActive());
        assertSame(handle, handle.requireActive(Duration.ofMillis(100)));
    }

    @Test
    void interfaceDefaultReportsSettledStateInsteadOfRereadingCurrentState() {
        ImmediateHandle handle = new ImmediateHandle() {
            @Override
            public ComponentState state() {
                return ComponentState.STARTING;
            }

            @Override
            public CompletionStage<ComponentState> whenSettled() {
                return CompletableFuture.completedFuture(ComponentState.WAITING);
            }
        };

        ComponentNotActiveException error =
                assertThrows(ComponentNotActiveException.class, handle::requireActive);
        assertEquals(ComponentState.WAITING, error.state());
        assertNull(error.getCause());
    }

    private static class ImmediateHandle implements ComponentHandle<Void> {
        @Override
        public String handleId() {
            return "immediate";
        }

        @Override
        public String mountId() {
            return "immediate";
        }

        @Override
        public String componentId() {
            return "immediate";
        }

        @Override
        public String factoryId() {
            return "immediate";
        }

        @Override
        public String contextId() {
            return "ctx-root";
        }

        @Override
        public ComponentState state() {
            return ComponentState.ACTIVE;
        }

        @Override
        public ComponentGoal goal() {
            return ComponentGoal.RUNNING;
        }

        @Override
        public long configRevision() {
            return 0;
        }

        @Override
        public CompletionStage<ComponentState> whenSettled() {
            return CompletableFuture.completedFuture(ComponentState.ACTIVE);
        }

        @Override
        public CompletionStage<ComponentState> reconfigureAsync(Void config) {
            return CompletableFuture.completedFuture(ComponentState.ACTIVE);
        }

        @Override
        public CompletionStage<ComponentState> retryAsync() {
            return CompletableFuture.completedFuture(ComponentState.ACTIVE);
        }

        @Override
        public CompletionStage<ComponentState> disposeAsync() {
            return CompletableFuture.completedFuture(ComponentState.DISPOSED);
        }
    }
}
