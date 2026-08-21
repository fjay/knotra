package io.knotra.loader;

import java.util.Objects;
import java.util.concurrent.CompletionStage;
import io.knotra.ComponentFactory;
import io.knotra.ComponentHandle;
import io.knotra.ConfigDecoder;
import io.knotra.MountOptions;
import io.knotra.NoConfig;

/**
 * 解析器返回的不透明、已就绪的受控工厂定义。
 *
 * <p>定义聚合实现身份、raw 配置 decoder、受控挂载策略与重配置策略，是 Loader
 * 与实现来源之间的全部契约：Loader 不直接接触工厂实例或 artifact 句柄，
 * 所有结构挂载都经由 {@link ControlledMountContext}，所有配置变更都经由
 * {@link ReconfigureStrategy}。</p>
 *
 * @param identity 实现身份；身份变化触发替换而不是重配置
 * @param configDecoder raw 配置 decoder；null 折算为 NoConfig decoder
 * @param mountStrategy 受控挂载策略
 * @param reconfigureStrategy 重配置策略
 */
public record ResolvedFactory(
        FactoryIdentity identity,
        ConfigDecoder<Object> configDecoder,
        ControlledMountStrategy mountStrategy,
        ReconfigureStrategy reconfigureStrategy) {

    public ResolvedFactory {
        Objects.requireNonNull(identity, "identity");
        configDecoder = configDecoder == null ? raw -> ConfigDecoder.noConfig().decode(raw) : configDecoder;
        mountStrategy = Objects.requireNonNull(mountStrategy, "mountStrategy");
        reconfigureStrategy = Objects.requireNonNull(reconfigureStrategy, "reconfigureStrategy");
    }

    /** 包装无配置 Core 工厂，使用默认挂载选项。 */
    public static ResolvedFactory of(
            FactoryIdentity identity,
            ComponentFactory<NoConfig> factory) {
        return of(identity, factory, MountOptions.DEFAULT);
    }

    /** 包装无配置 Core 工厂与显式挂载选项。 */
    public static ResolvedFactory of(
            FactoryIdentity identity,
            ComponentFactory<NoConfig> factory,
            MountOptions options) {
        Objects.requireNonNull(factory, "factory");
        return new ResolvedFactory(
                identity,
                raw -> ConfigDecoder.noConfig().decode(raw),
                directMount(factory, options),
                ReconfigureStrategy.direct());
    }

    /** 包装 Core 工厂与显式 decoder，使用默认挂载选项。 */
    public static <C> ResolvedFactory of(
            FactoryIdentity identity,
            ComponentFactory<C> factory,
            ConfigDecoder<C> configDecoder) {
        return of(identity, factory, configDecoder, MountOptions.DEFAULT);
    }

    /**
     * 包装 Core 工厂、显式 decoder 与挂载选项。
     *
     * <p>Raw 值只由 decoder 转换为 C；typed normalizer 留给 Core mount/reconfigure
     * 执行一次。泛型捕获只发生在这个静态入口，后续 expert SPI 以 Object 流转。</p>
     */
    public static <C> ResolvedFactory of(
            FactoryIdentity identity,
            ComponentFactory<C> factory,
            ConfigDecoder<C> configDecoder,
            MountOptions options) {
        Objects.requireNonNull(factory, "factory");
        Objects.requireNonNull(configDecoder, "configDecoder");
        ConfigDecoder<Object> adapted = raw -> configDecoder.decode(raw);
        return new ResolvedFactory(
                identity,
                adapted,
                directMount(factory, options),
                ReconfigureStrategy.direct());
    }

    /**
     * 在结构修改前解码 raw 配置。Decoder 失败会成为 CONFIG_INVALID，并使批次在挂载前拒绝；
     * factory typed normalizer 随后的 Core mount/reconfigure 执行。
     */
    public Object decodeConfig(Object raw) throws Exception {
        return configDecoder.decode(raw);
    }

    private static ControlledMountStrategy directMount(
            ComponentFactory<?> factory,
            MountOptions options) {
        return (context, config) -> {
            CompletionStage<?> mounted = mountDirect(context, factory, options, config);
            @SuppressWarnings("unchecked")
            CompletionStage<ComponentHandle<?>> result =
                    (CompletionStage<ComponentHandle<?>>) mounted;
            return result;
        };
    }

    private static <C> CompletionStage<ComponentHandle<C>> mountDirect(
            ControlledMountContext context,
            ComponentFactory<C> factory,
            MountOptions options,
            Object config) {
        @SuppressWarnings("unchecked")
        C typedConfig = (C) config;
        return context.mountAsync(factory, typedConfig, options);
    }

}
