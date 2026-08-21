package com.example.knotra.contract;

public final class CleanupCoordinator {

    private static boolean failNext;

    private CleanupCoordinator() {
    }

    public static synchronized void reset() {
        failNext = false;
    }

    public static synchronized void failNextCleanup() {
        failNext = true;
    }

    public static synchronized void allowCleanup() {
        failNext = false;
    }

    public static synchronized boolean shouldFailAndClear() {
        return failNext;
    }
}
