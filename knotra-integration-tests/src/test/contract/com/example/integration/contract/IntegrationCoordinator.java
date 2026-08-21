package com.example.integration.contract;

import java.lang.ref.WeakReference;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Host/plugin coordination contract. The class is loaded from the host class loader by
 * both sides, so its static state is shared across the artifact boundary.
 */
public final class IntegrationCoordinator {

    private static final Set<WeakReference<ClassLoader>> loaders =
            ConcurrentHashMap.newKeySet();

    private static CompletableFuture<Void> eventEntered = new CompletableFuture<>();
    private static CompletableFuture<Boolean> eventGate = new CompletableFuture<>();
    private static final AtomicInteger eventDeliveries = new AtomicInteger();

    private static CompletableFuture<Void> mountEntered = new CompletableFuture<>();
    private static CountDownLatch mountRelease = new CountDownLatch(1);

    private static volatile boolean failNextCleanup;

    private IntegrationCoordinator() {
    }

    public static synchronized void reset() {
        eventEntered = new CompletableFuture<>();
        eventGate = new CompletableFuture<>();
        eventDeliveries.set(0);
        mountEntered = new CompletableFuture<>();
        mountRelease = new CountDownLatch(1);
        failNextCleanup = false;
    }

    public static void remember(ClassLoader loader) {
        loaders.add(new WeakReference<>(loader));
    }

    public static void clearLoaders() {
        loaders.clear();
    }

    public static long liveLoaders() {
        loaders.removeIf(reference -> reference.get() == null);
        return loaders.stream()
                .map(WeakReference::get)
                .filter(loader -> loader != null)
                .count();
    }

    public static void enterEvent() {
        eventEntered.complete(null);
    }

    public static CompletableFuture<Void> eventEntered() {
        return eventEntered;
    }

    public static CompletableFuture<Boolean> eventGate() {
        return eventGate;
    }

    public static void releaseEvent() {
        eventGate.complete(true);
    }

    public static int recordDelivery() {
        return eventDeliveries.incrementAndGet();
    }

    public static int eventDeliveries() {
        return eventDeliveries.get();
    }

    public static void enterMount() {
        mountEntered.complete(null);
    }

    public static void awaitMountRelease() {
        try {
            if (!mountRelease.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("integration mount was never released");
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("integration mount wait was interrupted", error);
        }
    }

    public static CompletableFuture<Void> mountEntered() {
        return mountEntered;
    }

    public static void releaseMount() {
        mountRelease.countDown();
    }

    public static void failNextCleanup() {
        failNextCleanup = true;
    }

    public static void allowCleanup() {
        failNextCleanup = false;
    }

    public static boolean shouldFailAndClearCleanup() {
        return failNextCleanup;
    }
}
