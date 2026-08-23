package io.knotra;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ContextDisposalConcurrencyTest {
    private KnotraRuntime runtime = KnotraRuntime.create();

    @AfterEach
    void tearDown() {
        runtime.close();
    }

    @Test
    void parentDisposalCompletesAlreadyRegisteredChildFuture() throws Exception {
        for (int round = 0; round < 100; round++) {
            ContextHandle parent = runtime.advanced().childContext(
                    runtime.root(), "parent-" + round);
            ContextHandle child = runtime.advanced().childContext(
                    parent, "child-" + round);
            CountDownLatch cleanupEntered = new CountDownLatch(1);
            CountDownLatch releaseCleanup = new CountDownLatch(1);

            MountHandle blocked = TestKit.mount(
                    runtime,
                    child,
                    "blocked-cleanup",
                    (context, config) -> context.lifecycle().onClose(
                            "blocked", () -> {
                                cleanupEntered.countDown();
                                try {
                                    releaseCleanup.await();
                                } catch (InterruptedException error) {
                                    Thread.currentThread().interrupt();
                                }
                            }));
            blocked.requireActive(java.time.Duration.ofSeconds(5));

            java.util.concurrent.CyclicBarrier start = new java.util.concurrent.CyclicBarrier(2);
            java.util.concurrent.atomic.AtomicReference<CompletableFuture<ContextState>> childDisposal =
                    new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.atomic.AtomicReference<CompletableFuture<ContextState>> parentDisposal =
                    new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.ExecutorService executor =
                    java.util.concurrent.Executors.newFixedThreadPool(2);
            try {
                executor.submit(() -> {
                    start.await(5, TimeUnit.SECONDS);
                    childDisposal.set(child.disposeAsync().toCompletableFuture());
                    return null;
                });
                executor.submit(() -> {
                    start.await(5, TimeUnit.SECONDS);
                    parentDisposal.set(parent.disposeAsync().toCompletableFuture());
                    return null;
                });
                assertTrue(cleanupEntered.await(5, TimeUnit.SECONDS));
                long referenceDeadline = System.nanoTime()
                        + TimeUnit.SECONDS.toNanos(5);
                while (childDisposal.get() == null
                        && System.nanoTime() < referenceDeadline) {
                    Thread.sleep(1);
                }
                assertNotNull(childDisposal.get(), "round " + round);
                releaseCleanup.countDown();

                assertEquals(ContextState.DISPOSED,
                        childDisposal.get().get(3, TimeUnit.SECONDS), "round " + round);
                long parentReferenceDeadline = System.nanoTime()
                        + TimeUnit.SECONDS.toNanos(5);
                while (parentDisposal.get() == null
                        && System.nanoTime() < parentReferenceDeadline) {
                    Thread.sleep(1);
                }
                assertNotNull(parentDisposal.get(), "round " + round);
                assertEquals(ContextState.DISPOSED,
                        parentDisposal.get().get(3, TimeUnit.SECONDS), "round " + round);
            } finally {
                executor.shutdown();
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
    }
}
