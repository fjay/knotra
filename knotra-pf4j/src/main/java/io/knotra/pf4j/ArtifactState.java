package io.knotra.pf4j;

/** Knotra 视角下受管 PF4J artifact 的生命周期状态。 */
public enum ArtifactState {
    /** artifact 已启动并发布工厂，当前接受类型化受控挂载。 */
    ACTIVE,
    /** 卸载流程进行中：停止接受新挂载，等待执行中挂载并 dispose owned handle。 */
    DRAINING,
    /** drain 或 PF4J 卸载失败；诊断与资源保留，修复后可通过 retryDrain 收敛。 */
    DRAIN_FAILED,
    /** 加载或启动失败且已回滚；保留结构化失败诊断。 */
    FAILED,
    /** 成功完成 drain，PF4J 插件已停止并卸载，插件 ClassLoader 可被回收。 */
    UNLOADED
}
