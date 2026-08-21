package io.knotra.loader;

import java.util.List;

/**
 * One desired component and its nested child declarations.
 */
public record ComponentEntry(
        String path,
        FactoryRef factoryRef,
        Object config,
        List<ComponentEntry> children) {

    public ComponentEntry {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("entry path must not be blank");
        }
        if (factoryRef == null) {
            throw new IllegalArgumentException("factoryRef must not be null");
        }
        children = List.copyOf(children == null ? List.of() : children);
    }

    public static ComponentEntry of(
            String path,
            FactoryRef factoryRef,
            Object config,
            ComponentEntry... children) {
        return new ComponentEntry(path, factoryRef, config, List.of(children));
    }
}
