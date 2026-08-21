package com.example.knotra.contract;

import java.util.concurrent.CompletableFuture;

public interface ControlledGate extends AutoCloseable {
    CompletableFuture<Void> release();

    boolean disposed();

    @Override
    default void close() {
        release();
    }
}
