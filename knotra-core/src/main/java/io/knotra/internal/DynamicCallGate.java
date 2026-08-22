package io.knotra.internal;

import java.util.concurrent.CompletableFuture;

/**
 * 一个 Activation 的动态调用准入闸门。
 *
 * <p>Activation 停止时先关闭闸门并等待已开始的动态调用归零，然后才允许 LifecycleScope teardown。</p>
 */
final class DynamicCallGate {

    // closed 与 active 必须在同一个临界区内线性化，否则 drain 完成后仍可能漏进迟到调用。
    private boolean closed;
    private int active;
    private final CompletableFuture<Void> drained = new CompletableFuture<>();

    boolean tryAcquire() {
        synchronized (this) {
            if (closed) {
                return false;
            }
            active++;
            return true;
        }
    }

    void release() {
        boolean completeDrain;
        synchronized (this) {
            active--;
            completeDrain = closed && active == 0;
        }
        if (completeDrain) {
            drained.complete(null);
        }
    }

    CompletableFuture<Void> close() {
        boolean completeDrain;
        synchronized (this) {
            closed = true;
            completeDrain = active == 0;
        }
        if (completeDrain) {
            drained.complete(null);
        }
        return drained;
    }

    boolean isClosed() {
        synchronized (this) {
            return closed;
        }
    }
}
