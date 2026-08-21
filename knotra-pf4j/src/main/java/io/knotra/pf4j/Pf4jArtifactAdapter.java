package io.knotra.pf4j;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionStage;

import io.knotra.AsyncCloseable;
import io.knotra.KnotraRuntime;

/**
 * PF4J artifact 边界的公开适配器合约。
 *
 * <p>适配器负责 artifact 加载/启动、类型化受控挂载、只读工厂目录、drain、卸载与
 * ClassLoader 防护；它不暴露可变 PF4J 插件管理器，也不会在加载时隐式挂载组件。
 * 所有等待 artifact 生命周期的入口都以 {@code *Async} 命名并返回
 * {@link CompletionStage}；同名阻塞方法只是小工具入口。</p>
 */
public interface Pf4jArtifactAdapter extends AsyncCloseable {

    static Pf4jArtifactAdapter create(
            Path pluginsRoot,
            KnotraRuntime runtime,
            Set<String> sharedContractPackages) {
        return new DefaultPf4jArtifactAdapter(pluginsRoot, runtime, sharedContractPackages);
    }

    CompletionStage<ArtifactSnapshot> loadArtifactAsync(Path artifactPath);

    CompletionStage<Void> unloadArtifactAsync(String artifactId);

    CompletionStage<Void> retryDrainAsync(String artifactId);

    default ArtifactSnapshot loadArtifact(Path artifactPath) {
        return loadArtifactAsync(artifactPath).toCompletableFuture().join();
    }

    default void unloadArtifact(String artifactId) {
        unloadArtifactAsync(artifactId).toCompletableFuture().join();
    }

    default void retryDrain(String artifactId) {
        retryDrainAsync(artifactId).toCompletableFuture().join();
    }

    ArtifactFactoryCatalog factories();

    List<ArtifactSnapshot> artifacts();

    List<ArtifactSnapshot> artifactsInState(ArtifactState state);

    Optional<ArtifactSnapshot> artifact(String artifactId);

    Optional<ArtifactDiagnostic> diagnostic(String artifactId);

    List<ArtifactOwnership> ownership(String artifactId);

    CompletionStage<Void> closeAsync();

    @Override
    default void close() {
        closeAsync().toCompletableFuture().join();
    }
}
