package io.knotra.pf4j;

import java.util.List;
import java.util.Optional;

/**
 * Unique catalog of factories published by active artifacts.
 *
 * <p>{@link #list()} and {@link #find(String)} expose stable metadata only. The executable root
 * view cannot mount; use the no-config or typed configured resolution method.</p>
 */
public interface ArtifactFactoryCatalog {

    /** Returns catalog metadata for every active factory. */
    List<ArtifactFactoryCatalogEntry> list();

    /** Finds catalog metadata by factory id; the result cannot mount a component. */
    Optional<ArtifactFactoryCatalogEntry> find(String factoryId);

    /** Returns the non-mounting executable view used by diagnostics and dynamic tools. */
    Optional<ArtifactFactoryHandle> resolve(String factoryId);

    /** Returns a no-config factory whose mount call does not expose {@code NoConfig}. */
    Optional<ArtifactFactoryHandle.NoConfig> resolveNoConfig(String factoryId);

    /** Returns a typed configured factory; a mismatched token fails immediately. */
    <C> Optional<ArtifactFactoryHandle.Configured<C>> resolve(String factoryId, Class<C> configType);
}
