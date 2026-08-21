package io.knotra.loader;

import java.util.concurrent.CompletionStage;

import io.knotra.ComponentHandle;
import io.knotra.ComponentState;

/**
 * Applies an already normalized configuration to an existing component handle.
 */
@FunctionalInterface
public interface ReconfigureStrategy {

    CompletionStage<ComponentState> reconfigure(
            ComponentHandle<?> handle,
            Object normalizedConfig);

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
