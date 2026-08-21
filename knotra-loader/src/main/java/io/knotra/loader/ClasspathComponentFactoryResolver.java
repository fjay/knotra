package io.knotra.loader;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import io.knotra.ComponentFactory;
import io.knotra.ConfigSchema;
import io.knotra.MountOptions;

/**
 * Resolver for implementations already reachable from the host class path.
 */
public final class ClasspathComponentFactoryResolver implements ComponentFactoryResolver {

    private final Map<FactoryRef, ResolvedComponentDefinition> definitions;

    private ClasspathComponentFactoryResolver(
            Map<FactoryRef, ResolvedComponentDefinition> definitions) {
        this.definitions = Map.copyOf(definitions);
    }

    public static Builder builder() {
        return new Builder();
    }

    @SafeVarargs
    public static ComponentFactoryResolver forFactories(
            Map.Entry<FactoryRef, ComponentFactory<?>>... entries) {
        Builder builder = builder();
        for (Map.Entry<FactoryRef, ComponentFactory<?>> entry : entries) {
            builder.add(entry.getKey(), entry.getValue());
        }
        return builder.build();
    }

    @Override
    public Optional<ResolvedComponentDefinition> resolve(FactoryRef ref) {
        return Optional.ofNullable(definitions.get(ref));
    }

    public static final class Builder {
        private final Map<FactoryRef, ResolvedComponentDefinition> definitions =
                new LinkedHashMap<>();

        public <C> Builder add(FactoryRef ref, ComponentFactory<C> factory) {
            return add(ref, factory, null, MountOptions.DEFAULT);
        }

        public <C> Builder add(
                FactoryRef ref,
                ComponentFactory<C> factory,
                ConfigSchema<C> configSchema) {
            return add(ref, factory, configSchema, MountOptions.DEFAULT);
        }

        public <C> Builder add(
                FactoryRef ref,
                ComponentFactory<C> factory,
                MountOptions options) {
            return add(ref, factory, null, options);
        }

        public <C> Builder add(
                FactoryRef ref,
                ComponentFactory<C> factory,
                ConfigSchema<C> configSchema,
                MountOptions options) {
            if (definitions.containsKey(ref)) {
                throw new IllegalArgumentException("duplicate factory reference: " + ref);
            }
            String fingerprint = factory.getClass().getName() + "#"
                    + Integer.toUnsignedString(System.identityHashCode(factory));
            definitions.put(ref, ResolvedComponentDefinition.of(
                    FactoryIdentity.fromRef(ref, fingerprint),
                    factory,
                    configSchema,
                    options));
            return this;
        }

        public ClasspathComponentFactoryResolver build() {
            return new ClasspathComponentFactoryResolver(definitions);
        }
    }
}
