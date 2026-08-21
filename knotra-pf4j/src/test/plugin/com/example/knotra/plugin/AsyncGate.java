package com.example.knotra.plugin;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import com.example.knotra.contract.ControlledGate;

/**
 * Gated cleanup contract. The plugin-side disposer waits on {@link #released()};
 * any host call to {@link #release()} opens the gate and lets cleanup finish.
 */
final class AsyncGate implements ControlledGate {

    private final CompletableFuture<Void> gate = new CompletableFuture<>();
    private final AtomicBoolean disposed = new AtomicBoolean();

    @Override
    public CompletableFuture<Void> release() {
        gate.complete(null);
        return gate;
    }

    @Override
    public boolean disposed() {
        return disposed.get();
    }

    CompletableFuture<Void> released() {
        return gate;
    }

    void markDisposed() {
        disposed.set(true);
    }
}
