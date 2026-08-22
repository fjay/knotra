package io.knotra.spring;

import io.knotra.CapabilityKey;
import io.knotra.ComponentFactory;
import io.knotra.NoConfig;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/** Activation 拥有的 Spring 子上下文工厂不可变构建器。 */
public final class SpringModuleBuilder<C> {

    private final String componentId;
    private final Class<C> configType;
    private final boolean configured;
    private final List<Class<?>> annotatedClasses;
    private final List<Consumer<? super AnnotationConfigApplicationContext>> customizers;
    private final ClassLoader classLoader;
    private final String configBeanName;
    private final UnaryOperator<C> configNormalizer;
    private final SpringContextCloser closer;
    private final Map<String, SpringDependency<?>> dependencies;
    private final List<SpringOutput<?>> outputs;

    SpringModuleBuilder(String componentId, Class<C> configType, boolean configured) {
        this(
                componentId,
                configType,
                configured,
                List.of(),
                List.of(),
                null,
                configured ? "knotraConfig" : null,
                null,
                null,
                Map.of(),
                List.of());
    }

    private SpringModuleBuilder(
            String componentId,
            Class<C> configType,
            boolean configured,
            List<Class<?>> annotatedClasses,
            List<Consumer<? super AnnotationConfigApplicationContext>> customizers,
            ClassLoader classLoader,
            String configBeanName,
            UnaryOperator<C> configNormalizer,
            SpringContextCloser closer,
            Map<String, SpringDependency<?>> dependencies,
            List<SpringOutput<?>> outputs) {
        this.componentId = componentId;
        this.configType = configType;
        this.configured = configured;
        this.annotatedClasses = annotatedClasses;
        this.customizers = customizers;
        this.classLoader = classLoader;
        this.configBeanName = configBeanName;
        this.configNormalizer = configNormalizer;
        this.closer = closer;
        this.dependencies = dependencies;
        this.outputs = outputs;
    }

    public SpringModuleBuilder<C> annotatedClasses(Class<?>... classes) {
        Objects.requireNonNull(classes, "classes");
        List<Class<?>> next = new ArrayList<>(annotatedClasses);
        for (Class<?> type : classes) {
            next.add(Objects.requireNonNull(type, "annotated class"));
        }
        return recreate(next, customizers, classLoader, configBeanName,
                configNormalizer, closer, dependencies, outputs);
    }

    public SpringModuleBuilder<C> customizer(
            Consumer<? super AnnotationConfigApplicationContext> customizer) {
        Objects.requireNonNull(customizer, "customizer");
        List<Consumer<? super AnnotationConfigApplicationContext>> next =
                new ArrayList<>(customizers);
        next.add(customizer);
        return recreate(annotatedClasses, next, classLoader, configBeanName,
                configNormalizer, closer, dependencies, outputs);
    }

    /**
     * 显式设置上下文类加载器。
     *
     * <p>若未显式指定加载器，所有注解类必须来自同一个类加载器。
     * 所选加载器会安装在 Spring 上下文上，并在启动、刷新和清理期间替换当前线程的上下文类加载器；
     * 原有的 TCCL 会在 {@code finally} 块中还原。</p>
     */
    public SpringModuleBuilder<C> classLoader(ClassLoader classLoader) {
        return recreate(annotatedClasses, customizers,
                Objects.requireNonNull(classLoader, "classLoader"), configBeanName,
                configNormalizer, closer, dependencies, outputs);
    }

    public SpringModuleBuilder<C> configBeanName(String configBeanName) {
        if (!configured) {
            throw new IllegalStateException("no-config module has no config bean");
        }
        return recreate(annotatedClasses, customizers, classLoader,
                SpringDependency.requireBeanName(configBeanName),
                configNormalizer, closer, dependencies, outputs);
    }

    public SpringModuleBuilder<C> configNormalizer(UnaryOperator<C> configNormalizer) {
        if (!configured) {
            throw new IllegalStateException("no-config module has no config normalizer");
        }
        return recreate(annotatedClasses, customizers, classLoader, configBeanName,
                Objects.requireNonNull(configNormalizer, "configNormalizer"),
                closer, dependencies, outputs);
    }

    /**
     * 注册在 Knotra 物理关闭子上下文之前运行的停机钩子。
     *
     * <p>该钩子作为单个 Knotra 生命周期条目支持重试。成功的钩子执行后将进行框架的物理关闭；
     * 失败的钩子会保留 Spring 上下文以便下次重试。当清理失败必须对 Knotra 可见时使用此机制。</p>
     */
    public SpringModuleBuilder<C> closer(SpringContextCloser closer) {
        return recreate(annotatedClasses, customizers, classLoader, configBeanName,
                configNormalizer, Objects.requireNonNull(closer, "closer"),
                dependencies, outputs);
    }

    public <T> SpringModuleBuilder<C> required(String beanName, CapabilityKey<T> key) {
        return dependency(new SpringDependency<>(key, beanName,
                SpringDependency.Binding.REQUIRED));
    }

    public <T> SpringModuleBuilder<C> required(String beanName, Class<T> type) {
        return required(beanName, CapabilityKey.of(type));
    }

    /**
     * 将依赖注册为 {@code T}。
     *
     * <p>仅当能力存在时才注册该 Bean。按声明的 Bean 名称注入是确定性的；
     * 未限定的按类型注入遵循 Spring 的常规候选解析，若有多个兼容 Bean 可能会产生歧义。</p>
     */
    public <T> SpringModuleBuilder<C> optional(String beanName, CapabilityKey<T> key) {
        return dependency(new SpringDependency<>(
                key, beanName, SpringDependency.Binding.OPTIONAL_VALUE));
    }

    public <T> SpringModuleBuilder<C> optional(String beanName, Class<T> type) {
        return optional(beanName, CapabilityKey.of(type));
    }

    /**
     * 无论能力是否存在，均将依赖注册为 {@code Optional<T>}。
     *
     * <p>手动注册的单例没有对 Spring 可见的泛型元数据，因此无法仅通过类型参数可靠选择 {@code Optional<T>}。
     * 建议按声明的 Bean 名称或通过 Qualifier 注入；优先按类型注入时请使用 {@link #optional(String, CapabilityKey)}。</p>
     */
    public <T> SpringModuleBuilder<C> optionalAsOptional(
            String beanName,
            CapabilityKey<T> key) {
        return dependency(new SpringDependency<>(
                key, beanName, SpringDependency.Binding.OPTIONAL_OPTIONAL));
    }

    public <T> SpringModuleBuilder<C> optionalAsOptional(String beanName, Class<T> type) {
        return optionalAsOptional(beanName, CapabilityKey.of(type));
    }

    /**
     * 将必需的动态依赖注册为 {@code T} 方法租约代理。
     *
     * <p>能力必须是接口。每次方法调用都会选择当前提供方，且仅在该方法调用期间持有其租约。</p>
     */
    public <T> SpringModuleBuilder<C> dynamic(
            String beanName,
            CapabilityKey<T> key) {
        requireProxyInterface(key);
        return dependency(new SpringDependency<>(
                key, beanName, SpringDependency.Binding.DYNAMIC_REQUIRED));
    }

    public <T> SpringModuleBuilder<C> dynamic(String beanName, Class<T> type) {
        return dynamic(beanName, CapabilityKey.of(type));
    }

    /**
     * 将可选的动态依赖注册为 {@code T} 方法租约代理。
     *
     * <p>能力必须是接口。代理 Bean 始终会被注册；在无可用提供方时方法调用将失败。</p>
     */
    public <T> SpringModuleBuilder<C> dynamicOptional(
            String beanName,
            CapabilityKey<T> key) {
        requireProxyInterface(key);
        return dependency(new SpringDependency<>(
                key, beanName, SpringDependency.Binding.DYNAMIC_OPTIONAL));
    }

    public <T> SpringModuleBuilder<C> dynamicOptional(String beanName, Class<T> type) {
        return dynamicOptional(beanName, CapabilityKey.of(type));
    }

    /**
     * 将必需的动态依赖注册为 {@code DynamicCapability<T>}。
     *
     * <p>当应用代码必须显式固定单个提供方以执行 {@code call} 或 {@code callAsync} 时使用此高级形式。
     * 按 Bean 名称或 Qualifier 注入，因为所有此类 Bean 共享擦除后的 {@code DynamicCapability} 类型。</p>
     */
    public <T> SpringModuleBuilder<C> dynamicCapability(
            String beanName,
            CapabilityKey<T> key) {
        return dependency(new SpringDependency<>(
                key, beanName, SpringDependency.Binding.DYNAMIC_CAPABILITY_REQUIRED));
    }

    public <T> SpringModuleBuilder<C> dynamicCapability(
            String beanName,
            Class<T> type) {
        return dynamicCapability(beanName, CapabilityKey.of(type));
    }

    /**
     * 将可选的动态依赖注册为 {@code DynamicCapability<T>}。
     *
     * <p>该 Bean 始终会被注册；当无可用提供方时其能力可能处于不可用状态。</p>
     */
    public <T> SpringModuleBuilder<C> dynamicCapabilityOptional(
            String beanName,
            CapabilityKey<T> key) {
        return dependency(new SpringDependency<>(
                key, beanName, SpringDependency.Binding.DYNAMIC_CAPABILITY_OPTIONAL));
    }

    public <T> SpringModuleBuilder<C> dynamicCapabilityOptional(
            String beanName,
            Class<T> type) {
        return dynamicCapabilityOptional(beanName, CapabilityKey.of(type));
    }

    /**
     * 使用 Spring 按类型查找机制根据能力类型解析输出。
     *
     * <p>当可能存在多个可分配 Bean 时，推荐使用 {@link #expose(CapabilityKey, String)} 显式指定 Bean 名称。</p>
     */
    public <T> SpringModuleBuilder<C> expose(CapabilityKey<T> key) {
        return output(SpringOutput.byType(key));
    }

    /** 根据稳定的 Spring Bean 名称解析输出并校验其能力类型。 */
    public <T> SpringModuleBuilder<C> expose(CapabilityKey<T> key, String beanName) {
        return output(SpringOutput.byName(key, beanName));
    }

    public <T> SpringModuleBuilder<C> expose(Class<T> type) {
        return expose(CapabilityKey.of(type));
    }

    public <T> SpringModuleBuilder<C> expose(Class<T> type, String beanName) {
        return expose(CapabilityKey.of(type), beanName);
    }

    public ComponentFactory<C> build() {
        validate();
        return new SpringModuleDefinition<>(
                componentId,
                configType,
                configured,
                annotatedClasses,
                customizers,
                classLoader,
                configBeanName,
                configNormalizer,
                closer,
                dependencies.values().stream().toList(),
                outputs);
    }

    private void validate() {
        if (configured && dependencies.containsKey(configBeanName)) {
            throw new IllegalArgumentException(
                    "config bean name is already used by a dependency: " + configBeanName);
        }
        if (configured && outputs.stream().anyMatch(output ->
                output.beanName().filter(configBeanName::equals).isPresent())) {
            throw new IllegalArgumentException(
                    "config bean name is already used by an output: " + configBeanName);
        }
        if (classLoader == null && annotatedClasses.size() > 1) {
            ClassLoader expected = annotatedClasses.getFirst().getClassLoader();
            for (Class<?> annotatedClass : annotatedClasses) {
                if (!Objects.equals(expected, annotatedClass.getClassLoader())) {
                    throw new IllegalArgumentException(
                            "annotated classes use multiple class loaders; set classLoader "
                                    + "explicitly or use classes from one loader");
                }
            }
        }
    }

    private SpringModuleBuilder<C> dependency(SpringDependency<?> dependency) {
        Objects.requireNonNull(dependency, "dependency");
        Map<String, SpringDependency<?>> next = new LinkedHashMap<>(dependencies);
        if (next.putIfAbsent(dependency.beanName(), dependency) != null) {
            throw new IllegalArgumentException("duplicate dependency bean name: "
                    + dependency.beanName());
        }
        if (dependencies.values().stream().anyMatch(existing ->
                existing.key().name().equals(dependency.key().name()))) {
            throw new IllegalArgumentException("duplicate dependency capability: "
                    + dependency.key().name());
        }
        return recreate(annotatedClasses, customizers, classLoader, configBeanName,
                configNormalizer, closer, next, outputs);
    }

    private SpringModuleBuilder<C> output(SpringOutput<?> output) {
        Objects.requireNonNull(output, "output");
        if (outputs.stream().anyMatch(existing ->
                existing.key().name().equals(output.key().name()))) {
            throw new IllegalArgumentException("duplicate output capability: "
                    + output.key().name());
        }
        String requestedBean = output.beanName().orElse(null);
        if (requestedBean != null && dependencies.containsKey(requestedBean)) {
            throw new IllegalArgumentException(
                    "output bean name is already used by a dependency: " + requestedBean);
        }
        List<SpringOutput<?>> next = new ArrayList<>(outputs);
        next.add(output);
        return recreate(annotatedClasses, customizers, classLoader, configBeanName,
                configNormalizer, closer, dependencies, next);
    }

    private SpringModuleBuilder<C> recreate(
            List<Class<?>> annotatedClasses,
            List<Consumer<? super AnnotationConfigApplicationContext>> customizers,
            ClassLoader classLoader,
            String configBeanName,
            UnaryOperator<C> configNormalizer,
            SpringContextCloser closer,
            Map<String, SpringDependency<?>> dependencies,
            List<SpringOutput<?>> outputs) {
        return new SpringModuleBuilder<>(
                componentId,
                configType,
                configured,
                List.copyOf(annotatedClasses),
                List.copyOf(customizers),
                classLoader,
                configBeanName,
                configNormalizer,
                closer,
                Map.copyOf(dependencies),
                List.copyOf(outputs));
    }

    static SpringModuleBuilder<NoConfig> noConfig(String componentId) {
        return new SpringModuleBuilder<>(
                requireId(componentId), NoConfig.class, false);
    }

    static <C> SpringModuleBuilder<C> typed(String componentId, Class<C> configType) {
        return new SpringModuleBuilder<>(
                requireId(componentId),
                Objects.requireNonNull(configType, "configType"),
                true);
    }

    private static void requireProxyInterface(CapabilityKey<?> key) {
        Objects.requireNonNull(key, "key");
        if (!key.type().isInterface()) {
            throw new IllegalArgumentException(
                    "dynamic proxy capability must be an interface: " + key.typeName());
        }
    }

    private static String requireId(String componentId) {
        Objects.requireNonNull(componentId, "componentId");
        String trimmed = componentId.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("componentId must not be blank");
        }
        return trimmed;
    }
}
