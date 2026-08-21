package io.knotra.pf4j;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import io.knotra.KnotraRuntime;

/**
 * PF4J artifact 边界的公开适配器合约。
 *
 * <p>适配器负责 artifact 加载/启动、类型化受控挂载、只读工厂目录、drain、卸载与
 * ClassLoader 防护；它不暴露可变 PF4J 插件管理器，也不会在加载时隐式挂载组件。</p>
 */
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
