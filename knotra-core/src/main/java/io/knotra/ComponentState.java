package io.knotra;

/**
 * 组件挂载点的瞬时状态，与 {@link ComponentGoal} 表示的期望目标分离。
 *
 * <p>状态由 Runtime 异步驱动，宿主应通过 {@link MountHandle#whenSettled()} 等待收敛。
 */
public enum ComponentState {
    /** 已挂载但尚未启动：等待必需依赖满足或过渡调度。 */
    WAITING,
    /** 启动事务进行中。 */
    STARTING,
    /** 当前 Activation 处于活跃状态。 */
    ACTIVE,
    /** 正在停止当前 Activation，等待清理收敛。 */
    STOPPING,
    /** 启动或清理失败；保留诊断，可通过 {@link MountHandle#retryAsync()} 重试。 */
    FAILED,
    /** 终态：组件已释放并从运行时移除。 */
    DISPOSED
}
