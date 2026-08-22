package io.knotra;

/**
 * LifecycleScope 中单个受管条目的只读观察句柄。
 *
 * <p>用于查询清理进度与失败详情；清理失败的条目保持 FAILED 并保留有界错误文本，
 * 可通过重新触发清理（如 {@link MountHandle#retryAsync()}）重试。
 */
public interface ManagedHandle {
    String entryId();

    String description();

    CleanupState state();

    /** 返回该条目已执行的清理尝试次数。 */
    int attempts();

    /** 返回最近一次清理失败的错误文本；无失败时为空字符串。 */
    String lastError();
}
