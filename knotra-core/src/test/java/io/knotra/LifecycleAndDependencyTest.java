package io.knotra;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class LifecycleAndDependencyTest {
    static final CapabilityKey<String> A = CapabilityKey.of("a", String.class);
    static final CapabilityKey<String> B = CapabilityKey.of("b", String.class);

    KnotraRuntime runtime = KnotraRuntime.create();

    @AfterEach
    void tearDown() throws Exception {
        runtime.close();
    }

    private ComponentHandle<NoConfig> mount(String id, TestKit.Start<NoConfig> start,
                                             CapabilityRequirement... requirements) {
        return TestKit.mount(runtime, runtime.rootContext(), id, id, start, requirements);
    }

    @Test
    void autoCloseableManageReturnsTheOriginalResource() throws Exception {
        AtomicReference<TestResource> returned = new AtomicReference<>();
        var handle = mount("resource", (context, config) -> {
            TestResource resource = new TestResource();
            returned.set(context.lifecycle().manage("resource", resource));
        });
        assertEquals(ComponentState.ACTIVE, TestKit.settle(handle).call());
        assertSame(returned.get(), returned.get());
        handle.dispose().toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertTrue(returned.get().closed);
    }

    @Test
    void nestedScopesTearDownInLifoOrder() throws Exception {
        List<String> events = new ArrayList<>();
        var handle = mount("lifo", (context, config) -> {
            context.lifecycle().onClose("first", () -> events.add("first"));
            var nested = context.lifecycle().child("nested");
            nested.onClose("nested-entry", () -> events.add("nested-entry"));
            context.lifecycle().onClose("last", () -> events.add("last"));
        });
        assertEquals(ComponentState.ACTIVE, TestKit.settle(handle).call());
        handle.dispose().toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertEquals(List.of("last", "nested-entry", "first"), events);
    }

    @Test
    void asyncDisposersAreAwaitedBeforeSettlement() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CompletableFuture<Void> gate = new CompletableFuture<>();
        var handle = mount("async", (context, config) ->
                context.lifecycle().manageAsync("async", () -> {
                    entered.countDown();
                    return gate;
                }));
        assertEquals(ComponentState.ACTIVE, TestKit.settle(handle).call());
        var disposed = handle.dispose().toCompletableFuture();
        assertTrue(entered.await(10, TimeUnit.SECONDS));
        assertFalse(disposed.isDone());
        gate.complete(null);
        assertEquals(ComponentState.DISPOSED, disposed.get(10, TimeUnit.SECONDS));
    }

    @Test
    void parallelChildRunsOnlyItsDirectEntriesWithoutCrossingLaterSibling() throws Exception {
        AtomicInteger enteredCount = new AtomicInteger();
        CountDownLatch bothRunning = new CountDownLatch(2);
        CompletableFuture<Void> gate = new CompletableFuture<>();
        List<String> events = new java.util.concurrent.CopyOnWriteArrayList<>();
        var handle = mount("parallel", (context, config) -> {
            var group = context.lifecycle().parallelChild("group");
            group.manageAsync("first", () -> {
                enteredCount.incrementAndGet();
                bothRunning.countDown();
                return gate.whenComplete((ignored, error) -> events.add("first"));
            });
            group.manageAsync("second", () -> {
                enteredCount.incrementAndGet();
                bothRunning.countDown();
                return gate.whenComplete((ignored, error) -> events.add("second"));
            });
            context.lifecycle().onClose("later", () -> events.add("later"));
        });
        assertEquals(ComponentState.ACTIVE, TestKit.settle(handle).call());
        var disposing = handle.dispose().toCompletableFuture();
        try {
            assertEquals(ComponentState.STOPPING, handle.state(),
                    () -> runtime.snapshot().toString());
            assertTrue(bothRunning.await(10, TimeUnit.SECONDS),
                    () -> runtime.snapshot().toString());
            assertTrue(events.contains("later"));
        } finally {
            gate.complete(null);
        }
        disposing.get(10, TimeUnit.SECONDS);
        assertEquals(List.of("later", "first", "second").stream().sorted().toList(),
                events.stream().sorted().toList());
        assertEquals(2, enteredCount.get());
    }

    @Test
    void cleanupFailureContinuesOtherEntriesAndMarksHandleFailed() throws Exception {
        AtomicBoolean failOnce = new AtomicBoolean(true);
        List<String> events = new ArrayList<>();
        var handle = mount("bad-cleanup", (context, config) -> {
            context.lifecycle().onClose("before", () -> events.add("before"));
            Runnable bad = () -> {
                if (failOnce.getAndSet(false)) {
                    throw new IllegalStateException("temporary");
                }
            };
            context.lifecycle().onClose("bad", bad);
            context.lifecycle().onClose("after", () -> events.add("after"));
        });
        assertEquals(ComponentState.ACTIVE, TestKit.settle(handle).call());
        assertEquals(ComponentState.FAILED, handle.dispose().toCompletableFuture().get(10, TimeUnit.SECONDS));
        assertEquals(List.of("after", "before"), events);
        assertTrue(runtime.snapshot().diagnostics().stream()
                .anyMatch(diagnostic -> diagnostic.code() == DiagnosticCode.CLEANUP_FAILED));
    }

    @Test
    void retryRepeatsOnlyFailedCleanupEntriesAcrossMultipleAttempts() throws Exception {
        AtomicInteger goodAttempts = new AtomicInteger();
        AtomicInteger badAttempts = new AtomicInteger();
        var handle = mount("retry-cleanup", (context, config) -> {
            context.lifecycle().onClose("good", goodAttempts::incrementAndGet);
            context.lifecycle().onClose("bad", () -> {
                if (badAttempts.incrementAndGet() < 3) {
                    throw new IllegalStateException("temporary");
                }
            });
        });
        assertEquals(ComponentState.ACTIVE, TestKit.settle(handle).call());
        assertEquals(ComponentState.FAILED, handle.dispose().toCompletableFuture().get(10, TimeUnit.SECONDS));
        assertEquals(ComponentState.FAILED, handle.retry().toCompletableFuture().get(10, TimeUnit.SECONDS));
        assertEquals(ComponentState.DISPOSED, handle.retry().toCompletableFuture().get(10, TimeUnit.SECONDS));
        assertEquals(1, goodAttempts.get());
        assertEquals(3, badAttempts.get());
    }

    @Test
    void resourcesCanBeManagedDynamicallyWhileActive() throws Exception {
        List<String> closed = new ArrayList<>();
        AtomicReference<LifecycleScope> scope = new AtomicReference<>();
        var handle = mount("dynamic", (context, config) -> scope.set(context.lifecycle()));
        assertEquals(ComponentState.ACTIVE, TestKit.settle(handle).call());
        scope.get().onClose("late", () -> closed.add("late"));
        handle.dispose().toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertEquals(List.of("late"), closed);
    }

    @Test
    void stoppingScopeRejectsLateManage() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CompletableFuture<Void> gate = new CompletableFuture<>();
        AtomicReference<LifecycleScope> scope = new AtomicReference<>();
        var handle = mount("stopping", (context, config) -> {
            scope.set(context.lifecycle());
            context.lifecycle().manageAsync("blocked", () -> {
                entered.countDown();
                return gate;
            });
        });
        assertEquals(ComponentState.ACTIVE, TestKit.settle(handle).call());
        var stopping = handle.dispose().toCompletableFuture();
        assertTrue(entered.await(10, TimeUnit.SECONDS));
        assertThrows(IllegalStateException.class, () ->
                scope.get().onClose("late", () -> {}));
        gate.complete(null);
        assertEquals(ComponentState.DISPOSED, stopping.get(10, TimeUnit.SECONDS));
    }

    @Test
    void directDependentDetachesBeforeProviderTeardown() throws Exception {
        List<String> order = new java.util.concurrent.CopyOnWriteArrayList<>();
        var provider = mount("provider", (context, config) -> {
            context.provide(A, "a");
            context.lifecycle().onClose("provider", () -> order.add("provider"));
        });
        assertEquals(ComponentState.ACTIVE, TestKit.settle(provider).call());
        var consumer = mount("consumer", (context, config) -> {
            context.require(A);
            context.lifecycle().onClose("consumer", () -> order.add("consumer"));
        }, CapabilityRequirement.required(A));
        assertEquals(ComponentState.ACTIVE, TestKit.settle(consumer).call());
        provider.dispose().toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertEquals(List.of("consumer", "provider"), order, () -> runtime.snapshot().toString());
        assertEquals(ComponentState.WAITING, consumer.state());
    }

    @Test
    void indirectDependentsDetachBeforeTheirProviders() throws Exception {
        List<String> order = new java.util.concurrent.CopyOnWriteArrayList<>();
        var provider = mount("provider", (context, config) -> {
            context.provide(A, "a");
            context.lifecycle().onClose("provider", () -> order.add("provider"));
        });
        var middle = mount("middle", (context, config) -> {
            context.require(A);
            context.provide(B, "b");
            context.lifecycle().onClose("middle", () -> order.add("middle"));
        }, CapabilityRequirement.required(A));
        var leaf = mount("leaf", (context, config) -> {
            context.require(B);
            context.lifecycle().onClose("leaf", () -> order.add("leaf"));
        }, CapabilityRequirement.required(B));
        assertEquals(ComponentState.ACTIVE, TestKit.settle(provider).call());
        assertEquals(ComponentState.ACTIVE, TestKit.settle(middle).call());
        assertEquals(ComponentState.ACTIVE, TestKit.settle(leaf).call());
        provider.dispose().toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertEquals(List.of("leaf", "middle", "provider"), order);
    }

    @Test
    void dependentCleanupFailureDoesNotBlockProviderCleanup() throws Exception {
        AtomicBoolean failOnce = new AtomicBoolean(true);
        var provider = mount("provider", (context, config) -> context.provide(A, "a"));
        var consumer = mount("consumer", (context, config) -> {
            context.require(A);
            Runnable bad = () -> {
                if (failOnce.getAndSet(false)) {
                    throw new IllegalStateException("dependent cleanup failed");
                }
            };
            context.require(A);
            context.lifecycle().onClose("bad", bad);
        }, CapabilityRequirement.required(A));
        assertEquals(ComponentState.ACTIVE, TestKit.settle(provider).call());
        assertEquals(ComponentState.ACTIVE, TestKit.settle(consumer).call());
        assertEquals(ComponentState.DISPOSED, provider.dispose().toCompletableFuture().get(10, TimeUnit.SECONDS));
        assertEquals(ComponentState.FAILED, consumer.state());
    }

    @Test
    void newProviderDoesNotOverlapOldConsumerCleanup() throws Exception {
        CountDownLatch consumerCleanupStarted = new CountDownLatch(1);
        CompletableFuture<Void> cleanupGate = new CompletableFuture<>();
        AtomicReference<String> observed = new AtomicReference<>();
        var provider = mount("provider", (context, config) -> context.provide(A, "first"));
        var consumer = mount("consumer", (context, config) -> {
            observed.set(context.require(A));
            context.lifecycle().manageAsync("consumer", () -> {
                consumerCleanupStarted.countDown();
                return cleanupGate;
            });
        }, CapabilityRequirement.required(A));
        assertEquals(ComponentState.ACTIVE, TestKit.settle(provider).call());
        assertEquals(ComponentState.ACTIVE, TestKit.settle(consumer).call());
        var providerDisposed = provider.dispose().toCompletableFuture();
        assertTrue(consumerCleanupStarted.await(10, TimeUnit.SECONDS));
        TestKit.provide(runtime, runtime.rootContext(), A, "second");
        assertEquals(ComponentState.STOPPING, consumer.state());
        cleanupGate.complete(null);
        assertEquals(ComponentState.DISPOSED, providerDisposed.get(10, TimeUnit.SECONDS));
        assertEquals(ComponentState.ACTIVE, TestKit.settle(consumer).call());
        assertEquals("second", observed.get());
    }

    @Test
    void failedDisposeRetryCompletesTerminalGoalWithoutRestart() throws Exception {
        AtomicBoolean failOnce = new AtomicBoolean(true);
        var handle = mount("terminal", (context, config) ->
                context.lifecycle().onClose("bad", () -> {
                    if (failOnce.getAndSet(false)) {
                        throw new IllegalStateException("once");
                    }
                }));
        assertEquals(ComponentState.ACTIVE, TestKit.settle(handle).call());
        assertEquals(ComponentState.FAILED, handle.dispose().toCompletableFuture().get(10, TimeUnit.SECONDS));
        assertEquals(ComponentGoal.DISPOSED, handle.goal());
        assertEquals(ComponentState.DISPOSED, handle.retry().toCompletableFuture().get(10, TimeUnit.SECONDS));
    }

    @Test
    void concurrentDisposeRunsOneTransitionOnly() throws Exception {
        AtomicInteger cleanup = new AtomicInteger();
        var handle = mount("one-dispose", (context, config) ->
                context.lifecycle().onClose("once", cleanup::incrementAndGet));
        assertEquals(ComponentState.ACTIVE, TestKit.settle(handle).call());
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            var futures = new ArrayList<java.util.concurrent.Future<ComponentState>>();
            for (int i = 0; i < 8; i++) {
                futures.add(executor.submit(() ->
                        handle.dispose().toCompletableFuture().get(10, TimeUnit.SECONDS)));
            }
            for (var future : futures) {
                assertEquals(ComponentState.DISPOSED, future.get(10, TimeUnit.SECONDS));
            }
        } finally {
            executor.shutdown();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
        assertEquals(1, cleanup.get());
    }

    @Test
    void failedContextDisposalCanBeRetried() throws Exception {
        AtomicBoolean failOnce = new AtomicBoolean(true);
        ContextHandle child = TestKit.child(runtime, runtime.rootContext(), "retry-child");
        var handle = TestKit.mount(runtime, child, "component", (context, config) ->
                context.lifecycle().onClose("bad", () -> {
                    if (failOnce.getAndSet(false)) {
                        throw new IllegalStateException("context cleanup failed");
                    }
                }));
        assertEquals(ComponentState.ACTIVE, TestKit.settle(handle).call());
        assertTrue(assertThrows(java.util.concurrent.ExecutionException.class, () ->
                child.disposeAsync().toCompletableFuture().get(10, TimeUnit.SECONDS))
                instanceof java.util.concurrent.ExecutionException);
        child.disposeAsync().toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertEquals(ContextState.DISPOSED, child.state());
    }

    private static final class TestResource implements AutoCloseable {
        private boolean closed;

        @Override
        public void close() {
            closed = true;
        }
    }
}
