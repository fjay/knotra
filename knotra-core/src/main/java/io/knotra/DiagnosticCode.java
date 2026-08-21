package io.knotra;

/**
 * 内核诊断码，供告警与收敛逻辑使用。
 *
 * <p>诊断码是稳定枚举，消息文本可能随实现调整，消费方应依据代码而非消息编程。
 */
public enum DiagnosticCode {
    /** 必需 Capability 缺失，组件保持 WAITING。 */
    MISSING_CAPABILITY,
    /** 目标 Context 中同名 Capability 已被占用。 */
    CAPABILITY_SLOT_OCCUPIED,
    /** 同名 Capability 与已固化的合约 Java 类型冲突。 */
    CAPABILITY_TYPE_CONFLICT,
    /** 激活产生的依赖图存在环，自动重启被抑制。 */
    BINDING_CYCLE,
    /** 组件启动失败。 */
    ACTIVATION_FAILED,
    /** 激活提交或回滚流程失败。 */
    ROLLBACK_FAILED,
    /** LifecycleScope 受管条目清理失败。 */
    CLEANUP_FAILED,
    /** 自动收敛在配置的最大迭代次数内未收敛。 */
    NON_CONVERGENT_RECONCILE,
    /** 生命周期操作非法（如目标已释放、事务参数无效或运行时正在关闭）。 */
    INVALID_LIFECYCLE_OPERATION,
    /** 挂载 ID 缺失或已被占用。 */
    INVALID_MOUNT_ID,
    /** 配置不符合工厂 schema；当前为保留码。 */
    INVALID_CONFIG
}
