package io.knotra.loader;

import java.util.concurrent.CompletionStage;

import io.knotra.ComponentHandle;
import io.knotra.ComponentState;

/** 把已解码配置异步应用到既有 ComponentHandle 的策略。 */
@FunctionalInterface
public interface ReconfigureStrategy {
    CompletionStage<ComponentState> reconfigureAsync(
            ComponentHandle<?> handle,
            Object typedConfig);

    static ReconfigureStrategy direct() {
        return ReconfigureStrategy::reconfigureDirect;
    }

    @SuppressWarnings("unchecked")
    private static CompletionStage<ComponentState> reconfigureDirect(
            ComponentHandle<?> handle,
            Object config) {
        return ((ComponentHandle<Object>) handle).reconfigureAsync(config);
    }
}
