package io.knotra.pf4j;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Serializes all PF4J mutations and PF4J-facing reads.
 *
 * <p>Nested submissions from a coordinator callback run inline under the
 * reentrant lock, so artifact callbacks can safely inspect the catalog.</p>
 */
final class ArtifactCoordinator {

    private final ExecutorService executor;
    private final ReentrantLock mutationLock = new ReentrantLock();
    private final Object lifecycleLock = new Object();
    private final AtomicReference<Thread> coordinatorThread = new AtomicReference<>();
    private volatile boolean stopped;

    ArtifactCoordinator() {
        this.executor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "knotra-pf4j-artifact-coordinator");
            thread.setDaemon(true);
            return thread;
        });
    }

    <T> CompletableFuture<T> submit(Supplier<T> operation) {
        synchronized (lifecycleLock) {
            if (stopped) {
                return CompletableFuture.failedFuture(stopped());
            }
            if (isCoordinatorThread()) {
                return inline(operation);
            }
            try {
                return CompletableFuture.supplyAsync(
                        () -> asCoordinatorThread(operation), executor);
            } catch (RejectedExecutionException failure) {
                return CompletableFuture.failedFuture(stopped());
            }
        }
    }

    CompletableFuture<Void> execute(Runnable operation) {
        return submit(() -> {
            operation.run();
            return null;
        }).thenApply(ignored -> null);
    }

    void stop() {
        synchronized (lifecycleLock) {
            stopped = true;
            executor.shutdown();
        }
    }

    boolean isStopped() {
        return stopped;
    }

    private boolean isCoordinatorThread() {
        return Thread.currentThread() == coordinatorThread.get();
    }

    private <T> T asCoordinatorThread(Supplier<T> operation) {
        Thread current = Thread.currentThread();
        coordinatorThread.compareAndSet(null, current);
        if (coordinatorThread.get() != current) {
            throw new IllegalStateException("artifact coordinator executor changed threads");
        }
        return locked(operation);
    }

    private <T> CompletableFuture<T> inline(Supplier<T> operation) {
        try {
            return CompletableFuture.completedFuture(locked(operation));
        } catch (Throwable failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    private <T> T locked(Supplier<T> operation) {
        mutationLock.lock();
        try {
            return operation.get();
        } finally {
            mutationLock.unlock();
        }
    }

    private IllegalStateException stopped() {
        return new IllegalStateException("artifact coordinator is stopped");
    }
}
