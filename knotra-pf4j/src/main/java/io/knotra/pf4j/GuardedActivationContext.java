package io.knotra.pf4j;

import java.util.Objects;
import java.util.Optional;

import io.knotra.ActivationContext;
import io.knotra.CapabilityKey;
import io.knotra.Component;
import io.knotra.ComponentDescriptor;
import io.knotra.ComponentFactory;
import io.knotra.ConfigSchema;
import io.knotra.ContextInfo;
import io.knotra.LifecycleScope;
import io.knotra.MountOptions;

/** Wraps every activation edge and recursively guards child mounts. */
final class GuardedActivationContext implements ActivationContext {

    private final ActivationContext delegate;
    private final KnotraClassLoaderPolicy policy;
    private final ArtifactMetadata metadata;

    GuardedActivationContext(
            ActivationContext delegate,
            KnotraClassLoaderPolicy policy,
            ArtifactMetadata metadata) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.metadata = Objects.requireNonNull(metadata, "metadata");
    }

    @Override
    public <T> T require(CapabilityKey<T> key) {
        validate(key);
        return delegate.require(key);
    }

    @Override
    public <T> Optional<T> find(CapabilityKey<T> key) {
        validate(key);
        return delegate.find(key);
    }

    @Override
    public <T> void provide(CapabilityKey<T> key, T value) {
        validate(key);
        delegate.provide(key, value);
    }

    @Override
    public <C> io.knotra.ComponentHandle<C> mountChild(
            String mountId,
            ComponentFactory<C> factory,
            C config) {
        return mountChild(mountId, factory, config, null);
    }

    @Override
    public <C> io.knotra.ComponentHandle<C> mountChild(
            String mountId,
            ComponentFactory<C> factory,
            C config,
            MountOptions options) {
        // Ignore a caller-supplied origin. Artifact provenance is inherited exactly.
        ComponentFactory<C> guarded = GuardedComponentFactory.wrap(
                factory,
                policy,
                metadata);
        return delegate.mountChild(
                mountId,
                guarded,
                config,
                new MountOptions(metadata.origin(), options == null
                        ? metadata.options().metadata()
                        : options.metadata()));
    }

    @Override
    public LifecycleScope lifecycle() {
        return delegate.lifecycle();
    }

    @Override
    public ContextInfo contextInfo() {
        return delegate.contextInfo();
    }

    private void validate(CapabilityKey<?> key) {
        Objects.requireNonNull(key, "key");
        policy.validateContractType(key.type(), metadata.artifactId());
    }
}
