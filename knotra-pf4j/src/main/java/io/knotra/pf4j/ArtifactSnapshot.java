package io.knotra.pf4j;

import java.util.List;

/** Immutable structural view of one managed PF4J artifact. */
public record ArtifactSnapshot(
        String artifactId,
        String version,
        String path,
        ArtifactState state,
        String pf4jState,
        String classLoaderDescription,
        List<String> factoryIds,
        List<String> ownedHandleIds,
        List<String> dependencyArtifactIds) {

    public ArtifactSnapshot {
        factoryIds = List.copyOf(factoryIds);
        ownedHandleIds = List.copyOf(ownedHandleIds);
        dependencyArtifactIds = List.copyOf(dependencyArtifactIds);
    }
}
