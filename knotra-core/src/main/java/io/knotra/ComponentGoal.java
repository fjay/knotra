package io.knotra;

/**
 * 组件的期望目标，与 {@link ComponentState} 表示的瞬时状态分离。
 *
 * <p>目标由宿主事务声明，Runtime 据此驱动收敛。
 */
public enum ComponentGoal {
    /** 组件应保持运行：依赖满足时自动启动，绑定或配置变化时重新激活。 */
    RUNNING,
    /** 组件已请求释放：当前激活停止后不再重启，最终进入 DISPOSED 终态。 */
    DISPOSED
}
