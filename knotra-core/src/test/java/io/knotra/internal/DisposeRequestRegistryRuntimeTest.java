package io.knotra.internal;

import io.knotra.ComponentDescriptor;
import io.knotra.ComponentState;
import io.knotra.ContextHandle;
import io.knotra.ContextState;
import io.knotra.KnotraConfig;
import io.knotra.MountFactory;
import io.knotra.MountHandle;
import io.knotra.PendingOperationsSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DisposeRequestRegistryRuntimeTest {
    private final DefaultKnotraRuntime runtime =
            new DefaultKnotraRuntime(KnotraConfig.defaults(), System::nanoTime);

    @AfterEach
    void tearDown() {
        runtime.close();
    }

    @Test
    void hundredConcurrentDisposalsOfSameHandleShareOneFuture() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CompletableFuture<Void> gate = new CompletableFuture<>();
        MountHandle handle = mountGated("merged", entered, gate);
        assertEquals(ComponentState.ACTIVE, handle.whenSettled()
                .toCompletableFuture().get(10, TimeUnit.SECONDS));

        int threads = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CompletableFuture<ComponentState>[] results = new CompletableFuture[threads];
        AtomicInteger cursor = new AtomicInteger();
        try {
            for (int i = 0; i < threads; i++) {
                executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    results[cursor.getAndIncrement()] =
                            handle.disposeAsync().toCompletableFuture();
                    return null;
                });
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            executor.shutdown();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }

        Set<CompletableFuture<ComponentState>> distinct = new HashSet<>();
        for (CompletableFuture<ComponentState> future : results) {
            assertTrue(future != null, "every caller must receive a dispose future");
            assertTrue(!future.isDone(), "cleanup gate must keep the request in flight");
            distinct.add(future);
        }
        assertEquals(1, distinct.size(), "100 concurrent disposals must merge to one request");

        gate.complete(null);
        for (CompletableFuture<ComponentState> future : results) {
            assertEquals(ComponentState.DISPOSED, future.get(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void newDisposeRequestCanBeCreatedAfterCompletion() throws Exception {
        MountHandle handle = runtime.transact(transaction -> transaction.mount(
                runtime.root(),
                "completed",
                MountFactory.of("completed-factory",
                        ComponentDescriptor.named("completed"),
                        context -> { }))).value();
        assertEquals(ComponentState.ACTIVE, handle.whenSettled()
                .toCompletableFuture().get(10, TimeUnit.SECONDS));

        CompletableFuture<ComponentState> first =
                handle.disposeAsync().toCompletableFuture();
        assertEquals(ComponentState.DISPOSED, first.get(10, TimeUnit.SECONDS));

        CompletableFuture<ComponentState> second =
                handle.disposeAsync().toCompletableFuture();
        assertNotSame(first, second, "completed requests must not block a new explicit dispose");
        assertEquals(ComponentState.DISPOSED, second.get(10, TimeUnit.SECONDS));
    }

    @Test
    void failedCleanupRequiresExplicitRetry() throws Exception {
        AtomicInteger failures = new AtomicInteger(1);
        MountHandle handle = runtime.transact(transaction -> transaction.mount(
                runtime.root(),
                "failed-cleanup",
                MountFactory.of("failed-cleanup-factory",
                        ComponentDescriptor.named("failed-cleanup"),
                        context -> context.lifecycle().onClose("boom", () -> {
                            if (failures.getAndDecrement() > 0) {
                                throw new IllegalStateException("cleanup failed once");
                            }
                        })))).value();
        assertEquals(ComponentState.ACTIVE, handle.whenSettled()
                .toCompletableFuture().get(10, TimeUnit.SECONDS));

        CompletableFuture<ComponentState> disposal =
                handle.disposeAsync().toCompletableFuture();
        assertEquals(ComponentState.FAILED, disposal.get(10, TimeUnit.SECONDS));
        assertEquals(ComponentState.FAILED, handle.whenSettled()
                .toCompletableFuture().get(10, TimeUnit.SECONDS),
                "observation must not implicitly retry failed cleanup");

        assertEquals(ComponentState.DISPOSED, handle.retryAsync()
                .toCompletableFuture().get(10, TimeUnit.SECONDS));
    }

    @Test
    void concurrentChildContextDisposalsDedupToOneFuture() throws Exception {
        ContextHandle parent = runtime.advanced().childContext(runtime.root(), "deduc-parent");
        ContextHandle child = runtime.advanced().childContext(parent, "deduc-child-ctx");
        CountDownLatch entered = new CountDownLatch(1);
        CompletableFuture<Void> gate = new CompletableFuture<>();
        MountHandle blocked = mountGatedIn(child, "deduc-child", entered, gate);
        blocked.whenSettled().toCompletableFuture().get(10, TimeUnit.SECONDS);

        List<CompletableFuture<Void>> futures = new CopyOnWriteArrayList<>();
        runConcurrently(2, () -> futures.add(runtime
                .disposeContext((ContextHandleImpl) child).toCompletableFuture()));
        assertEquals(2, futures.size());
        assertSame(futures.get(0), futures.get(1),
                "concurrent disposals of the same child context must share one future");

        gate.complete(null);
        futures.get(0).get(10, TimeUnit.SECONDS);
        assertEquals(ContextState.DISPOSED, child.state());
    }

    @Test
    void concurrentParentContextDisposalsDedupToOneFuture() throws Exception {
        ContextHandle parent = runtime.advanced().childContext(runtime.root(), "dedup-parent");
        CountDownLatch entered = new CountDownLatch(1);
        CompletableFuture<Void> gate = new CompletableFuture<>();
        MountHandle blocked = mountGatedIn(parent, "dedup-child", entered, gate);
        blocked.whenSettled().toCompletableFuture().get(10, TimeUnit.SECONDS);

        List<CompletableFuture<Void>> futures = new CopyOnWriteArrayList<>();
        runConcurrently(2, () -> futures.add(runtime
                .disposeContext((ContextHandleImpl) parent).toCompletableFuture()));
        assertEquals(2, futures.size());
        assertSame(futures.get(0), futures.get(1),
                "concurrent disposals of the same parent context must share one future");

        gate.complete(null);
        futures.get(0).get(10, TimeUnit.SECONDS);
        assertEquals(ContextState.DISPOSED, parent.state());
    }

    @Test
    void failedContextDisposalRetryGetsNewFuture() throws Exception {
        ContextHandle context = runtime.advanced().childContext(runtime.root(), "retry-ctx");
        AtomicInteger failures = new AtomicInteger(1);
        MountHandle handle = runtime.transact(transaction -> transaction.mount(
                context,
                "retry-mount",
                MountFactory.of("retry-factory",
                        ComponentDescriptor.named("retry-mount"),
                        starter -> starter.lifecycle().onClose("boom", () -> {
                            if (failures.getAndDecrement() > 0) {
                                throw new IllegalStateException("context cleanup failed once");
                            }
                        })))).value();
        assertEquals(ComponentState.ACTIVE, handle.whenSettled()
                .toCompletableFuture().get(10, TimeUnit.SECONDS));

        CompletableFuture<ContextState> failed =
                context.disposeAsync().toCompletableFuture();
        assertEquals(ContextState.FAILED, failed.get(10, TimeUnit.SECONDS),
                "failed context cleanup must surface FAILED instead of an exception");
        assertEquals(ContextState.FAILED, context.state());

        CompletableFuture<ContextState> retried =
                context.disposeAsync().toCompletableFuture();
        assertNotSame(failed, retried, "failed future must not shadow the retry request");
        assertEquals(ContextState.DISPOSED, retried.get(10, TimeUnit.SECONDS));
    }

    @Test
    void pendingSamplingDoesNotNeedCoordinatorWhileCleanupIsBlocked() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CompletableFuture<Void> gate = new CompletableFuture<>();
        MountHandle handle = mountGated("blocked", entered, gate);
        handle.whenSettled().toCompletableFuture().get(10, TimeUnit.SECONDS);
        handle.disposeAsync().toCompletableFuture();
        assertTrue(entered.await(10, TimeUnit.SECONDS));

        PendingOperationsSnapshot snapshot = assertTimeoutPreemptively(
                java.time.Duration.ofSeconds(5), () -> runtime.pendingOperations());
        assertTrue(snapshot.operations().stream()
                .anyMatch(operation -> operation.targetId().equals(handle.handleId())),
                "blocked cleanup must be observable without the coordinator");

        gate.complete(null);
        assertEquals(ComponentState.DISPOSED, handle.whenSettled()
                .toCompletableFuture().get(10, TimeUnit.SECONDS));
    }

    @Test
    void pendingSamplingStaysLiveWhileCoordinatorIsHeld() throws Exception {
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            synchronized (runtime.coordinator) {
                held.countDown();
                try {
                    release.await();
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        holder.start();
        try {
            assertTrue(held.await(10, TimeUnit.SECONDS));
            assertTimeoutPreemptively(java.time.Duration.ofSeconds(5),
                    () -> runtime.pendingOperations());
        } finally {
            release.countDown();
            holder.join(10_000);
        }
    }

    @Test
    void disposePendingToleratesNegativeMonotonicTicker() throws Exception {
        AtomicLong nanos = new AtomicLong(-10_000L);
        try (DefaultKnotraRuntime negative = new DefaultKnotraRuntime(
                KnotraConfig.defaults(), nanos::get)) {
            CountDownLatch entered = new CountDownLatch(1);
            CompletableFuture<Void> gate = new CompletableFuture<>();
            MountHandle handle = negative.transact(transaction -> transaction.mount(
                    negative.root(),
                    "negative",
                    MountFactory.of("negative-factory",
                            ComponentDescriptor.named("negative"),
                            context -> context.lifecycle().onCloseAsync("gate", () -> {
                                entered.countDown();
                                return gate;
                            })))).value();
            handle.whenSettled().toCompletableFuture().get(10, TimeUnit.SECONDS);
            handle.disposeAsync().toCompletableFuture();
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            nanos.set(-9_000L);

            PendingOperationsSnapshot snapshot = assertTimeoutPreemptively(
                    java.time.Duration.ofSeconds(5), () -> negative.pendingOperations());
            PendingOperationsSnapshot.Operation operation = snapshot.operations().stream()
                    .filter(item -> item.targetId().equals(handle.handleId()))
                    .findFirst()
                    .orElseThrow();
            assertTrue(!operation.age().isNegative(),
                    "negative ticker must not produce negative ages");

            gate.complete(null);
            assertEquals(ComponentState.DISPOSED, handle.whenSettled()
                    .toCompletableFuture().get(10, TimeUnit.SECONDS));
        }
    }

    private MountHandle mountGated(
            String mountId,
            CountDownLatch entered,
            CompletableFuture<Void> gate) {
        return mountGatedIn(runtime.root(), mountId, entered, gate);
    }

    private MountHandle mountGatedIn(
            ContextHandle context,
            String mountId,
            CountDownLatch entered,
            CompletableFuture<Void> gate) {
        return runtime.transact(transaction -> transaction.mount(
                context,
                mountId,
                MountFactory.of(mountId + "-factory",
                        ComponentDescriptor.named(mountId),
                        starter -> starter.lifecycle().onCloseAsync("gate", () -> {
                            entered.countDown();
                            return gate;
                        })))).value();
    }

    private static void runConcurrently(int threads, Runnable action) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            for (int i = 0; i < threads; i++) {
                executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    action.run();
                    return null;
                });
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            executor.shutdown();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }
}
