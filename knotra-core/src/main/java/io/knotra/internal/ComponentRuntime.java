package io.knotra.internal;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;

final class ComponentRuntime {
    final String handleId;
    final String contextId;
    final String mountId;
    final PreparedComponent<?> prepared;

    volatile Object desiredConfig;
    volatile long desiredRevision = 1;
    volatile ActivationRuntime current;
    volatile ActivationRuntime failedCleanup;
    volatile boolean pendingStartFailure;
    volatile boolean suppressAutoRestart;
    volatile boolean blockedNonConvergent;
    volatile String lastStartError = "";
    volatile String lastCleanupError = "";
    volatile String reconcileFingerprint = "";
    volatile int reconcileAttempts;

    private final Object chainLock = new Object();
    private final AtomicReference<CompletableFuture<io.knotra.ComponentState>> transition =
            new AtomicReference<>();

    ComponentRuntime(
            String handleId,
            String contextId,
            String mountId,
            PreparedComponent<?> prepared) {
        this.handleId = handleId;
        this.contextId = contextId;
        this.mountId = mountId;
        this.prepared = prepared;
        this.desiredConfig = prepared.config();
    }

    CompletableFuture<io.knotra.ComponentState> enqueue(
            DefaultKnotraRuntime runtime,
            ExecutorService executor) {
        Reservation reservation = reserveTransition();
        if (reservation.created()) {
            reservation.component().executeReserved(
                    runtime,
                    executor,
                    reservation.future());
        }
        return reservation.future();
    }
    Reservation reserveTransition() {
        synchronized (chainLock) {
            CompletableFuture<io.knotra.ComponentState> existing = transition.get();
            if (existing != null && !existing.isDone()) {
                return new Reservation(this, existing, false);
            }
            CompletableFuture<io.knotra.ComponentState> created = new CompletableFuture<>();
            transition.set(created);
            return new Reservation(this, created, true);
        }
    }

    void executeReserved(
            DefaultKnotraRuntime runtime,
            ExecutorService executor,
            CompletableFuture<io.knotra.ComponentState> future) {
        synchronized (chainLock) {
            if (transition.get() != future) {
                return;
            }
        }
        executor.execute(() -> runtime.driveTransition(handleId, future));
    }


    void cancelTransition(CompletableFuture<io.knotra.ComponentState> future) {
        synchronized (chainLock) {
            transition.compareAndSet(future, null);
        }
    }

    void failTransition(
            CompletableFuture<io.knotra.ComponentState> future,
            Throwable error) {
        synchronized (chainLock) {
            transition.compareAndSet(future, null);
            future.completeExceptionally(error);
        }
    }

    Reservation replaceTransition() {
        synchronized (chainLock) {
            CompletableFuture<io.knotra.ComponentState> created = new CompletableFuture<>();
            transition.set(created);
            return new Reservation(this, created, true);
        }
    }

    void finishTransition(CompletableFuture<io.knotra.ComponentState> future) {
        synchronized (chainLock) {
            transition.compareAndSet(future, null);
        }
    }

    void finishTransition(
            CompletableFuture<io.knotra.ComponentState> future,
            io.knotra.ComponentState state) {
        synchronized (chainLock) {
            transition.compareAndSet(future, null);
            future.complete(state);
        }
    }

    void updateConfig(Object config, long revision) {
        this.desiredConfig = config;
        this.desiredRevision = revision;
    }

    record Reservation(
            ComponentRuntime component,
            CompletableFuture<io.knotra.ComponentState> future,
            boolean created) {
    }
}
