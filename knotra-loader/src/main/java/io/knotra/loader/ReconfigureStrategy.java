package io.knotra.loader;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import io.knotra.ComponentState;
import io.knotra.MountHandle;
import io.knotra.loader.ResolvedFactory.FactoryKind;

/** 把已解码配置异步应用到既有挂载的策略。 */
@FunctionalInterface
public interface ReconfigureStrategy {
    CompletionStage<ComponentState> reconfigureAsync(
            MountHandle handle,
            Object typedConfig);

    /** 按 Resolver 声明的配置能力执行 Core 的类型化重配置。 */
    static ReconfigureStrategy direct(FactoryKind factoryKind) {
        return (handle, typedConfig) ->
                ConfiguredBoundary.reconfigure(factoryKind, handle, typedConfig);
    }

    /** plain 工厂没有公开配置契约；若误入配置路径则结构化失败。 */
    static ReconfigureStrategy unsupportedPlain() {
        return (handle, typedConfig) -> CompletableFuture.failedFuture(
                new UnsupportedOperationException(
                        "plain factory does not accept configuration: " + handle.handleId()));
    }
}
