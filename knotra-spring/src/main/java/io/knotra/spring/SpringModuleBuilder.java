package io.knotra.spring;

import java.util.Objects;
import java.util.function.UnaryOperator;

import io.knotra.ComponentFactory;

/** Activation 拥有的 Spring 子上下文工厂不可变构建器。 */
public final class SpringModuleBuilder<C>
        extends SpringModuleDsl<SpringModuleBuilder<C>, C> {

    SpringModuleBuilder(String componentId, Class<C> configType) {
        this(
                requireId(componentId),
                ConfigContract.typed(
                        Objects.requireNonNull(configType, "configType"), "knotraConfig"),
                ContextOptions.EMPTY,
                BeanNameRegistry.EMPTY.withConfigBeanName("knotraConfig"));
    }

    private SpringModuleBuilder(
            String componentId,
            ConfigContract<C> contract,
            ContextOptions options,
            BeanNameRegistry beanNames) {
        super(componentId, contract, options, beanNames);
    }

    public SpringModuleBuilder<C> configBeanName(String configBeanName) {
        if (!(contract() instanceof ConfigContract.TypedConfigContract<C> typed)) {
            throw new IllegalStateException("no-config module has no config bean");
        }
        String nextBeanName = SpringDependency.requireBeanName(configBeanName);
        return recreate(
                typed.withBeanName(nextBeanName),
                options(),
                beanNames().withConfigBeanName(nextBeanName));
    }

    public SpringModuleBuilder<C> configNormalizer(UnaryOperator<C> configNormalizer) {
        if (!(contract() instanceof ConfigContract.TypedConfigContract<C> typed)) {
            throw new IllegalStateException("no-config module has no config normalizer");
        }
        return recreate(
                typed.withNormalizer(configNormalizer), options(), beanNames());
    }

    public ComponentFactory<C> build() {
        validate();
        return definition();
    }

    @Override
    protected SpringModuleBuilder<C> recreate(
            ConfigContract<C> contract,
            ContextOptions options,
            BeanNameRegistry beanNames) {
        return new SpringModuleBuilder<>(
                componentId(),
                contract,
                options,
                beanNames);
    }

    static <C> SpringModuleBuilder<C> typed(String componentId, Class<C> configType) {
        return new SpringModuleBuilder<>(componentId, configType);
    }

}
