package io.knotra.internal;

import io.knotra.PendingOperationsSnapshot;
import java.time.Duration;

/** 叶子锁内复制的挂起操作纯值；只允许字符串、枚举与单调时钟时间戳。 */
record PendingOperationSample(
        PendingOperationsSnapshot.Kind kind,
        String targetId,
        PendingOperationsSnapshot.WaitType waitsFor,
        long startNanos,
        String detail) {

    PendingOperationSample {
        if (kind == null || targetId == null || waitsFor == null || detail == null) {
            throw new IllegalArgumentException("pending operation sample requires stable values");
        }
    }

    PendingOperationsSnapshot.Operation toOperation(long nowNanos) {
        long elapsed = nowNanos - startNanos;
        return new PendingOperationsSnapshot.Operation(
                kind,
                targetId,
                waitsFor,
                Duration.ofNanos(Math.max(0L, elapsed)),
                detail);
    }
}
