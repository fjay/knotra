package io.knotra.internal;

import io.knotra.PendingOperationsSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Context 处置请求注册表。
 *
 * <p>取代原先 {@code contextFutures} 与 {@code pendingContextDisposals} 两张表：future 与
 * start 元数据合并为单条 CHM 记录，getOrCreate 的合并判断与元数据写入在一次
 * {@code compute} 内原子完成，消除协调器内嵌套第二把监视器的锁顺序。条目异常完成后可被
 * 同 key 替换，支撑 FAILED Context 的显式重试语义。</p>
 *
 * <p>本类是叶子数据结构：方法体只做 CHM 短计算，不回调 runtime、不获取协调器，也不持有
 * handle、component、Class 或 Throwable。</p>
 */
final class ContextDisposalRequestRegistry {
    private final Map<String, Entry> requests = new ConcurrentHashMap<>();

    private record Entry(
            CompletableFuture<Void> future,
            long startNanos) {
    }

    record Registration(
            CompletableFuture<Void> future,
            boolean created) {
    }

    /**
     * 合并尚未异常完成的请求；异常完成的条目允许替换，使失败后的显式重试获得新 future。
     */
    Registration getOrCreate(String contextId, long startNanos) {
        Objects.requireNonNull(contextId, "contextId");
        boolean[] created = {false};
        CompletableFuture<Void> future = requests
                .compute(contextId, (id, existing) -> {
                    if (existing != null
                            && !existing.future().isCompletedExceptionally()) {
                        return existing;
                    }
                    created[0] = true;
                    return new Entry(new CompletableFuture<>(), startNanos);
                })
                .future();
        return new Registration(future, created[0]);
    }

    /** 只有仍指向同一 future 时才清理，避免误删并发重试建立的新请求。 */
    boolean remove(String contextId, CompletableFuture<Void> future) {
        Objects.requireNonNull(contextId, "contextId");
        Objects.requireNonNull(future, "future");
        boolean[] removed = {false};
        requests.computeIfPresent(contextId, (id, existing) -> {
            if (existing.future() == future) {
                removed[0] = true;
                return null;
            }
            return existing;
        });
        return removed[0];
    }

    /**
     * Context 最终化时清空子树内的去重条目。future 的完成由各自驱动方持有引用，
     * 从表中移除只影响后续合并，不孤立任何请求。
     */
    void removeAll(Set<String> contextIds) {
        Objects.requireNonNull(contextIds, "contextIds");
        for (String contextId : contextIds) {
            requests.remove(contextId);
        }
    }

    /** 纯采样：复制快照值，不触发 future 注册与完成，也不需要协调器。 */
    List<PendingOperationSample> pending() {
        List<PendingOperationSample> samples = new ArrayList<>();
        for (Map.Entry<String, Entry> mapping : requests.entrySet()) {
            Entry entry = mapping.getValue();
            samples.add(new PendingOperationSample(
                    PendingOperationsSnapshot.Kind.CONTEXT_DISPOSAL,
                    mapping.getKey(),
                    PendingOperationsSnapshot.WaitType.CONTEXT,
                    entry.startNanos(),
                    "context disposal"));
        }
        return samples;
    }
}
