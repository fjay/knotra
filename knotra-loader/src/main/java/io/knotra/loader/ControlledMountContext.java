package io.knotra.loader;

import java.util.concurrent.CompletionStage;

import io.knotra.ComponentFactory;
import io.knotra.ComponentHandle;
import io.knotra.ContextHandle;
import io.knotra.MountOptions;

/** Loader 为单个期望条目分配的唯一受控挂载点。 */
public interface ControlledMountContext {
    ContextHandle context();

    String mountId();

    /** 在分配槽位执行一次类型化挂载。重复调用或 Context 非 ACTIVE 会结构化失败。 */
    <C> CompletionStage<ComponentHandle<C>> mountAsync(
            ComponentFactory<C> factory,
            C config,
            MountOptions options);
}
