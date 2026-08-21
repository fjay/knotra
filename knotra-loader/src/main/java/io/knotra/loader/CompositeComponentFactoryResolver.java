package io.knotra.loader;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Tries resolvers in declaration order and returns the first present definition.
 */
public final class CompositeComponentFactoryResolver implements ComponentFactoryResolver {

    private final List<ComponentFactoryResolver> resolvers;

    private CompositeComponentFactoryResolver(List<ComponentFactoryResolver> resolvers) {
        this.resolvers = List.copyOf(resolvers);
    }

    public static ComponentFactoryResolver of(ComponentFactoryResolver... resolvers) {
        return new CompositeComponentFactoryResolver(List.of(resolvers));
    }

    public static ComponentFactoryResolver of(List<ComponentFactoryResolver> resolvers) {
        return new CompositeComponentFactoryResolver(resolvers);
    }

    @Override
    public Optional<ResolvedComponentDefinition> resolve(FactoryRef ref) {
        for (ComponentFactoryResolver resolver : resolvers) {
            Optional<ResolvedComponentDefinition> definition = resolver.resolve(ref);
            if (definition.isPresent()) {
                return definition;
            }
        }
        return Optional.empty();
    }

    public List<ComponentFactoryResolver> resolvers() {
        return new ArrayList<>(resolvers);
    }
}
