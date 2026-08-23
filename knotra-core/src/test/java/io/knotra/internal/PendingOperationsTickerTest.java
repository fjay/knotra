package io.knotra.internal;

import io.knotra.ComponentState;
import io.knotra.MountHandle;
import io.knotra.PendingOperationsSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class PendingOperationsTickerTest {
    private final AtomicLong nanos = new AtomicLong(100);
    private final DefaultKnotraRuntime runtime =
            new DefaultKnotraRuntime(io.knotra.KnotraConfig.defaults(), nanos::get);

    @AfterEach
    void tearDown() {
        runtime.close();
    }

    @Test
    void agesAreCalculatedFromTheInjectedMonotonicTicker() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CompletableFuture<Void> gate = new CompletableFuture<>();
        MountHandle handle = runtime.transact(transaction -> transaction.mount(
                runtime.root(),
                "ticker",
                io.knotra.MountFactory.of(
                        "ticker",
                        io.knotra.ComponentDescriptor.named("ticker"),
                        context -> {
                            entered.countDown();
                            gate.join();
                        }))).value();
        assertEquals(100, nanos.get());
        assertTrue(entered.await(10, TimeUnit.SECONDS));

        PendingOperationsSnapshot first = runtime.pendingOperations();
        PendingOperationsSnapshot.Operation operation = first.operations().stream()
                .filter(item -> item.targetId().equals(handle.handleId()))
                .findFirst()
                .orElseThrow();
        assertEquals(java.time.Duration.ZERO, operation.age());

        nanos.set(142);
        PendingOperationsSnapshot second = runtime.pendingOperations();
        PendingOperationsSnapshot.Operation aged = second.operations().stream()
                .filter(item -> item.targetId().equals(handle.handleId()))
                .findFirst()
                .orElseThrow();
        assertEquals(java.time.Duration.ofNanos(42), aged.age());

        gate.complete(null);
        assertEquals(ComponentState.ACTIVE, handle.whenSettled()
                .toCompletableFuture().get(10, TimeUnit.SECONDS));
    }

    @Test
    void negativeTickerStartsStillReportCloseAndCleanupAges() throws Exception {
        nanos.set(-10_000L);
        CountDownLatch entered = new CountDownLatch(1);
        CompletableFuture<Void> gate = new CompletableFuture<>();
        MountHandle handle = runtime.transact(transaction -> transaction.mount(
                runtime.root(),
                "negative-ticker",
                io.knotra.MountFactory.of(
                        "negative-ticker",
                        io.knotra.ComponentDescriptor.named("negative-ticker"),
                        context -> context.lifecycle().onCloseAsync(
                                "negative disposer", () -> {
                                    entered.countDown();
                                    return gate;
                                })))).value();
        assertEquals(ComponentState.ACTIVE, handle.whenSettled()
                .toCompletableFuture().get(10, TimeUnit.SECONDS));

        CompletableFuture<Void> closed = runtime.closeAsync().toCompletableFuture();
        assertTrue(entered.await(10, TimeUnit.SECONDS));
        nanos.set(-9_000L);
        PendingOperationsSnapshot snapshot = runtime.pendingOperations();

        PendingOperationsSnapshot.Operation closeOperation = snapshot.operations().stream()
                .filter(item -> item.kind() == PendingOperationsSnapshot.Kind.RUNTIME_CLOSE)
                .findFirst()
                .orElseThrow();
        assertEquals(java.time.Duration.ofNanos(1_000L), closeOperation.age());
        PendingOperationsSnapshot.Operation cleanupOperation = snapshot.operations().stream()
                .filter(item -> item.kind() == PendingOperationsSnapshot.Kind.LIFECYCLE_CLEANUP)
                .findFirst()
                .orElseThrow();
        assertEquals(java.time.Duration.ofNanos(1_000L), cleanupOperation.age());

        gate.complete(null);
        closed.get(10, TimeUnit.SECONDS);
        PendingOperationsSnapshot settled = runtime.pendingOperations();
        assertEquals(0, settled.operations().size());
    }

    private static void assertTrue(boolean value) {
        org.junit.jupiter.api.Assertions.assertTrue(value);
    }
}
