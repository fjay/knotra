package io.knotra.loader;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import io.knotra.ComponentFactory;
import io.knotra.ComponentHandle;
import io.knotra.ConfigSchema;
import io.knotra.MountOptions;
import io.knotra.NoConfig;

/**
 * An opaque, fully normalized implementation selected by a resolver.
 */
public record ResolvedComponentDefinition(
        FactoryIdentity identity,
        ConfigSchema<Object> configSchema,
        ControlledMountStrategy mountStrategy,
        ReconfigureStrategy reconfigureStrategy) {

    public ResolvedComponentDefinition {
        Objects.requireNonNull(identity, "identity");
        configSchema = configSchema == null ? raw -> noConfigOrValue(raw) : configSchema;
        mountStrategy = Objects.requireNonNull(mountStrategy, "mountStrategy");
        reconfigureStrategy = Objects.requireNonNull(reconfigureStrategy, "reconfigureStrategy");
    }

    public static ResolvedComponentDefinition of(
            FactoryIdentity identity,
            ComponentFactory<?> factory) {
        return of(identity, factory, MountOptions.DEFAULT);
    }

    public static ResolvedComponentDefinition of(
            FactoryIdentity identity,
            ComponentFactory<?> factory,
            ConfigSchema<?> configSchema) {
        return of(identity, factory, configSchema, MountOptions.DEFAULT);
    }

    public static ResolvedComponentDefinition of(
            FactoryIdentity identity,
            ComponentFactory<?> factory,
            MountOptions options) {
        return of(identity, factory, null, options);
    }

    public static ResolvedComponentDefinition of(
            FactoryIdentity identity,
            ComponentFactory<?> factory,
            ConfigSchema<?> configSchema,
            MountOptions options) {
        Objects.requireNonNull(factory, "factory");
        ConfigSchema<Object> adapted = raw -> {
            ConfigSchema<?> selected = configSchema == null
                    ? factory.configSchema().orElse(null)
                    : configSchema;
            if (selected == null) {
                return noConfigOrValue(raw);
            }
            return selected.validate(raw);
        };
        return new ResolvedComponentDefinition(
                identity,
                adapted,
                directMount(factory, options),
                ReconfigureStrategy.direct());
    }

    public Object normalizeConfig(Object raw) throws Exception {
        return configSchema.validate(raw);
    }

    private static ControlledMountStrategy directMount(
            ComponentFactory<?> factory,
            MountOptions options) {
        return (context, config) -> {
            CompletionStage<?> mounted = mountDirect(context, factory, options, config);
            @SuppressWarnings("unchecked")
            CompletionStage<ComponentHandle<?>> result =
                    (CompletionStage<ComponentHandle<?>>) mounted;
            return result;
        };
    }

    private static <C> CompletionStage<ComponentHandle<C>> mountDirect(
            ControlledMountContext context,
            ComponentFactory<C> factory,
            MountOptions options,
            Object config) {
        @SuppressWarnings("unchecked")
        C typedConfig = (C) config;
        return context.mount(factory, typedConfig, options);
    }

    private static Object noConfigOrValue(Object raw) {
        return raw == null ? NoConfig.INSTANCE : raw;
    }
}
