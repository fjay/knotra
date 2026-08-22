package io.knotra.spring;

import io.knotra.ActivationContext;
import io.knotra.CapabilityKey;
import io.knotra.CapabilityRequirement;
import io.knotra.Component;
import io.knotra.ComponentDescriptor;
import io.knotra.DynamicCapability;

import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
/** 无状态组件外壳；每次 Activation 均拥有一个全新的 Spring 上下文。 */
final class SpringModuleComponent<C> implements Component<C> {

    private final SpringModuleDefinition<C> definition;
    private final ComponentDescriptor descriptor;

    SpringModuleComponent(SpringModuleDefinition<C> definition) {
        this.definition = definition;
        this.descriptor = ComponentDescriptor.named(
                definition.componentId(),
                definition.dependencies().stream()
                        .map(SpringDependency::requirement)
                        .toArray(CapabilityRequirement[]::new));
    }

    @Override
    public ComponentDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public void start(ActivationContext context, C config) throws Exception {
        ClassLoader loader = effectiveClassLoader();
        AnnotationConfigApplicationContext spring = new AnnotationConfigApplicationContext();
        // 在清理可逆前，不允许运行任何 Bean、自定义器或刷新操作。
        registerCleanup(context, spring, loader);
        spring.setClassLoader(loader);
        ClassLoader previousLoader = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(loader);
        try {
            if (!definition.annotatedClasses().isEmpty()) {
                spring.register(definition.annotatedClasses().toArray(Class<?>[]::new));
            }
            if (definition.configured()) {
                registerExternalSingleton(
                        spring,
                        definition.configBeanName().orElseThrow(),
                        definition.configType(),
                        config);
            }
            for (SpringDependency<?> dependency : definition.dependencies()) {
                registerDependency(context, spring, dependency);
            }
            for (Consumer<? super AnnotationConfigApplicationContext> customizer
                    : definition.customizers()) {
                customizer.accept(spring);
            }
            spring.refresh();

            List<StagedOutput<?>> staged = resolveOutputs(spring);
            for (StagedOutput<?> output : staged) {
                provide(context, output);
            }
        } finally {
            Thread.currentThread().setContextClassLoader(previousLoader);
        }
    }

    private ClassLoader effectiveClassLoader() {
        return definition.classLoader().orElseGet(() -> definition.annotatedClasses().isEmpty()
                ? SpringModuleComponent.class.getClassLoader()
                : definition.annotatedClasses().getFirst().getClassLoader());
    }

    private static <T> void registerExternalSingleton(
            AnnotationConfigApplicationContext spring,
            String beanName,
            Class<? super T> type,
            T value) {
        if (!type.isInstance(value)) {
            throw new IllegalArgumentException(
                    "external singleton '" + beanName + "' is not instance of "
                            + type.getName() + ": "
                            + (value == null ? "null" : value.getClass().getName()));
        }
        DefaultListableBeanFactory beanFactory =
                (DefaultListableBeanFactory) spring.getBeanFactory();
        // 外部注册的单例不接收 Spring 初始化或销毁回调。
        beanFactory.registerSingleton(beanName, value);
    }
    private void registerDependency(
            ActivationContext context,
            AnnotationConfigApplicationContext spring,
            SpringDependency<?> dependency) {
        switch (dependency.binding()) {
            case REQUIRED -> registerRequired(context, spring, cast(dependency));
            case OPTIONAL_VALUE -> registerOptionalValue(context, spring, cast(dependency));
            case OPTIONAL_OPTIONAL -> registerOptionalWrapper(context, spring, cast(dependency));
            case DYNAMIC_CAPABILITY_REQUIRED, DYNAMIC_CAPABILITY_OPTIONAL ->
                    registerDynamicCapability(context, spring, cast(dependency));
            case DYNAMIC_REQUIRED, DYNAMIC_OPTIONAL ->
                    registerDynamicProxy(context, spring, cast(dependency));
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> SpringDependency<T> cast(SpringDependency<?> dependency) {
        return (SpringDependency<T>) dependency;
    }

    private static <T> void registerRequired(
            ActivationContext context,
            AnnotationConfigApplicationContext spring,
            SpringDependency<T> dependency) {
        registerExternalSingleton(
                spring,
                dependency.beanName(),
                dependency.key().type(),
                context.require(dependency.key()));
    }

    private static <T> void registerOptionalValue(
            ActivationContext context,
            AnnotationConfigApplicationContext spring,
            SpringDependency<T> dependency) {
        context.find(dependency.key()).ifPresent(value ->
                registerExternalSingleton(
                        spring,
                        dependency.beanName(),
                        dependency.key().type(),
                        value));
    }

    private static <T> void registerOptionalWrapper(
            ActivationContext context,
            AnnotationConfigApplicationContext spring,
            SpringDependency<T> dependency) {
        Optional<T> wrapper = context.find(dependency.key());
        registerExternalSingleton(
                spring,
                dependency.beanName(),
                Optional.class,
                wrapper);
    }


    private static <T> void registerDynamicCapability(
            ActivationContext context,
            AnnotationConfigApplicationContext spring,
            SpringDependency<T> dependency) {
        registerExternalSingleton(
                spring,
                dependency.beanName(),
                DynamicCapability.class,
                context.subscribe(dependency.key()));
    }

    private static <T> void registerDynamicProxy(
            ActivationContext context,
            AnnotationConfigApplicationContext spring,
            SpringDependency<T> dependency) {
        DynamicCapability<T> capability = context.subscribe(dependency.key());
        T proxy = capability.proxy(dependency.key().type());
        registerExternalSingleton(
                spring,
                dependency.beanName(),
                dependency.key().type(),
                proxy);
    }

    private void registerCleanup(
            ActivationContext context,
            AnnotationConfigApplicationContext spring,
            ClassLoader loader) {
        String description = "spring-context:" + definition.componentId();
        SpringContextCloser hook = definition.closer().orElse(null);
        context.lifecycle().onCloseAsync(description, () ->
                runCleanup(spring, hook, loader));
    }

    private static CompletionStage<Void> runCleanup(
            AnnotationConfigApplicationContext spring,
            SpringContextCloser hook,
            ClassLoader loader) {
        ClassLoader previousLoader = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(loader);
        try {
            if (hook == null) {
                return closePhysically(spring, loader);
            }
            CompletionStage<Void> stage;
            try {
                stage = hook.close(spring);
            } catch (Throwable error) {
                return failedCleanup(error);
            }
            if (stage == null) {
                return closePhysically(spring, loader);
            }
            return stage.handle((ignored, error) -> {
                if (error != null) {
                    throw new CompletionException(error);
                }
                return closePhysically(spring, loader);
            }).thenCompose(stageValue -> stageValue);
        } finally {
            Thread.currentThread().setContextClassLoader(previousLoader);
        }
    }

    private static CompletionStage<Void> closePhysically(
            AnnotationConfigApplicationContext spring,
            ClassLoader loader) {
        ClassLoader previousLoader = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(loader);
        try {
            if (spring.isActive()) {
                spring.close();
            } else {
                // refresh() 未完成；Spring 创建的早期单例仍需要清理。
                ((DefaultListableBeanFactory) spring.getBeanFactory()).destroySingletons();
            }
            return CompletableFuture.completedFuture(null);
        } catch (Throwable error) {
            return failedCleanup(error);
        } finally {
            Thread.currentThread().setContextClassLoader(previousLoader);
        }
    }

    private static CompletionStage<Void> failedCleanup(Throwable error) {
        CompletableFuture<Void> failed = new CompletableFuture<>();
        failed.completeExceptionally(error);
        return failed;
    }

    private List<StagedOutput<?>> resolveOutputs(
            AnnotationConfigApplicationContext spring) {
        List<StagedOutput<?>> result = new ArrayList<>();
        for (SpringOutput<?> output : definition.outputs()) {
            result.add(resolveOutput(spring, output));
        }
        return result;
    }

    private <T> StagedOutput<T> resolveOutput(
            AnnotationConfigApplicationContext spring,
            SpringOutput<T> output) {
        Object bean = output.beanName()
                .map(name -> (Object) spring.getBeanFactory().getBean(name))
                .orElseGet(() -> spring.getBeanFactory().getBean(output.key().type()));
        if (!output.key().type().isInstance(bean)) {
            throw new IllegalStateException(
                    "Spring output '" + output.key().name() + "' is not instance of "
                            + output.key().typeName());
        }
        return new StagedOutput<>(output.key(), output.key().type().cast(bean));
    }
    private <T> void provide(ActivationContext context, StagedOutput<T> output) {
        context.provide(output.key(), output.value());
    }

    private record StagedOutput<T>(CapabilityKey<T> key, T value) {
    }
}
