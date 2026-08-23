package io.knotra.spring;

import java.util.Objects;
import java.util.function.Consumer;

import io.knotra.CapabilityKey;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/**
 * 两个 Spring 模块 builder 共用的自类型化 DSL。
 *
 * <p>公共依赖、输出和上下文选项只在这里实现；类型化配置 API 留给
 * {@link SpringModuleBuilder}，Simple API 适配留给 {@link SpringNoConfigModuleBuilder}。</p>
 */
abstract class SpringModuleDsl<B extends SpringModuleDsl<B, C>, C> {

    private final String componentId;
    private final ConfigContract<C> contract;
    private final ContextOptions options;
    private final BeanNameRegistry beanNames;

    SpringModuleDsl(
            String componentId,
            ConfigContract<C> contract,
            ContextOptions options,
            BeanNameRegistry beanNames) {
        this.componentId = Objects.requireNonNull(componentId, "componentId");
        this.contract = Objects.requireNonNull(contract, "contract");
        this.options = Objects.requireNonNull(options, "options");
        this.beanNames = Objects.requireNonNull(beanNames, "beanNames");
    }

    public B annotatedClasses(Class<?>... classes) {
        return recreate(contract, options.withAnnotatedClasses(classes), beanNames);
    }

    public B customizer(
            Consumer<? super AnnotationConfigApplicationContext> customizer) {
        return recreate(contract, options.withCustomizer(customizer), beanNames);
    }

    /**
     * 显式设置上下文类加载器。
     *
     * <p>若未显式指定加载器，所有注解类必须来自同一个类加载器。
     * 所选加载器会安装在 Spring 上下文上，并在启动、刷新和清理期间替换当前线程的上下文类加载器；
     * 原有的 TCCL 会在 {@code finally} 块中还原。</p>
     */
    public B classLoader(ClassLoader classLoader) {
        return recreate(contract, options.withClassLoader(classLoader), beanNames);
    }

    /**
     * 注册在 Knotra 物理关闭子上下文之前运行的停机钩子。
     *
     * <p>该钩子作为单个 Knotra 生命周期条目支持重试。成功的钩子执行后将进行框架的物理关闭；
     * 失败的钩子会保留 Spring 上下文以便下次重试。当清理失败必须对 Knotra 可见时使用此机制。</p>
     */
    public B closer(SpringContextCloser closer) {
        return recreate(contract, options.withCloser(closer), beanNames);
    }

    public <T> B required(String beanName, CapabilityKey<T> key) {
        return dependency(new SpringDependency<>(key, beanName,
                SpringDependency.Binding.REQUIRED));
    }

    public <T> B required(String beanName, Class<T> type) {
        return required(beanName, CapabilityKey.of(type));
    }

    /**
     * 将依赖注册为 {@code T}。
     *
     * <p>仅当能力存在时才注册该 Bean。按声明的 Bean 名称注入是确定性的；
     * 未限定的按类型注入遵循 Spring 的常规候选解析，若有多个兼容 Bean 可能会产生歧义。</p>
     */
    public <T> B optional(String beanName, CapabilityKey<T> key) {
        return dependency(new SpringDependency<>(
                key, beanName, SpringDependency.Binding.OPTIONAL_VALUE));
    }

    public <T> B optional(String beanName, Class<T> type) {
        return optional(beanName, CapabilityKey.of(type));
    }

    /**
     * 无论能力是否存在，均将依赖注册为 {@code Optional<T>}。
     *
     * <p>手动注册的单例没有对 Spring 可见的泛型元数据，因此无法仅通过类型参数可靠选择 {@code Optional<T>}。
     * 建议按声明的 Bean 名称或通过 Qualifier 注入；优先按类型注入时请使用 {@link #optional(String, CapabilityKey)}。</p>
     */
    public <T> B optionalAsOptional(String beanName, CapabilityKey<T> key) {
        return dependency(new SpringDependency<>(
                key, beanName, SpringDependency.Binding.OPTIONAL_OPTIONAL));
    }

    public <T> B optionalAsOptional(String beanName, Class<T> type) {
        return optionalAsOptional(beanName, CapabilityKey.of(type));
    }

    /**
     * 将必需的动态依赖注册为 {@code T} 方法租约代理。
     *
     * <p>能力必须是接口。每次方法调用都会选择当前提供方，且仅在该方法调用期间持有其租约。</p>
     */
    public <T> B dynamic(String beanName, CapabilityKey<T> key) {
        requireProxyInterface(key);
        return dependency(new SpringDependency<>(
                key, beanName, SpringDependency.Binding.DYNAMIC_PROXY_REQUIRED));
    }

    public <T> B dynamic(String beanName, Class<T> type) {
        return dynamic(beanName, CapabilityKey.of(type));
    }

    /**
     * 将可选的动态依赖注册为 {@code T} 方法租约代理。
     *
     * <p>能力必须是接口。代理 Bean 始终会被注册；在无可用提供方时方法调用将失败。</p>
     */
    public <T> B dynamicOptional(String beanName, CapabilityKey<T> key) {
        requireProxyInterface(key);
        return dependency(new SpringDependency<>(
                key, beanName, SpringDependency.Binding.DYNAMIC_PROXY_OPTIONAL));
    }

    public <T> B dynamicOptional(String beanName, Class<T> type) {
        return dynamicOptional(beanName, CapabilityKey.of(type));
    }

    /**
     * 将必需的动态依赖注册为 {@code DynamicCapability<T>}。
     *
     * <p>当应用代码必须显式固定单个提供方以执行 {@code call} 或 {@code callAsync} 时使用此高级形式。
     * 按 Bean 名称或 Qualifier 注入，因为所有此类 Bean 共享擦除后的 {@code DynamicCapability} 类型。</p>
     */
    public <T> B dynamicCapability(String beanName, CapabilityKey<T> key) {
        return dependency(new SpringDependency<>(
                key, beanName, SpringDependency.Binding.DYNAMIC_CAPABILITY_REQUIRED));
    }

    public <T> B dynamicCapability(String beanName, Class<T> type) {
        return dynamicCapability(beanName, CapabilityKey.of(type));
    }

    /**
     * 将可选的动态依赖注册为 {@code DynamicCapability<T>}。
     *
     * <p>该 Bean 始终会被注册；当无可用提供方时其能力可能处于不可用状态。</p>
     */
    public <T> B dynamicCapabilityOptional(String beanName, CapabilityKey<T> key) {
        return dependency(new SpringDependency<>(
                key, beanName, SpringDependency.Binding.DYNAMIC_CAPABILITY_OPTIONAL));
    }

    public <T> B dynamicCapabilityOptional(String beanName, Class<T> type) {
        return dynamicCapabilityOptional(beanName, CapabilityKey.of(type));
    }

    /**
     * 使用 Spring 按类型查找机制根据能力类型解析输出。
     *
     * <p>当可能存在多个可分配 Bean 时，推荐使用 {@link #expose(CapabilityKey, String)} 显式指定 Bean 名称。</p>
     */
    public <T> B expose(CapabilityKey<T> key) {
        return output(SpringOutput.byType(key));
    }

    /** 根据稳定的 Spring Bean 名称解析输出并校验其能力类型。 */
    public <T> B expose(CapabilityKey<T> key, String beanName) {
        return output(SpringOutput.byName(key, beanName));
    }

    public <T> B expose(Class<T> type) {
        return expose(CapabilityKey.of(type));
    }

    public <T> B expose(Class<T> type, String beanName) {
        return expose(CapabilityKey.of(type), beanName);
    }

    /** 校验可在构建期确定的上下文约束。 */
    protected void validate() {
        if (options.classLoader().isEmpty() && options.annotatedClasses().size() > 1) {
            ClassLoader expected = options.annotatedClasses().getFirst().getClassLoader();
            for (Class<?> annotatedClass : options.annotatedClasses()) {
                if (!Objects.equals(expected, annotatedClass.getClassLoader())) {
                    throw new IllegalArgumentException(
                            "annotated classes use multiple class loaders; set classLoader "
                                    + "explicitly or use classes from one loader");
                }
            }
        }
    }

    protected SpringModuleDefinition<C> definition() {
        return new SpringModuleDefinition<>(componentId, contract, options, beanNames);
    }

    protected String componentId() {
        return componentId;
    }

    protected ConfigContract<C> contract() {
        return contract;
    }

    protected ContextOptions options() {
        return options;
    }

    protected BeanNameRegistry beanNames() {
        return beanNames;
    }

    protected abstract B recreate(
            ConfigContract<C> contract, ContextOptions options, BeanNameRegistry beanNames);

    private B dependency(SpringDependency<?> dependency) {
        return recreate(contract, options, beanNames.withDependency(dependency));
    }

    private B output(SpringOutput<?> output) {
        return recreate(contract, options, beanNames.withOutput(output));
    }

    protected static String requireId(String componentId) {
        Objects.requireNonNull(componentId, "componentId");
        String trimmed = componentId.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("componentId must not be blank");
        }
        return trimmed;
    }

    private static void requireProxyInterface(CapabilityKey<?> key) {
        Objects.requireNonNull(key, "key");
        if (!key.type().isInterface()) {
            throw new IllegalArgumentException(
                    "dynamic proxy capability must be an interface: " + key.typeName());
        }
    }
}
