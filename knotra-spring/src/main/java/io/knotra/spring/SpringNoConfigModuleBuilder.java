package io.knotra.spring;

import io.knotra.CapabilityKey;
import io.knotra.MountFactory;
import io.knotra.NoConfig;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.function.Consumer;

/**
 * 无公开配置契约的 Spring 子容器模块不可变构建器。
 *
 * <p>提供链式注入上游 Knotra 能力为 Spring Bean、以及将 Spring 内部 Bean 导出为 Knotra Capability 的完整 DSL。
 * {@link #build()} 方法直接产出 {@link MountFactory}，无缝适配 Simple API 的挂载入口。</p>
 */
public final class SpringNoConfigModuleBuilder {

    private final SpringModuleBuilder<NoConfig> delegate;

    SpringNoConfigModuleBuilder(String componentId) {
        this(SpringModuleBuilder.noConfig(componentId));
    }

    private SpringNoConfigModuleBuilder(SpringModuleBuilder<NoConfig> delegate) {
        this.delegate = delegate;
    }

    public SpringNoConfigModuleBuilder annotatedClasses(Class<?>... classes) {
        return new SpringNoConfigModuleBuilder(delegate.annotatedClasses(classes));
    }

    public SpringNoConfigModuleBuilder customizer(
            Consumer<? super AnnotationConfigApplicationContext> customizer) {
        return new SpringNoConfigModuleBuilder(delegate.customizer(customizer));
    }

    /**
     * 显式设置上下文类加载器。
     *
     * <p>若未显式指定加载器，所有注解类必须来自同一个类加载器。
     * 所选加载器会安装在 Spring 上下文上，并在启动、刷新和清理期间替换当前线程的上下文类加载器；
     * 原有的 TCCL 会在 {@code finally} 块中还原。</p>
     */
    public SpringNoConfigModuleBuilder classLoader(ClassLoader classLoader) {
        return new SpringNoConfigModuleBuilder(delegate.classLoader(classLoader));
    }

    /**
     * 注册在 Knotra 物理关闭子上下文之前运行的停机钩子。
     *
     * <p>该钩子作为单个 Knotra 生命周期条目支持重试。成功的钩子执行后将进行框架的物理关闭；
     * 失败的钩子会保留 Spring 上下文以便下次重试。当清理失败必须对 Knotra 可见时使用此机制。</p>
     */
    public SpringNoConfigModuleBuilder closer(SpringContextCloser closer) {
        return new SpringNoConfigModuleBuilder(delegate.closer(closer));
    }

    public <T> SpringNoConfigModuleBuilder required(String beanName, CapabilityKey<T> key) {
        return new SpringNoConfigModuleBuilder(delegate.required(beanName, key));
    }

    public <T> SpringNoConfigModuleBuilder required(String beanName, Class<T> type) {
        return new SpringNoConfigModuleBuilder(delegate.required(beanName, type));
    }

    /**
     * 将依赖注册为 {@code T}。
     *
     * <p>仅当能力存在时才注册该 Bean。按声明的 Bean 名称注入是确定性的；
     * 未限定的按类型注入遵循 Spring 的常规候选解析，若有多个兼容 Bean 可能会产生歧义。</p>
     */
    public <T> SpringNoConfigModuleBuilder optional(String beanName, CapabilityKey<T> key) {
        return new SpringNoConfigModuleBuilder(delegate.optional(beanName, key));
    }

    public <T> SpringNoConfigModuleBuilder optional(String beanName, Class<T> type) {
        return new SpringNoConfigModuleBuilder(delegate.optional(beanName, type));
    }

    /**
     * 无论能力是否存在，均将依赖注册为 {@code Optional<T>}。
     *
     * <p>手动注册的单例没有对 Spring 可见的泛型元数据，因此无法仅通过类型参数可靠选择 {@code Optional<T>}。
     * 建议按声明的 Bean 名称或通过 Qualifier 注入。</p>
     */
    public <T> SpringNoConfigModuleBuilder optionalAsOptional(
            String beanName,
            CapabilityKey<T> key) {
        return new SpringNoConfigModuleBuilder(delegate.optionalAsOptional(beanName, key));
    }

    public <T> SpringNoConfigModuleBuilder optionalAsOptional(String beanName, Class<T> type) {
        return new SpringNoConfigModuleBuilder(delegate.optionalAsOptional(beanName, type));
    }

    /**
     * 将必需的动态依赖注册为 {@code T} 方法租约代理。
     *
     * <p>能力必须是接口。每次方法调用都会选择当前提供方，且仅在该方法调用期间持有其租约。</p>
     */
    public <T> SpringNoConfigModuleBuilder dynamic(
            String beanName,
            CapabilityKey<T> key) {
        return new SpringNoConfigModuleBuilder(delegate.dynamic(beanName, key));
    }

    public <T> SpringNoConfigModuleBuilder dynamic(String beanName, Class<T> type) {
        return new SpringNoConfigModuleBuilder(delegate.dynamic(beanName, type));
    }

    /**
     * 将可选的动态依赖注册为 {@code T} 方法租约代理。
     *
     * <p>能力必须是接口。代理 Bean 始终会被注册；在无可用提供方时方法调用将失败。</p>
     */
    public <T> SpringNoConfigModuleBuilder dynamicOptional(
            String beanName,
            CapabilityKey<T> key) {
        return new SpringNoConfigModuleBuilder(delegate.dynamicOptional(beanName, key));
    }

    public <T> SpringNoConfigModuleBuilder dynamicOptional(String beanName, Class<T> type) {
        return new SpringNoConfigModuleBuilder(delegate.dynamicOptional(beanName, type));
    }

    /**
     * 将必需的动态依赖注册为 {@code DynamicCapability<T>}。
     *
     * <p>当应用代码必须显式固定单个提供方以执行 {@code call} 或 {@code callAsync} 时使用此高级形式。
     * 按 Bean 名称或 Qualifier 注入，因为所有此类 Bean 共享擦除后的 {@code DynamicCapability} 类型。</p>
     */
    public <T> SpringNoConfigModuleBuilder dynamicCapability(
            String beanName,
            CapabilityKey<T> key) {
        return new SpringNoConfigModuleBuilder(delegate.dynamicCapability(beanName, key));
    }

    public <T> SpringNoConfigModuleBuilder dynamicCapability(
            String beanName,
            Class<T> type) {
        return new SpringNoConfigModuleBuilder(delegate.dynamicCapability(beanName, type));
    }

    /**
     * 将可选的动态依赖注册为 {@code DynamicCapability<T>}。
     *
     * <p>该 Bean 始终会被注册；当无可用提供方时其能力可能处于不可用状态。</p>
     */
    public <T> SpringNoConfigModuleBuilder dynamicCapabilityOptional(
            String beanName,
            CapabilityKey<T> key) {
        return new SpringNoConfigModuleBuilder(
                delegate.dynamicCapabilityOptional(beanName, key));
    }

    public <T> SpringNoConfigModuleBuilder dynamicCapabilityOptional(
            String beanName,
            Class<T> type) {
        return new SpringNoConfigModuleBuilder(
                delegate.dynamicCapabilityOptional(beanName, type));
    }

    /**
     * 使用 Spring 按类型查找机制根据能力类型解析输出。
     *
     * <p>当可能存在多个可分配 Bean 时，推荐使用 {@link #expose(CapabilityKey, String)} 显式指定 Bean 名称。</p>
     */
    public <T> SpringNoConfigModuleBuilder expose(CapabilityKey<T> key) {
        return new SpringNoConfigModuleBuilder(delegate.expose(key));
    }

    /** 根据稳定的 Spring Bean 名称解析输出并校验其能力类型。 */
    public <T> SpringNoConfigModuleBuilder expose(CapabilityKey<T> key, String beanName) {
        return new SpringNoConfigModuleBuilder(delegate.expose(key, beanName));
    }

    public <T> SpringNoConfigModuleBuilder expose(Class<T> type) {
        return new SpringNoConfigModuleBuilder(delegate.expose(type));
    }

    public <T> SpringNoConfigModuleBuilder expose(Class<T> type, String beanName) {
        return new SpringNoConfigModuleBuilder(delegate.expose(type, beanName));
    }

    /** 构建供 Simple API 运行时挂载门面使用的 {@link MountFactory} 模块工厂。 */
    public MountFactory build() {
        return MountFactory.adapt(delegate.build());
    }
}
