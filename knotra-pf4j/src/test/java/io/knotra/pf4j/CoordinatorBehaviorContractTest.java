package io.knotra.pf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
final class CoordinatorBehaviorContractTest {

    private ArtifactCoordinator coordinator;

    @AfterEach
    void stopCoordinator() {
        if (coordinator != null) {
            coordinator.stop();
        }
    }

    @Test
    void submitsAreSerializedOnOneCoordinatorThread() throws Exception {
        coordinator = new ArtifactCoordinator();
        try {
            CountDownLatch firstEntered = new CountDownLatch(1);
            CompletableFuture<Void> releaseFirst = new CompletableFuture<>();
            List<String> order = new ArrayList<>();
            AtomicReference<Thread> callerThread = new AtomicReference<>();
            AtomicReference<Thread> firstThread = new AtomicReference<>();
            AtomicReference<Thread> secondThread = new AtomicReference<>();

            callerThread.set(Thread.currentThread());
            CompletableFuture<String> first = coordinator.submit(() -> {
                firstThread.set(Thread.currentThread());
                order.add("first");
                firstEntered.countDown();
                releaseFirst.join();
                return "first";
            });
            assertTrue(firstEntered.await(10, TimeUnit.SECONDS));
            CompletableFuture<String> second = coordinator.submit(() -> {
                secondThread.set(Thread.currentThread());
                order.add("second");
                return "second";
            });
            releaseFirst.complete(null);

            assertEquals("first", first.get(10, TimeUnit.SECONDS));
            assertEquals("second", second.get(10, TimeUnit.SECONDS));
            assertEquals(List.of("first", "second"), order);
            assertSame(firstThread.get(), secondThread.get());
            assertNotSame(callerThread.get(), firstThread.get());
        } finally {
            coordinator.stop();
        }
    }

    @Test
    void coordinatorRemainsUsableAfterAnOperationFails() throws Exception {
        coordinator = new ArtifactCoordinator();
        try {
            IllegalStateException failure = new IllegalStateException("operation failed");
            CompletableFuture<String> failed = coordinator.submit(() -> {
                throw failure;
            });

            CompletionException rejected = assertThrows(
                    CompletionException.class, failed::join);
            assertSame(failure, rejected.getCause());
            assertEquals("usable", coordinator.submit(() -> "usable")
                    .get(10, TimeUnit.SECONDS));
        } finally {
            coordinator.stop();
        }
    }

    @Test
    void submissionsAfterStopFailInsteadOfBeingAccepted() throws Exception {
        coordinator = new ArtifactCoordinator();
        try {
            assertEquals("before", coordinator.submit(() -> "before")
                    .get(10, TimeUnit.SECONDS));
            coordinator.stop();

            CompletableFuture<String> rejected = coordinator.submit(() -> "after");
            assertTrue(rejected.isCompletedExceptionally());
            CompletionException error = assertThrows(
                    CompletionException.class, rejected::join);
            assertTrue(error.getCause() instanceof IllegalStateException,
                    () -> String.valueOf(error.getCause()));
            assertTrue(error.getCause().getMessage().contains("stopped"),
                    error.getCause()::getMessage);
        } finally {
            coordinator.stop();
        }
    }

    @Test
    void nestedSubmissionFromCoordinatorThreadExecutesInlineOnSameThread() throws Exception {
        coordinator = new ArtifactCoordinator();
        try {
            AtomicReference<Thread> callerThread = new AtomicReference<>();
            AtomicReference<Thread> outerThread = new AtomicReference<>();
            AtomicReference<Thread> nestedThread = new AtomicReference<>();

            callerThread.set(Thread.currentThread());
            String result = coordinator.submit(() -> {
                outerThread.set(Thread.currentThread());
                return coordinator.submit(() -> {
                    nestedThread.set(Thread.currentThread());
                    return "nested";
                }).join();
            }).get(10, TimeUnit.SECONDS);

            assertEquals("nested", result);
            assertSame(outerThread.get(), nestedThread.get());
            assertNotSame(callerThread.get(), outerThread.get());
        } finally {
            coordinator.stop();
        }
    }
}
