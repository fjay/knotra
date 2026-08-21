package io.knotra.loader;

import java.util.List;

/**
 * The complete desired component tree supplied to a reconciliation.
 */
public record ComponentTree(List<ComponentEntry> entries) {

    public ComponentTree {
        entries = List.copyOf(entries == null ? List.of() : entries);
    }

    public static ComponentTree empty() {
        return new ComponentTree(List.of());
    }

    public static ComponentTree of(ComponentEntry... entries) {
        return new ComponentTree(List.of(entries));
    }
}
