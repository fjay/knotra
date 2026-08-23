package io.knotra.internal;

import io.knotra.ComponentState;
import io.knotra.PendingOperationsSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.ref.WeakReference;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DisposeRequestRegistryTest {
    private final DefaultKnotraRuntime runtime =
            new DefaultKnotraRuntime(io.knotra.KnotraConfig.defaults(), System::nanoTime);

    @AfterEach
    void tearDown() {
        runtime.close();
    }

    @Test
    void concurrentRequestsForSameHandleMergeIntoOneFuture() throws Exception {
        DisposeRequestRegistry registry = new DisposeRequestRegistry();
        int threads = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            CompletableFuture<ComponentState>[] results = new CompletableFuture[threads];
            AtomicInteger cursor = new AtomicInteger();
            for (int i = 0; i < threads; i++) {
                executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    results[cursor.getAndIncrement()] =
                            registry.getOrCreate("handle-1", 100L).future();
                    return null;
                });
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            executor.shutdown();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));

            Set<CompletableFuture<ComponentState>> distinct = new HashSet<>();
            for (CompletableFuture<ComponentState> future : results) {
                assertTrue(future != null, "every caller must receive a future");
                distinct.add(future);
            }
            assertEquals(1, distinct.size(), "all concurrent disposals must share one future");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void completedRequestIsReplacedByNewRequest() {
        DisposeRequestRegistry registry = new DisposeRequestRegistry();
        DisposeRequestRegistry.Registration first = registry.getOrCreate("h", 10L);
        assertTrue(first.created());
        first.future().complete(ComponentState.DISPOSED);

        DisposeRequestRegistry.Registration second = registry.getOrCreate("h", 20L);
        assertTrue(second.created());
        assertNotSame(first.future(), second.future());
    }

    @Test
    void removeUsesFutureIdentityAndDoesNotEvictNewerRequest() {
        DisposeRequestRegistry registry = new DisposeRequestRegistry();
        CompletableFuture<ComponentState> stale = registry.getOrCreate("h", 10L).future();
        stale.completeExceptionally(new IllegalStateException("old failure"));

        DisposeRequestRegistry.Registration current = registry.getOrCreate("h", 20L);
        assertTrue(current.created());

        assertFalse(registry.remove("h", stale), "stale future must not evict the new request");
        assertEquals(1, registry.pending().size());

        assertTrue(registry.remove("h", current.future()));
        assertEquals(0, registry.pending().size());
    }

    @Test
    void pendingSkipsDoneFuturesAndClampsNegativeTickerAges() {
        DisposeRequestRegistry registry = new DisposeRequestRegistry();
        CompletableFuture<ComponentState> done = registry.getOrCreate("done", -500L).future();
        done.complete(ComponentState.DISPOSED);
        registry.getOrCreate("blocked", -100L);

        List<PendingOperationSample> samples = registry.pending();
        assertEquals(1, samples.size());
        PendingOperationSample sample = samples.getFirst();
        assertEquals("blocked", sample.targetId());
        assertEquals(PendingOperationsSnapshot.Kind.COMPONENT_TRANSITION, sample.kind());

        PendingOperationsSnapshot.Operation operation = sample.toOperation(-200L);
        assertEquals(java.time.Duration.ZERO, operation.age(),
                "negative elapsed must clamp to zero");
    }

    @Test
    void registryDoesNotPinCompletedFutureAfterRemoval() throws Exception {
        DisposeRequestRegistry registry = new DisposeRequestRegistry();
        CompletableFuture<ComponentState> future = registry.getOrCreate("h", 1L).future();
        WeakReference<CompletableFuture<ComponentState>> reference = new WeakReference<>(future);
        assertTrue(registry.remove("h", future));
        future = null;

        for (int i = 0; i < 50 && reference.get() != null; i++) {
            System.gc();
            Thread.sleep(10);
        }
        assertNull(reference.get(), "registry must not keep the future alive after removal");
    }

    @Test
    void registryOperationsRemainLiveWhileCoordinatorIsHeld() throws Exception {
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
            DisposeRequestRegistry registry = new DisposeRequestRegistry();
            assertSame(registry.getOrCreate("h", 1L).future(),
                    registry.getOrCreate("h", 1L).future());
            assertEquals(1, registry.pending().size());
            // pendingOperations 也不能依赖协调器，否则会被任意持有者饿死。
            runtime.pendingOperations();
        } finally {
            release.countDown();
            holder.join(10_000);
        }
    }
}
