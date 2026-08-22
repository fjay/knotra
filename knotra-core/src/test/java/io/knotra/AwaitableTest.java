package io.knotra;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class AwaitableTest {
    @Test
    void boundedWaitTimesOutWithoutCancellingTheUnderlyingFuture() {
        CompletableFuture<String> future = new CompletableFuture<>();
        Awaitable<String> awaitable = () -> future;
        SettlementAwaitException error = assertThrows(
                SettlementAwaitException.class,
                () -> awaitable.awaitSettled(Duration.ofMillis(20)));
        assertEquals(SettlementAwaitException.Reason.TIMEOUT, error.reason());
        assertFalse(future.isDone());
        future.complete("late");
        assertEquals("late", awaitable.awaitSettled(Duration.ofSeconds(1)));
    }

    @Test
    void interruptedWaitRestoresTheThreadInterruptFlag() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Thread current = Thread.currentThread();
        AtomicReference<Thread> waiter = new AtomicReference<>();
        try {
            CompletableFuture<String> future = new CompletableFuture<>();
            Awaitable<String> awaitable = () -> future;
            var ignored = executor.submit(() -> {
                waiter.updateAndGet(existing -> existing == null ? current : existing);
                try {
                    Thread.sleep(20);
                } catch (InterruptedException ignoredInterrupt) {
                    Thread.currentThread().interrupt();
                }
                current.interrupt();
            });
            SettlementAwaitException error = assertThrows(
                    SettlementAwaitException.class,
                    () -> awaitable.awaitSettled());
            assertEquals(SettlementAwaitException.Reason.INTERRUPTED, error.reason());
            assertTrue(Thread.interrupted());
            future.complete("done");
            assertEquals("done", awaitable.awaitSettled(Duration.ofSeconds(1)));
        } finally {
            executor.shutdown();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void nonPositiveDurationsAreRejected() {
        Awaitable<String> awaitable = () -> CompletableFuture.completedFuture("done");
        assertThrows(IllegalArgumentException.class,
                () -> awaitable.awaitSettled(Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> awaitable.awaitSettled(Duration.ofSeconds(-1)));
    }

    @Test
    void failedSettlementIsTranslatedToAStableUncheckedException() {
        CompletableFuture<String> future = new CompletableFuture<>();
        future.completeExceptionally(new IllegalStateException("kernel failed"));
        Awaitable<String> awaitable = () -> future;
        SettlementAwaitException error = assertThrows(
                SettlementAwaitException.class,
                () -> awaitable.awaitSettled(Duration.ofSeconds(1)));
        assertEquals(SettlementAwaitException.Reason.FAILED, error.reason());
        assertTrue(error.getMessage().contains("java.lang.IllegalStateException: kernel failed"));
    }
}
