package io.knotra.pf4j;

import io.knotra.ComponentHandle;
import io.knotra.ConfigSchema;
import io.knotra.ContextHandle;

import java.util.Optional;

/** Controlled, configuration-typed view of one factory from a managed artifact. */
public interface ArtifactFactoryHandle<C> extends ArtifactFactoryCatalogEntry {

    /** Host/shared configuration token validated when the artifact was discovered. */
    Class<C> configType();

    /**
     * Returns the active factory's schema, or empty when it has none.
     *
     * <p>This is an active-artifact view. A handle is not a detached schema snapshot;
     * callers must resolve a new typed handle after an artifact is unloaded.</p>
     */
    Optional<ConfigSchema<C>> configSchema();

    /** Mounts a new logical component and returns its stable Knotra handle. */
    ComponentHandle<C> mount(ContextHandle context, String mountId, C config);
}
