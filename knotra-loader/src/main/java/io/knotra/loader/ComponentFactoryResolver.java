package io.knotra.loader;

import java.util.Optional;

/**
 * Resolves desired references into complete, validated implementation definitions.
 */
@FunctionalInterface
public interface ComponentFactoryResolver {

    Optional<ResolvedComponentDefinition> resolve(FactoryRef ref);
}
