package io.knotra.internal;

import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;

/**
 * 一个 Activation 的动态调用准入闸门。
 *
 * <p>Activation 停止时先关闭闸门并等待已开始的动态调用归零，然后才允许 LifecycleScope teardown。</p>
 */
final class DynamicCallGate {

    // closed 与 active 必须在同一个临界区内线性化，否则 drain 完成后仍可能漏进迟到调用。
    private boolean closed;
    private int active;
    private long oldestActiveNanos;
    private boolean oldestActivePresent;
    private final CompletableFuture<Void> drained = new CompletableFuture<>();
    private final LongSupplier ticker;

    DynamicCallGate(LongSupplier ticker) {
        this.ticker = ticker;
    }

    boolean tryAcquire() {
        synchronized (this) {
            if (closed) {
                return false;
            }
            if (active == 0) {
                oldestActiveNanos = ticker.getAsLong();
                oldestActivePresent = true;
            }
            active++;
            return true;
        }
    }

    void release() {
        boolean completeDrain;
        synchronized (this) {
            active--;
            if (active == 0) {
                oldestActivePresent = false;
            }
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

    ActiveSnapshot pendingSnapshot() {
        synchronized (this) {
            return new ActiveSnapshot(
                    active, closed, oldestActivePresent, oldestActiveNanos);
        }
    }

    // started 独立于计数表达时间戳存在性，避免假设 System.nanoTime 非负。
    record ActiveSnapshot(
            int active,
            boolean draining,
            boolean started,
            long startNanos) {
    }
}
