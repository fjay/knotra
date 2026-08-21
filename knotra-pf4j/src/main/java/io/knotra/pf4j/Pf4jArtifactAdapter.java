package io.knotra.pf4j;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import io.knotra.KnotraRuntime;

/** PF4J artifact boundary. The mutable PF4J plugin manager is never exposed. */
public interface Pf4jArtifactAdapter extends AutoCloseable {

    static Pf4jArtifactAdapter create(
            Path pluginsRoot,
            KnotraRuntime runtime,
            Set<String> sharedContractPackages) {
        return new DefaultPf4jArtifactAdapter(pluginsRoot, runtime, sharedContractPackages);
    }

    CompletableFuture<ArtifactSnapshot> loadArtifact(Path artifactPath);

    CompletableFuture<Void> unloadArtifact(String artifactId);

    CompletableFuture<Void> retryDrain(String artifactId);

    List<ArtifactFactoryCatalogEntry> factoryCatalog();

    ArtifactFactoryResolver resolver();

    List<ArtifactSnapshot> artifacts();

    List<ArtifactSnapshot> artifactsInState(ArtifactState state);

    Optional<ArtifactSnapshot> artifact(String artifactId);

    Optional<ArtifactDiagnostic> diagnostic(String artifactId);

    List<ArtifactOwnership> ownership(String artifactId);

    CompletableFuture<Void> closeAsync();

    @Override
    default void close() {
        closeAsync().join();
    }
}
