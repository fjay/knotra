package io.knotra.internal;

import io.knotra.ComponentState;
import io.knotra.PendingOperationsSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 单组件 dispose 请求注册表。
 *
 * <p>只负责按 handleId 合并并发请求、以 identity CAS 清理已驱动的请求，以及无副作用采样
 * pending 元数据。本类是叶子数据结构：方法体只做 CHM 短计算，不回调 runtime、不获取协调器，
 * 也不持有 handle、component、Class 或 Throwable。</p>
 */
final class DisposeRequestRegistry {
    private final Map<String, Entry> requests = new ConcurrentHashMap<>();

    private record Entry(
            CompletableFuture<ComponentState> future,
            long startNanos) {
    }

    record Registration(
            CompletableFuture<ComponentState> future,
            boolean created) {
    }

    /**
     * 合并仍在执行的请求；已完成（无论成败）的条目按 identity 替换，允许下一次显式调用建立新请求。
     */
    Registration getOrCreate(String handleId, long startNanos) {
        Objects.requireNonNull(handleId, "handleId");
        boolean[] created = {false};
        CompletableFuture<ComponentState> future = requests
                .compute(handleId, (id, existing) -> {
                    if (existing != null && !existing.future().isDone()) {
                        return existing;
                    }
                    created[0] = true;
                    return new Entry(new CompletableFuture<>(), startNanos);
                })
                .future();
        return new Registration(future, created[0]);
    }

    /** 只有仍指向同一 future 时才清理，避免误删并发重试建立的新请求。 */
    boolean remove(String handleId, CompletableFuture<ComponentState> future) {
        Objects.requireNonNull(handleId, "handleId");
        Objects.requireNonNull(future, "future");
        boolean[] removed = {false};
        requests.computeIfPresent(handleId, (id, existing) -> {
            if (existing.future() == future) {
                removed[0] = true;
                return null;
            }
            return existing;
        });
        return removed[0];
    }

    /** 纯采样：复制快照值，不触发 future 注册与完成，也不需要协调器。 */
    List<PendingOperationSample> pending() {
        List<PendingOperationSample> samples = new ArrayList<>();
        for (Map.Entry<String, Entry> mapping : requests.entrySet()) {
            Entry entry = mapping.getValue();
            if (entry.future().isDone()) {
                continue;
            }
            samples.add(new PendingOperationSample(
                    PendingOperationsSnapshot.Kind.COMPONENT_TRANSITION,
                    mapping.getKey(),
                    PendingOperationsSnapshot.WaitType.COMPONENT,
                    entry.startNanos(),
                    "mount dispose waiting for component"));
        }
        return samples;
    }
}
