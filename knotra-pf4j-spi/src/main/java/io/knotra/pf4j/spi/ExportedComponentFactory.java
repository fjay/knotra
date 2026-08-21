package io.knotra.pf4j.spi;

import java.util.Objects;

import io.knotra.ComponentFactory;
import io.knotra.ConfigDecoder;
import io.knotra.NoConfig;

/**
 * 由 PF4J artifact 导出的受控工厂及其跨边界配置合约。
 *
 * <p>配置 token 与 decoder 都来自宿主共享的 Core API；token 声明挂载合约，
 * decoder 负责把宿主持有的 raw 配置转换成该 token 的实例。组件工厂自身只处理
 * 已经类型化的配置归一化。</p>
 *
 * @param <C> artifact 与宿主共享的配置类型
 */
public record ExportedComponentFactory<C>(
        Class<C> configType,
        ConfigDecoder<C> decoder,
        ComponentFactory<C> factory) {

    public ExportedComponentFactory {
        Objects.requireNonNull(configType, "configType");
        Objects.requireNonNull(decoder, "decoder");
        Objects.requireNonNull(factory, "factory");
    }

    public static <C> ExportedComponentFactory<C> of(
            Class<C> configType,
            ComponentFactory<C> factory) {
        return new ExportedComponentFactory<>(configType, ConfigDecoder.typed(configType), factory);
    }

    public static <C> ExportedComponentFactory<C> of(
            Class<C> configType,
            ConfigDecoder<C> decoder,
            ComponentFactory<C> factory) {
        return new ExportedComponentFactory<>(configType, decoder, factory);
    }

    public static ExportedComponentFactory<NoConfig> noConfig(
            ComponentFactory<NoConfig> factory) {
        return new ExportedComponentFactory<>(
                NoConfig.class,
                ConfigDecoder.noConfig(),
                factory);
    }
}
