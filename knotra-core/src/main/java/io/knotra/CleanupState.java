package io.knotra;

/**
 * LifecycleScope 中单个受管条目的清理状态。
 *
 * <p>清理失败的条目保持 FAILED 并保留错误文本，后续清理可重试。
 */
public enum CleanupState {
    /** 已登记尚未清理，或一次清理尝试正在执行。 */
    PENDING,
    /** 清理成功，条目持有的资源已释放。 */
    SUCCEEDED,
    /** 最近一次清理失败，错误保留在 {@link ManagedHandle} 中，可重试。 */
    FAILED
}
