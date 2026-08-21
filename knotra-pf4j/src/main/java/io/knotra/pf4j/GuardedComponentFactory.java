package io.knotra.pf4j;

import java.util.Objects;
import java.util.Optional;

import io.knotra.Component;
import io.knotra.ComponentFactory;
import io.knotra.ConfigSchema;

/** Internal factory adapter that cannot be used to bypass artifact provenance. */
final class GuardedComponentFactory<C> implements ComponentFactory<C> {

    private final ComponentFactory<C> delegate;
    private final String fixedFactoryId;
    private final KnotraClassLoaderPolicy policy;
    private final ArtifactMetadata metadata;

    private GuardedComponentFactory(
            ComponentFactory<C> delegate,
            String fixedFactoryId,
            KnotraClassLoaderPolicy policy,
            ArtifactMetadata metadata) {
        this.delegate = delegate;
        this.fixedFactoryId = fixedFactoryId;
        this.policy = policy;
        this.metadata = metadata;
    }

    static <C> ComponentFactory<C> wrap(
            ComponentFactory<C> factory,
            KnotraClassLoaderPolicy policy,
            ArtifactMetadata metadata) {
        Objects.requireNonNull(factory, "factory");
        policy.validateInterface(factory.getClass(), ComponentFactory.class, metadata.artifactId());
        String factoryId = factory.factoryId();
        if (factoryId == null || factoryId.isBlank()) {
            throw new ArtifactOperationException(
                    metadata.artifactId(),
                    "factory",
                    "factory returned a blank factory id");
        }
        return new GuardedComponentFactory<>(
                factory,
                factoryId.trim(),
                policy,
                metadata);
    }

    @Override
    public String factoryId() {
        return fixedFactoryId;
    }

    @Override
    public Component<C> create() {
        return GuardedComponent.wrap(delegate.create(), policy, metadata);
    }

    @Override
    public Optional<ConfigSchema<C>> configSchema() {
        return delegate.configSchema();
    }

}
