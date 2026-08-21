package io.knotra.internal;

import io.knotra.Component;
import io.knotra.ComponentDescriptor;
import io.knotra.ComponentFactory;
import io.knotra.ConfigSchema;
import io.knotra.MountOptions;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Objects;
import java.util.Optional;

final class PreparedComponent<C> {
    private final String factoryId;
    private final ComponentDescriptor descriptor;
    private final Optional<ConfigSchema<C>> schema;
    private final Object config;
    private final MountOptions options;
    private final MethodHandle start;
    private final MethodHandle normalize;

    private PreparedComponent(
            String factoryId,
            ComponentDescriptor descriptor,
            Optional<ConfigSchema<C>> schema,
            Object config,
            MountOptions options,
            MethodHandle start,
            MethodHandle normalize) {
        this.factoryId = factoryId;
        this.descriptor = descriptor;
        this.schema = schema;
        this.config = config;
        this.options = options;
        this.start = start;
        this.normalize = normalize;
    }

    static <C> PreparedComponent<C> prepare(
            ComponentFactory<C> factory,
            C rawConfig,
            MountOptions options) {
        Objects.requireNonNull(factory, "factory");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(
                rawConfig,
                "config (use NoConfig.INSTANCE for components without configuration)");
        String factoryId = safeText(factory.factoryId());
        if (factoryId.isBlank()) {
            throw new IllegalArgumentException("factoryId must not be blank");
        }
        Component<C> component = Objects.requireNonNull(
                factory.create(), "factory.create() returned null");
        ComponentDescriptor descriptor = component.descriptor();
        for (String failure : descriptor.validate()) {
            throw new IllegalArgumentException(failure);
        }
        Optional<ConfigSchema<C>> optionalSchema = factory.configSchema();
        ConfigSchema<C> validator = optionalSchema.orElse(null);
        C config;
        try {
            config = validator == null
                    ? rawConfig
                    : Objects.requireNonNull(
                            validator.validate(rawConfig),
                            "config schema returned null");
        } catch (RuntimeException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalArgumentException(
                    LifecycleScopeImpl.safeError(error),
                    error);
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
            MethodHandle normalize = validator == null
                    ? MethodHandles.identity(Object.class)
                    : MethodHandles.lookup()
                            .unreflect(ConfigSchema.class.getMethod(
                                    "validate",
                                    Object.class))
                            .bindTo(validator)
                            .asType(MethodType.methodType(
                                    Object.class,
                                    Object.class));
            return new PreparedComponent<>(
                    factoryId,
                    descriptor,
                    optionalSchema,
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

    Optional<ConfigSchema<C>> schema() {
        return schema;
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

    Object normalize(Object rawConfig) throws Exception {
        return invoke(normalize, rawConfig);
    }

    private static Object invoke(MethodHandle handle, Object... arguments)
            throws Exception {
        try {
            return handle.invokeWithArguments(arguments);
        } catch (Exception | Error error) {
            throw error;
        } catch (Throwable error) {
            if (error instanceof Exception failure) {
                throw failure;
            }
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
}
