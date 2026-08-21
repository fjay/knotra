package io.knotra.loader;

import java.util.concurrent.CompletionStage;

import io.knotra.ComponentHandle;
import io.knotra.ComponentState;

/**
 * 重配置策略：把已归一化的配置应用到既有 ComponentHandle。
 *
 * <p>当条目的实现身份不变、仅配置变化时，Loader 走该策略复用既有句柄，
 * 避免不必要的卸载与重挂载。策略返回完成后的组件状态；执行被拒绝或
 * 最终状态为 FAILED 都会记为 ACTIVATION_FAILED 诊断，重试由调用方显式触发。
 */
@FunctionalInterface
public interface ReconfigureStrategy {

    /**
     * 对既有句柄应用新配置。
     *
     * @param handle 当前受管句柄
     * @param normalizedConfig 已由定义的配置 schema 归一化的新配置
     * @return 重配置完成后的组件状态
     */
    CompletionStage<ComponentState> reconfigure(
            ComponentHandle<?> handle,
            Object normalizedConfig);

    /** 默认策略：直接调用句柄自身的 reconfigure。 */
    static ReconfigureStrategy direct() {
        return (handle, config) -> reconfigureDirect(handle, config);
    }

    @SuppressWarnings("unchecked")
    private static CompletionStage<ComponentState> reconfigureDirect(
            ComponentHandle<?> handle,
            Object config) {
        return ((ComponentHandle<Object>) handle).reconfigure(config);
    }
}
