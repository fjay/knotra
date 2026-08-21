package com.example.knotra.contract;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class MountCoordinator {

    private static CompletableFuture<Void> entered = new CompletableFuture<>();
    private static CountDownLatch releaseCreate = new CountDownLatch(1);

    private MountCoordinator() {
    }

    public static synchronized void reset() {
        entered = new CompletableFuture<>();
        releaseCreate = new CountDownLatch(1);
    }

    public static void enterCreate() throws InterruptedException {
        entered.complete(null);
        if (!releaseCreate.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("factory create was not released");
        }
    }

    public static CompletableFuture<Void> entered() {
        return entered;
    }

    public static void releaseCreate() {
        releaseCreate.countDown();
    }
}
