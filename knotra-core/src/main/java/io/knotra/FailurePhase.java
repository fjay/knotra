package io.knotra;

/** 捕获结构化异常诊断（FailureInfo）时所处的生命周期阶段。 */
public enum FailurePhase {
    /** 配置解析或归一化阶段。 */
    CONFIGURATION,
    /** 组件启动激活（Activation）阶段。 */
    ACTIVATION,
    /** 组件生命周期关闭与资源清理阶段。 */
    CLEANUP,
    /** 操作异步传播与排空结算阶段。 */
    SETTLEMENT
}
