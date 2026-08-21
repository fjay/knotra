package io.knotra.pf4j;

import java.util.List;
import java.util.Optional;

/** Read-only factory catalog resolver. */
public interface ArtifactFactoryResolver {

    /** Returns catalog metadata only; a tokenless result cannot mount. */
    Optional<ArtifactFactoryCatalogEntry> resolve(String factoryId);

    /** Returns a typed handle, or fails immediately when the token does not match. */
    <C> Optional<ArtifactFactoryHandle<C>> resolve(String factoryId, Class<C> configType);

    /** Returns catalog metadata for all active factories. */
    List<ArtifactFactoryCatalogEntry> handles();
}
