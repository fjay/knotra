package io.knotra.spring;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

import io.knotra.ComponentFactory;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/** 具有稳定组件与工厂身份标识的冻结 Spring 模块定义。 */
public final class SpringModuleDefinition<C> implements ComponentFactory<C> {

    private final String componentId;
    private final ConfigContract<C> contract;
    private final ContextOptions options;
    private final BeanNameRegistry beanNames;

    SpringModuleDefinition(
            String componentId,
            ConfigContract<C> contract,
            ContextOptions options,
            BeanNameRegistry beanNames) {
        this.componentId = Objects.requireNonNull(componentId, "componentId");
        this.contract = Objects.requireNonNull(contract, "contract");
        this.options = Objects.requireNonNull(options, "options");
        this.beanNames = Objects.requireNonNull(beanNames, "beanNames");
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
        if (!contract.configType().isInstance(input)) {
            throw new IllegalArgumentException(
                    "config must be instance of " + contract.configType().getName()
                            + ": " + input.getClass().getName());
        }
        C typed = contract.configType().cast(input);
        return applyNormalizer(typed);
    }

    private C applyNormalizer(C typed) {
        return contract.configNormalizer()
                .map(normalizer -> normalizeWith(normalizer, typed))
                .orElse(typed);
    }

    private C normalizeWith(UnaryOperator<C> normalizer, C typed) {
        C normalized = normalizer.apply(typed);
        if (normalized == null) {
            throw new IllegalStateException(
                    "config normalizer returned null for " + componentId);
        }
        return normalized;
    }

    public Class<C> configType() {
        return contract.configType();
    }

    boolean configured() {
        return contract.configured();
    }

    List<Class<?>> annotatedClasses() {
        return options.annotatedClasses();
    }

    List<Consumer<? super AnnotationConfigApplicationContext>> customizers() {
        return options.customizers();
    }

    Optional<ClassLoader> classLoader() {
        return options.classLoader();
    }

    Optional<String> configBeanName() {
        return contract.configBeanName();
    }

    Optional<SpringContextCloser> closer() {
        return options.closer();
    }

    List<SpringDependency<?>> dependencies() {
        return beanNames.dependencies();
    }

    List<SpringOutput<?>> outputs() {
        return beanNames.outputs();
    }
}
