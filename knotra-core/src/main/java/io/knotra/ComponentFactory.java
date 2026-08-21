package io.knotra;

import java.util.Optional;

public interface ComponentFactory<C> {
    String factoryId();

    Component<C> create();

    default Optional<ConfigSchema<C>> configSchema() {
        return Optional.empty();
    }
}
