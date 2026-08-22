package com.example.knotra.plugin;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import com.example.knotra.contract.ControlledGate;

/**
 * 门禁清理契约。插件端释放器等待 {@link #released()}；
 * 任何宿主对 {@link #release()} 的调用都会开启门禁并允许清理完成。
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
