package io.knotra.internal;

import io.knotra.ComponentState;
import io.knotra.ConfiguredMountHandle;
import java.time.Duration;
import java.util.concurrent.CompletionStage;

/** 配置类型为公开类型的挂载运行时句柄。 */
final class ConfiguredMountHandleImpl<C> extends MountHandleImpl
        implements ConfiguredMountHandle<C> {
    ConfiguredMountHandleImpl(DefaultKnotraRuntime runtime, String id, Identity identity) {
        super(runtime, id, identity);
    }

    @Override
    public ConfiguredMountHandle<C> requireActive() {
        return runtime.requireActiveConfigured(this, null);
    }

    @Override
    public ConfiguredMountHandle<C> requireActive(Duration timeout) {
        return runtime.requireActiveConfigured(this, timeout);
    }

    @Override
    public CompletionStage<ComponentState> reconfigureAsync(C config) {
        return runtime.reconfigure(this, config);
    }
}
