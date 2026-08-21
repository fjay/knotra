package io.knotra;

/**
 * Context 节点的生命周期状态。
 */
public enum ContextState {
    /** 活跃：可挂载组件、创建子 Context 并发布 Capability。 */
    ACTIVE,
    /** 释放流程进行中：子树内组件正在停止，宿主注册已撤销。 */
    DISPOSING,
    /** 终态：子树清理成功并已从视图移除（根 Context 保留为 DISPOSED）。 */
    DISPOSED,
    /** 终态：子树内存在清理失败的组件，修复后可重试释放。 */
    FAILED
}
