package io.knotra.spring;

/**
 * 构建受 Knotra 激活生命周期托管的 Spring 子容器模块的入口工厂。
 *
 * <p>Spring 子容器在组件激活时初始化，在组件销毁时自动优雅关闭其 ApplicationContext。</p>
 */
public final class SpringModules {

    private SpringModules() {
    }

    /** 创建无公开配置契约的 Spring 模块构建器。 */
    public static SpringNoConfigModuleBuilder noConfig(String componentId) {
        return new SpringNoConfigModuleBuilder(componentId);
    }

    /** 创建带类型化配置的 Spring 模块构建器。 */
    public static <C> SpringModuleBuilder<C> typed(String componentId, Class<C> configType) {
        return SpringModuleBuilder.typed(componentId, configType);
    }
}
