package io.knotra.pf4j.spi;

import java.util.Objects;

import io.knotra.ComponentFactory;
import io.knotra.NoConfig;

/**
 * 由 PF4J artifact 导出的受控工厂及其跨边界配置 token。
 *
 * <p>配置 token 必须来自宿主或共享合约包；适配器会在 artifact 发现、类型化解析和挂载
 * 前逐层校验该 token，避免插件私有类型进入 Capability 合约。</p>
 *
 * @param <C> artifact 与宿主共享的配置类型
 */
public record ExportedComponentFactory<C>(
        Class<C> configType,
        ComponentFactory<C> factory) {

    public ExportedComponentFactory {
        Objects.requireNonNull(configType, "configType");
        Objects.requireNonNull(factory, "factory");
    }

    public static <C> ExportedComponentFactory<C> of(
            Class<C> configType,
            ComponentFactory<C> factory) {
        return new ExportedComponentFactory<>(configType, factory);
    }

    public static ExportedComponentFactory<NoConfig> noConfig(
            ComponentFactory<NoConfig> factory) {
        return new ExportedComponentFactory<>(NoConfig.class, factory);
    }
}
