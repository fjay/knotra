package io.knotra.events;

import io.knotra.PendingOperationsSnapshot;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * 一次已接受分发的纯诊断元数据。
 *
 * <p>除收敛 future 外只保存稳定字符串：元数据在 accept 写锁内完成字符串化，
 * 不引用事件值、监听对象、事件 Class 或 ClassLoader，因此可在分发收敛前安全保留。</p>
 */
record AcceptedDispatch(
        String dispatchId,
        String eventName,
        String eventTypeName,
        EventMode mode,
        List<String> subscriptionIds,
        long acceptedNanos,
        CompletableFuture<Void> settled) {

    AcceptedDispatch {
        dispatchId = requireText(dispatchId, "dispatchId");
        eventName = requireText(eventName, "eventName");
        eventTypeName = requireText(eventTypeName, "eventTypeName");
        Objects.requireNonNull(mode, "mode");
        subscriptionIds = List.copyOf(subscriptionIds);
        Objects.requireNonNull(settled, "settled");
    }

    PendingOperationsSnapshot.Operation toOperation(long nowNanos) {
        return new PendingOperationsSnapshot.Operation(
                PendingOperationsSnapshot.Kind.EVENT_DISPATCH,
                dispatchId,
                PendingOperationsSnapshot.WaitType.LISTENER,
                Duration.ofNanos(elapsedNanos(nowNanos)),
                "event=" + eventName
                        + " type=" + eventTypeName
                        + " mode=" + mode
                        + " listeners=" + subscriptionIds.size());
    }

    long elapsedNanos(long nowNanos) {
        return Math.max(0L, nowNanos - acceptedNanos);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        return value;
    }
}
