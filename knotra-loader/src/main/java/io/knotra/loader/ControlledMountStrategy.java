package io.knotra.loader;

import java.util.concurrent.CompletionStage;

import io.knotra.ComponentHandle;

/** Loader 实现来源在已分配单一槽位中执行挂载的异步策略。 */
@FunctionalInterface
public interface ControlledMountStrategy {
    CompletionStage<ComponentHandle<?>> mountAsync(
            ControlledMountContext context,
            Object typedConfig);
}
