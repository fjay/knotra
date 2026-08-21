package io.knotra.pf4j;

import java.util.Objects;
import java.util.Optional;

import io.knotra.ActivationContext;
import io.knotra.Component;
import io.knotra.ComponentDescriptor;
import io.knotra.ComponentFactory;
import io.knotra.ConfigSchema;

/** Fixes one descriptor and guards all user start calls for that component instance. */
final class GuardedComponent<C> implements Component<C> {

    private final Component<C> delegate;
    private final ComponentDescriptor fixedDescriptor;
    private final KnotraClassLoaderPolicy policy;
    private final ArtifactMetadata metadata;

    private GuardedComponent(
            Component<C> delegate,
            ComponentDescriptor fixedDescriptor,
            KnotraClassLoaderPolicy policy,
            ArtifactMetadata metadata) {
        this.delegate = delegate;
        this.fixedDescriptor = fixedDescriptor;
        this.policy = policy;
        this.metadata = metadata;
        fixedDescriptor.sortedRequirements().forEach(requirement ->
                policy.validateContractType(requirement.key().type(), metadata.artifactId()));
    }

    static <C> Component<C> wrap(
            Component<C> delegate,
            KnotraClassLoaderPolicy policy,
            ArtifactMetadata metadata) {
        ComponentDescriptor descriptor = Objects.requireNonNull(
                delegate.descriptor(), "component.descriptor()");
        return new GuardedComponent<>(delegate, descriptor, policy, metadata);
    }

    @Override
    public ComponentDescriptor descriptor() {
        return fixedDescriptor;
    }

    @Override
    public void start(ActivationContext context, C config) throws Exception {
        delegate.start(new GuardedActivationContext(context, policy, metadata), config);
    }
}
