package io.knotra.loader;

import java.util.concurrent.CompletionStage;

import io.knotra.ComponentFactory;
import io.knotra.ConfiguredMountHandle;
import io.knotra.ContextHandle;
import io.knotra.MountHandle;
import io.knotra.MountOptions;
import io.knotra.NoConfig;

/** Loader 为单个期望条目分配的唯一受控挂载点。 */
public interface ControlledMountContext {
    ContextHandle context();

    String mountId();

    /**
     * 在分配槽位执行一次无配置挂载。
     *
     * <p>返回普通 {@link MountHandle}；重复调用或 Context 非 ACTIVE 会结构化失败。</p>
     */
    CompletionStage<MountHandle> mountAsync(
            ComponentFactory<NoConfig> factory,
            MountOptions options);

    /**
     * 在分配槽位执行一次类型化配置挂载。
     *
     * <p>只有配置类型属于实现契约的路径才返回 {@link ConfiguredMountHandle}。</p>
     */
    <C> CompletionStage<ConfiguredMountHandle<C>> mountAsync(
            ComponentFactory<C> factory,
            C config,
            MountOptions options);
}
