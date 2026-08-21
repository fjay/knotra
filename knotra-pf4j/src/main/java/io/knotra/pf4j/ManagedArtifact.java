package io.knotra.pf4j;

import java.lang.ref.WeakReference;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.pf4j.PluginWrapper;
import io.knotra.ComponentHandle;

final class ManagedArtifact {

    final String artifactId;
    final String version;
    final Path path;
    final ArtifactMetadata metadata;
    final WeakReference<PluginWrapper> wrapper;
    final Set<String> dependencies;

    volatile ArtifactState state = ArtifactState.ACTIVE;
    volatile String transition = "published";
    volatile String lastError;
    volatile WeakReference<ClassLoader> classLoader;
    volatile boolean acceptingMounts = true;

    final List<ManagedFactory<?>> factories = new ArrayList<>();
    final Map<String, ComponentHandle<?>> directHandles = new LinkedHashMap<>();
    final Map<String, ManagedFactory<?>> factoriesById = new LinkedHashMap<>();
    final Set<String> factoryIdHistory = new LinkedHashSet<>();
    int mountsInFlight;
    java.util.concurrent.CompletableFuture<Void> mountsInFlightFuture;
    java.util.concurrent.CompletableFuture<Void> drainFuture;
    java.util.function.Supplier<String> pf4jStateView = () -> "UNKNOWN";

    ManagedArtifact(
            String artifactId,
            String version,
            Path path,
            Set<String> dependencies,
            PluginWrapper wrapper) {
        this.artifactId = artifactId;
        this.version = version;
        this.path = path.toAbsolutePath().normalize();
        this.metadata = ArtifactMetadata.of(artifactId, version, this.path);
        this.wrapper = new WeakReference<>(wrapper);
        this.dependencies = Set.copyOf(dependencies);
        this.classLoader = new WeakReference<>(wrapper.getPluginClassLoader());
    }

    ArtifactSnapshot snapshot(List<ArtifactOwnership> ownership) {
        ClassLoader loader = classLoader == null ? null : classLoader.get();
        return new ArtifactSnapshot(
                artifactId,
                version,
                path.toString(),
                state,
                pf4jStateView.get(),
                loader == null ? "" : KnotraClassLoaderPolicy.describe(loader),
                factories.stream().map(factory -> factory.factoryId).toList(),
                ownership.stream().map(ArtifactOwnership::handleId).toList(),
                dependencies.stream().toList());
    }

    ArtifactDiagnostic diagnostic(List<ArtifactOwnership> ownership) {
        ClassLoader loader = classLoader == null ? null : classLoader.get();
        return new ArtifactDiagnostic(
                artifactId,
                state,
                transition,
                factoryIdHistory.stream().toList(),
                ownership.stream().map(ArtifactOwnership::handleId).toList(),
                lastError == null ? java.util.Optional.empty() : java.util.Optional.of(lastError),
                loader == null ? List.of() : List.of(KnotraClassLoaderPolicy.describe(loader)));
    }

    List<ComponentHandle<?>> rootHandles() {
        return List.copyOf(directHandles.values());
    }

    void fail(String transition, String message) {
        state = ArtifactState.FAILED;
        this.transition = transition;
        lastError = message;
        acceptingMounts = false;
        invalidateFactories();
    }

    void invalidateFactories() {
        acceptingMounts = false;
        for (ManagedFactory<?> factory : factories) {
            factory.factory = null;
            factory.configSchema = java.util.Optional.empty();
        }
        factories.clear();
        factoriesById.clear();
    }

    void unloadView() {
        state = ArtifactState.UNLOADED;
        transition = "unloaded";
        acceptingMounts = false;
        directHandles.clear();
        invalidateFactories();
        classLoader = null;
        wrapper.clear();
    }
}
