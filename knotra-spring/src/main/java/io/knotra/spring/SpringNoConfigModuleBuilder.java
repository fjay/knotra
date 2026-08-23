package io.knotra.spring;

import io.knotra.MountFactory;
import io.knotra.NoConfig;

/**
 * 无公开配置契约的 Spring 子容器模块不可变构建器。
 *
 * <p>提供链式注入上游 Knotra 能力为 Spring Bean、以及将 Spring 内部 Bean 导出为 Knotra Capability 的完整 DSL。
 * {@link #build()} 方法直接产出 {@link MountFactory}，无缝适配 Simple API 的挂载入口。</p>
 */
public final class SpringNoConfigModuleBuilder
        extends SpringModuleDsl<SpringNoConfigModuleBuilder, NoConfig> {

    SpringNoConfigModuleBuilder(String componentId) {
        super(
                requireId(componentId),
                ConfigContract.noConfig(),
                ContextOptions.EMPTY,
                BeanNameRegistry.EMPTY);
    }

    private SpringNoConfigModuleBuilder(
            String componentId,
            ConfigContract<NoConfig> contract,
            ContextOptions options,
            BeanNameRegistry beanNames) {
        super(componentId, contract, options, beanNames);
    }

    /** 构建供 Simple API 运行时挂载门面使用的 {@link MountFactory} 模块工厂。 */
    public MountFactory build() {
        validate();
        return MountFactory.adapt(definition());
    }

    @Override
    protected SpringNoConfigModuleBuilder recreate(
            ConfigContract<NoConfig> contract,
            ContextOptions options,
            BeanNameRegistry beanNames) {
        return new SpringNoConfigModuleBuilder(
                componentId(), contract, options, beanNames);
    }
}
