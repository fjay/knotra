package io.knotra;

/**
 * LifecycleScope 的状态。
 */
public enum LifecycleState {
    /** 开放：可登记条目、创建子 Scope。 */
    OPEN,
    /** 释放进行中：不再接受新条目，清理按 LIFO 与并行组语义执行。 */
    STOPPING,
    /** 终态：释放完成，但存在清理失败的条目。 */
    FAILED,
    /** 终态：全部条目清理成功。 */
    SUCCEEDED
}
