package io.knotra.loader;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import io.knotra.ComponentState;
import io.knotra.ConfiguredMountHandle;
import io.knotra.MountHandle;
import io.knotra.loader.ResolvedFactory.FactoryKind;

/**
 * Loader 记账边界上的配置类型擦除适配点。
 *
 * <p>配置能力先由 {@link ResolvedFactory#factoryKind()} 声明；decode 之后 Loader
 * 只能以 Object 保存当前配置。配置传回类型化 Core API 前，这里仅校验 Core handle
 * 与已声明能力一致，并恢复该捕获类型。</p>
 */
final class ConfiguredBoundary {

    private ConfiguredBoundary() {
    }

    @SuppressWarnings("unchecked")
    static <C> C coerce(Object value) {
        return (C) value;
    }

    static CompletionStage<ComponentState> reconfigure(
            FactoryKind factoryKind,
            MountHandle handle,
            Object value) {
        if (factoryKind != FactoryKind.CONFIGURED) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException(
                    "plain factory does not accept configuration: " + handle.handleId()));
        }
        if (!(handle instanceof ConfiguredMountHandle<?> configured)) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "configured factory returned a non-configured core handle: " + handle.handleId()));
        }
        return adapted(configured).reconfigureAsync(value);
    }

    @SuppressWarnings("unchecked")
    private static ConfiguredMountHandle<Object> adapted(ConfiguredMountHandle<?> handle) {
        return (ConfiguredMountHandle<Object>) handle;
    }
}
