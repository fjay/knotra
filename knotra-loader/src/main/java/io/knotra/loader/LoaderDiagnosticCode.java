package io.knotra.loader;

/**
 * Loader 收敛过程使用的结构化诊断码。
 *
 * <p>诊断码面向告警与协调逻辑消费，语义与 Core 的 DiagnosticCode 对齐：
 * Loader 会尽可能保留 Core 事务的拒绝原因并映射到这些枚举值，
 * 而不是把问题压平成一条异常消息。
 */
public enum LoaderDiagnosticCode {
    /** 工厂引用解析失败：解析器未返回实现，或解析过程抛出异常。 */
    RESOLUTION_FAILED,
    /** Raw decoder 或 Core typed normalizer 拒绝配置。 */
    CONFIG_INVALID,
    /** 期望树结构非法：路径为空、越界、重复、缺少父条目等。 */
    INVALID_TREE,
    /** 基础 Context 不可用：不属于运行时、状态不是 ACTIVE 或已释放。 */
    BASE_UNAVAILABLE,
    /** 目标路径上的 Context 或挂载 ID 已被其他所有者占用；Loader 不认领外来结构。 */
    CONTEXT_CONFLICT,
    /** 运行时结构事务被拒绝（受控挂载、Context 创建、分配槽位校验失败等）。 */
    STRUCTURE_REJECTED,
    /** 组件或 Context 清理未收敛到 DISPOSED，条目保持可重试。 */
    TEARDOWN_FAILED,
    /** 工厂替换被拒绝，旧实现已做补偿性恢复。 */
    REPLACEMENT_BLOCKED,
    /** 替换或新增的回滚（补偿）自身失败，可能存在残余资源。 */
    COMPENSATION_FAILED,
    /** 组件 Activation 失败或重配置后处于 FAILED，需要显式 retry。 */
    ACTIVATION_FAILED,
    /** Loader 已关闭，操作被快速拒绝。 */
    CLOSED
}
