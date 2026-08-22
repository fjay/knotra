package io.knotra;

/** 稳定能力发布插槽（Publication）的生命周期状态枚举。 */
public enum PublicationState {
    /** 活跃已发布状态，可正常提供能力并支持原子热更新。 */
    PUBLISHED,
    /** 主动撤销终态。 */
    UNPUBLISHED,
    /** 因外部替换、上下文销毁或运行时关闭而被淘汰的终态。 */
    DISPLACED
}
