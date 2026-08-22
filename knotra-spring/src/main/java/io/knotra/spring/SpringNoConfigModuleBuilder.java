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
     * Sets the context class loader explicitly.
     *
     * <p>Without an explicit loader, all annotated classes must originate from one class loader.
     * The selected loader is installed on the Spring context and replaces the current thread's
     * context class loader for start, refresh, and cleanup; the previous TCCL is restored in a
     * {@code finally} block.</p>
     */
    public SpringNoConfigModuleBuilder classLoader(ClassLoader classLoader) {
        return new SpringNoConfigModuleBuilder(delegate.classLoader(classLoader));
    }

    /**
     * Registers a hook run before Knotra physically closes the child context.
     *
     * <p>The hook is retried as one Knotra lifecycle entry. A successful hook is followed by the
     * framework's physical close; a failed hook leaves the Spring context in place for the next
     * retry. Use this when cleanup failures must be visible to Knotra.</p>
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
     * Registers an absent-or-present dependency as {@code T}.
     *
     * <p>The bean is registered only when the capability is present. Injection by its declared
     * bean name is deterministic; unqualified by-type injection follows Spring's normal candidate
     * resolution and can be ambiguous if multiple beans are assignable.</p>
     */
    public <T> SpringNoConfigModuleBuilder optional(String beanName, CapabilityKey<T> key) {
        return new SpringNoConfigModuleBuilder(delegate.optional(beanName, key));
    }

    public <T> SpringNoConfigModuleBuilder optional(String beanName, Class<T> type) {
        return new SpringNoConfigModuleBuilder(delegate.optional(beanName, type));
    }

    /**
     * Registers a dependency as {@code Optional<T>} regardless of capability presence.
     *
     * <p>A manually registered singleton has no generic type metadata visible to Spring, so
     * {@code Optional<T>} cannot be selected reliably by its type argument alone. Inject it by the
     * declared bean name or through a qualifier.</p>
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
     * Registers a required dynamic dependency as a {@code T} method-lease proxy.
     *
     * <p>The capability must be an interface. Every method invocation selects the current provider
     * and holds its lease only for that method call.</p>
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
     * Registers an optional dynamic dependency as a {@code T} method-lease proxy.
     *
     * <p>The capability must be an interface. The proxy bean is always registered; method calls
     * fail while no provider is present.</p>
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
     * Registers a required dynamic dependency as {@code DynamicCapability<T>}.
     *
     * <p>Use this advanced form when application code must pin one provider explicitly for
     * {@code call} or {@code callAsync}. Inject it by bean name or qualifier because all such beans
     * share the erased {@code DynamicCapability} type.</p>
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
     * Registers an optional dynamic dependency as {@code DynamicCapability<T>}.
     *
     * <p>The bean is always registered; its capability may be unavailable while no provider is
     * present.</p>
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
     * Resolves the output by its capability type using Spring's by-type lookup.
     *
     * <p>The lookup is exact neither at compile time nor among Spring bean definitions. Prefer
     * {@link #expose(CapabilityKey, String)} when more than one bean may be assignable.</p>
     */
    public <T> SpringNoConfigModuleBuilder expose(CapabilityKey<T> key) {
        return new SpringNoConfigModuleBuilder(delegate.expose(key));
    }

    /** Resolves the output by a stable Spring bean name and checks it against the capability type. */
    public <T> SpringNoConfigModuleBuilder expose(CapabilityKey<T> key, String beanName) {
        return new SpringNoConfigModuleBuilder(delegate.expose(key, beanName));
    }

    public <T> SpringNoConfigModuleBuilder expose(Class<T> type) {
        return new SpringNoConfigModuleBuilder(delegate.expose(type));
    }

    public <T> SpringNoConfigModuleBuilder expose(Class<T> type, String beanName) {
        return new SpringNoConfigModuleBuilder(delegate.expose(type, beanName));
    }

    /** Builds the module factory as a {@link MountFactory} for the simple runtime mount facade. */
    public MountFactory build() {
        return MountFactory.adapt(delegate.build());
    }
}
