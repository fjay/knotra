package io.knotra.spring;


/** Entry point for building Activation-owned Spring child contexts. */
public final class SpringModules {

    private SpringModules() {
    }

    public static SpringNoConfigModuleBuilder noConfig(String componentId) {
        return new SpringNoConfigModuleBuilder(componentId);
    }

    public static <C> SpringModuleBuilder<C> typed(String componentId, Class<C> configType) {
        return SpringModuleBuilder.typed(componentId, configType);
    }
}
