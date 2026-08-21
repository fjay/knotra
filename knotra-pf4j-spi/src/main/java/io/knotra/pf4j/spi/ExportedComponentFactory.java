package io.knotra.pf4j.spi;

import java.util.Objects;

import io.knotra.ComponentFactory;
import io.knotra.NoConfig;

/**
 * A factory exported by an artifact together with its cross-boundary config token.
 *
 * @param <C> configuration type shared by the artifact and host
 */
public record ExportedComponentFactory<C>(
        Class<C> configType,
        ComponentFactory<C> factory) {

    public ExportedComponentFactory {
        Objects.requireNonNull(configType, "configType");
        Objects.requireNonNull(factory, "factory");
    }

    public static <C> ExportedComponentFactory<C> of(
            Class<C> configType,
            ComponentFactory<C> factory) {
        return new ExportedComponentFactory<>(configType, factory);
    }

    public static ExportedComponentFactory<NoConfig> noConfig(
            ComponentFactory<NoConfig> factory) {
        return new ExportedComponentFactory<>(NoConfig.class, factory);
    }
}
