package io.knotra.pf4j;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionStage;

import io.knotra.AsyncCloseable;
import io.knotra.KnotraRuntime;
import io.knotra.PendingOperationsSnapshot;

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

    /**
     * 返回 adapter 当前挂起操作的 point-in-time 纯文本快照，包括仍在协调器上排队或
     * 运行中的操作。实现必须返回自身真实状态，不得用空默认值掩盖卡死的排空。
     *
     * <p>该方法可在任意状态调用，不驱动协调器或 PF4J，也不改变 close 语义。</p>
     */
    PendingOperationsSnapshot pendingOperations();

    Optional<ArtifactSnapshot> artifact(String artifactId);

    Optional<ArtifactDiagnostic> diagnostic(String artifactId);

    List<ArtifactOwnership> ownership(String artifactId);

    /**
     * 异步排空全部 artifact 并关闭适配器。
     *
     * <p>与 {@code runtime.closeAsync()} 并发时，runtime 接管的组件清理由
     * 适配器内部等待其收敛，接管拒绝不会泄漏成本方法的失败。</p>
     *
     * <p><strong>禁止</strong>在同一 runtime 的组件生命周期回调（start、清理、
     * 事件监听）中阻塞等待本方法或 {@code unload/retryDrain} 完成：runtime.close 的
     * 收敛依赖回调返回，回调又等待适配器排空，二者互等会形成无法收敛的环。
     * 适配器的关闭必须由宿主线程在回调之外发起。</p>
     */
    CompletionStage<Void> closeAsync();

    @Override
    default void close() {
        closeAsync().toCompletableFuture().join();
    }
}
