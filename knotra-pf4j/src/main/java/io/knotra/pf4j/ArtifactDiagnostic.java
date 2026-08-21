package io.knotra.pf4j;

import java.util.List;
import java.util.Optional;

/** Immutable diagnostic view. It never retains artifact objects or class loaders. */
public record ArtifactDiagnostic(
        String artifactId,
        ArtifactState state,
        String transition,
        List<String> factoryIds,
        List<String> ownedHandleIds,
        Optional<String> lastError,
        List<String> classLoaderDiagnostics) {

    public ArtifactDiagnostic {
        factoryIds = List.copyOf(factoryIds);
        ownedHandleIds = List.copyOf(ownedHandleIds);
        lastError = lastError == null ? Optional.empty() : lastError;
        classLoaderDiagnostics = List.copyOf(classLoaderDiagnostics);
    }
}
