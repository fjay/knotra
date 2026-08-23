package io.knotra;

import java.time.Duration;
import java.util.concurrent.CompletionStage;

/** 具有显式配置契约的稳定组件挂载句柄。 */
public interface ConfiguredMountHandle<C> extends MountHandle {

    /** 异步重新配置已挂载的组件，并触发其重新配置策略。 */
    CompletionStage<ComponentState> reconfigureAsync(C config);

    @Override
    default ConfiguredMountHandle<C> requireActive() {
        return (ConfiguredMountHandle<C>) MountHandle.super.requireActive();
    }

    @Override
    default ConfiguredMountHandle<C> requireActive(Duration timeout) {
        return (ConfiguredMountHandle<C>) MountHandle.super.requireActive(timeout);
    }
}
