package io.knotra.internal;

import io.knotra.Component;
import io.knotra.ComponentDescriptor;
import io.knotra.ComponentFactory;
import io.knotra.MountOptions;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Objects;

/** 挂载前完成创建、声明冻结与类型化配置归一化的组件。 */
final class PreparedComponent<C> {
    private final String factoryId;
    private final ComponentDescriptor descriptor;
    private final Object config;
    private final MountOptions options;
    private final MethodHandle start;
    private final MethodHandle normalize;

    private PreparedComponent(
            String factoryId,
            ComponentDescriptor descriptor,
            Object config,
            MountOptions options,
            MethodHandle start,
            MethodHandle normalize) {
        this.factoryId = factoryId;
        this.descriptor = descriptor;
        this.config = config;
        this.options = options;
        this.start = start;
        this.normalize = normalize;
    }

    static <C> PreparedComponent<C> prepare(
            ComponentFactory<C> factory,
            C inputConfig,
            MountOptions options) {
        Objects.requireNonNull(factory, "factory");
        Objects.requireNonNull(options, "options");

        String factoryId = safeText(factory.factoryId());
        if (factoryId.isBlank()) {
            throw new IllegalArgumentException("factoryId must not be blank");
        }
        Component<C> component = Objects.requireNonNull(
                factory.create(), "factory.create() returned null");
        ComponentDescriptor declared = Objects.requireNonNull(
                component.descriptor(), "component.descriptor() returned null");
        ComponentDescriptor descriptor = declared.componentId().isEmpty()
                ? ComponentDescriptor.named(
                        factoryId,
                        declared.requirements().toArray(io.knotra.CapabilityRequirement[]::new))
                : declared;
        C config;
        try {
            C nonNullConfig = Objects.requireNonNull(
                    inputConfig,
                    "config (use the no-config mount overload for components without configuration)");
            config = Objects.requireNonNull(
                    factory.normalizeConfig(nonNullConfig),
                    "config normalizer returned null");
        } catch (Exception error) {
            throw new InvalidConfigException(LifecycleScopeImpl.safeError(error), error);
        }

        try {
            MethodHandle start = MethodHandles.lookup()
                    .unreflect(Component.class.getMethod(
                            "start",
                            io.knotra.ActivationContext.class,
                            Object.class))
                    .bindTo(component)
                    .asType(MethodType.methodType(
                            void.class,
                            io.knotra.ActivationContext.class,
                            Object.class));
            MethodHandle normalize = MethodHandles.lookup()
                    .unreflect(ComponentFactory.class.getMethod(
                            "normalizeConfig",
                            Object.class))
                    .bindTo(factory)
                    .asType(MethodType.methodType(Object.class, Object.class));
            return new PreparedComponent<>(
                    factoryId,
                    descriptor,
                    config,
                    options,
                    start,
                    normalize);
        } catch (NoSuchMethodException | IllegalAccessException error) {
            throw new IllegalStateException("component contract method is unavailable", error);
        }
    }

    String factoryId() {
        return factoryId;
    }

    ComponentDescriptor descriptor() {
        return descriptor;
    }

    Object config() {
        return config;
    }

    MountOptions options() {
        return options;
    }

    void start(io.knotra.ActivationContext context, Object config) throws Exception {
        invoke(start, context, config);
    }

    Object normalize(Object config) throws Exception {
        return invoke(normalize, config);
    }

    private static Object invoke(MethodHandle handle, Object... arguments) throws Exception {
        try {
            return handle.invokeWithArguments(arguments);
        } catch (Exception | Error error) {
            throw error;
        } catch (Throwable error) {
            throw new IllegalStateException(error);
        }
    }

    private static String safeText(String value) {
        if (value == null) {
            return "";
        }
        try {
            return value.trim();
        } catch (Throwable ignored) {
            return "";
        }
    }

    static final class InvalidConfigException extends RuntimeException {
        InvalidConfigException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
