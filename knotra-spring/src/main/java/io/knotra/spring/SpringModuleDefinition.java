package io.knotra.spring;

import io.knotra.ComponentFactory;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/** Frozen Spring module definition with a stable component and factory identity. */
public final class SpringModuleDefinition<C> implements ComponentFactory<C> {

    private final String componentId;
    private final Class<C> configType;
    private final boolean configured;
    private final List<Class<?>> annotatedClasses;
    private final List<Consumer<? super AnnotationConfigApplicationContext>> customizers;
    private final ClassLoader classLoader;
    private final String configBeanName;
    private final UnaryOperator<C> configNormalizer;
    private final SpringContextCloser closer;
    private final List<SpringDependency<?>> dependencies;
    private final List<SpringOutput<?>> outputs;

    SpringModuleDefinition(
            String componentId,
            Class<C> configType,
            boolean configured,
            List<Class<?>> annotatedClasses,
            List<Consumer<? super AnnotationConfigApplicationContext>> customizers,
            ClassLoader classLoader,
            String configBeanName,
            UnaryOperator<C> configNormalizer,
            SpringContextCloser closer,
            List<SpringDependency<?>> dependencies,
            List<SpringOutput<?>> outputs) {
        this.componentId = Objects.requireNonNull(componentId, "componentId");
        this.configType = Objects.requireNonNull(configType, "configType");
        this.configured = configured;
        this.annotatedClasses = List.copyOf(annotatedClasses);
        this.customizers = List.copyOf(customizers);
        this.classLoader = classLoader;
        this.configBeanName = configBeanName;
        this.configNormalizer = configNormalizer;
        this.closer = closer;
        this.dependencies = List.copyOf(dependencies);
        this.outputs = List.copyOf(outputs);
    }

    public String componentId() {
        return componentId;
    }

    @Override
    public String factoryId() {
        return componentId;
    }

    @Override
    public io.knotra.Component<C> create() {
        return new SpringModuleComponent<>(this);
    }

    @Override
    public C normalizeConfig(C config) throws Exception {
        C input = Objects.requireNonNull(config, "config");
        if (!configType.isInstance(input)) {
            throw new IllegalArgumentException(
                    "config must be instance of " + configType.getName()
                            + ": " + input.getClass().getName());
        }
        C typed = configType.cast(input);
        if (!configured || configNormalizer == null) {
            return typed;
        }
        C normalized = configNormalizer.apply(typed);
        if (normalized == null) {
            throw new IllegalStateException(
                    "config normalizer returned null for " + componentId);
        }
        return normalized;
    }

    public Class<C> configType() {
        return configType;
    }

    boolean configured() {
        return configured;
    }

    List<Class<?>> annotatedClasses() {
        return annotatedClasses;
    }

    List<Consumer<? super AnnotationConfigApplicationContext>> customizers() {
        return customizers;
    }

    Optional<ClassLoader> classLoader() {
        return Optional.ofNullable(classLoader);
    }

    Optional<String> configBeanName() {
        return Optional.ofNullable(configBeanName);
    }

    Optional<SpringContextCloser> closer() {
        return Optional.ofNullable(closer);
    }

    List<SpringDependency<?>> dependencies() {
        return dependencies;
    }

    List<SpringOutput<?>> outputs() {
        return outputs;
    }
}
