package io.knotra;

import java.util.concurrent.CompletionStage;

/**
 * 具有显式配置契约的稳定组件挂载句柄。
 *
 * @param <C> 配置对象的类型
 */
public interface ConfiguredMountHandle<C> extends MountHandle {

    /**
     * 异步重新配置已挂载的组件，并触发其重新配置策略（ReconfigureStrategy）。
     *
     * @param config 新的配置实例
     * @return 重新配置完成后组件的新状态
     */
    CompletionStage<ComponentState> reconfigureAsync(C config);
}
