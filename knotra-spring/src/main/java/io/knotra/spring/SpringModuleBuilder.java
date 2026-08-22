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

/** Immutable builder for an Activation-owned Spring child context factory. */
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
     * Sets the context class loader explicitly.
     *
     * <p>Without an explicit loader, all annotated classes must originate from one class loader.
     * The selected loader is installed on the Spring context and replaces the current thread's
     * context class loader for start, refresh, and cleanup; the previous TCCL is restored in a
     * {@code finally} block.
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
     * Registers a hook run before Knotra physically closes the child context.
     *
     * <p>The hook is retried as one Knotra lifecycle entry. A successful hook is followed by
     * the framework's physical close; a failed hook leaves the Spring context in place for the
     * next retry. Use this when cleanup failures must be visible to Knotra. Without a hook,
     * Spring shutdown is opaque and Spring may log and swallow destruction errors for beans it
     * created, so such errors cannot reliably drive a Knotra retry.
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

    public <T> SpringModuleBuilder<C> pinned(String beanName, CapabilityKey<T> key) {
        return required(beanName, key);
    }

    /**
     * Registers an absent-or-present dependency as {@code T}.
     *
     * <p>The bean is registered only when the capability is present. Injection by its declared
     * bean name is deterministic; unqualified by-type injection follows Spring's normal
     * candidate resolution and can be ambiguous if multiple beans are assignable.
     */
    public <T> SpringModuleBuilder<C> optional(String beanName, CapabilityKey<T> key) {
        return dependency(new SpringDependency<>(
                key, beanName, SpringDependency.Binding.OPTIONAL_VALUE));
    }

    /**
     * Registers a dependency as {@code Optional<T>} regardless of capability presence.
     *
     * <p>A manually registered singleton has no generic type metadata visible to Spring, so
     * {@code Optional<T>} cannot be selected reliably by its type argument alone. Inject it by
     * the declared bean name or through a qualifier; use {@link #optional(String, CapabilityKey)}
     * when by-type injection is preferred.
     */
    public <T> SpringModuleBuilder<C> optionalAsOptional(
            String beanName,
            CapabilityKey<T> key) {
        return dependency(new SpringDependency<>(
                key, beanName, SpringDependency.Binding.OPTIONAL_OPTIONAL));
    }

    public <T> SpringModuleBuilder<C> dynamic(String beanName, CapabilityKey<T> key) {
        return dynamicRequired(beanName, key);
    }

    public <T> SpringModuleBuilder<C> dynamicRequired(String beanName, CapabilityKey<T> key) {
        return dependency(new SpringDependency<>(key, beanName,
                SpringDependency.Binding.DYNAMIC_REQUIRED));
    }

    public <T> SpringModuleBuilder<C> dynamicOptional(String beanName, CapabilityKey<T> key) {
        return dependency(new SpringDependency<>(key, beanName,
                SpringDependency.Binding.DYNAMIC_OPTIONAL));
    }

    /**
     * Resolves the output by its capability type using Spring's by-type lookup.
     *
     * <p>The lookup is exact neither at compile time nor among Spring bean definitions: multiple
     * assignable bean candidates can fail resolution, and Knotra checks the selected object
     * against the capability type only after lookup. Prefer {@link #expose(CapabilityKey, String)}
     * when more than one bean may be assignable.
     */
    public <T> SpringModuleBuilder<C> expose(CapabilityKey<T> key) {
        return output(SpringOutput.byType(key));
    }

    /** Resolves the output by a stable Spring bean name and checks it against the capability type. */
    public <T> SpringModuleBuilder<C> expose(CapabilityKey<T> key, String beanName) {
        return output(SpringOutput.byName(key, beanName));
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

    private static String requireId(String componentId) {
        Objects.requireNonNull(componentId, "componentId");
        String trimmed = componentId.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("componentId must not be blank");
        }
        return trimmed;
    }
}
