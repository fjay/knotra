package io.knotra.spring;

import io.knotra.NoConfig;

/** Entry point for building Activation-owned Spring child contexts. */
public final class SpringModules {

    private SpringModules() {
    }

    public static SpringModuleBuilder<NoConfig> noConfig(String componentId) {
        return SpringModuleBuilder.noConfig(componentId);
    }

    public static <C> SpringModuleBuilder<C> typed(String componentId, Class<C> configType) {
        return SpringModuleBuilder.typed(componentId, configType);
    }
}
