package io.knotra.loader;

import java.util.Objects;
import java.util.concurrent.CompletionStage;

import io.knotra.ComponentFactory;
import io.knotra.ConfigDecoder;
import io.knotra.MountHandle;
import io.knotra.MountOptions;
import io.knotra.NoConfig;

/**
 * 解析器返回的不透明、已就绪的受控工厂定义。
 *
 * <p>定义聚合实现身份、配置能力、raw 配置 decoder、受控挂载策略与重配置策略，
 * 是 Loader 与实现来源之间的全部契约：Loader 不直接接触工厂实例或 artifact 句柄，
 * 所有结构挂载都经由 {@link ControlledMountContext}，所有配置变更都经由
 * {@link ReconfigureStrategy}。配置能力由 {@link FactoryKind} 声明，不能从
 * Core 返回的 handle 运行时类型反推。</p>
 *
 * @param identity 实现身份；身份变化触发替换而不是重配置
 * @param factoryKind 配置能力来源；plain 工厂没有公开配置契约
 * @param configDecoder raw 配置 decoder；plain 固定为 NoConfig decoder
 * @param mountStrategy 受控挂载策略
 * @param reconfigureStrategy 重配置策略
 */
public record ResolvedFactory(
        FactoryIdentity identity,
        FactoryKind factoryKind,
        ConfigDecoder<Object> configDecoder,
        ControlledMountStrategy mountStrategy,
        ReconfigureStrategy reconfigureStrategy) {

    /** Resolver 声明的配置能力，是 Loader 选择 mount/reconfigure 路径的唯一依据。 */
    public enum FactoryKind {
        PLAIN, CONFIGURED
    }

    public ResolvedFactory {
        Objects.requireNonNull(identity, "identity");
        factoryKind = Objects.requireNonNull(factoryKind, "factoryKind");
        if (factoryKind == FactoryKind.PLAIN) {
            configDecoder = raw -> ConfigDecoder.noConfig().decode(raw);
        } else {
            configDecoder = Objects.requireNonNull(configDecoder, "configDecoder");
        }
        mountStrategy = Objects.requireNonNull(mountStrategy, "mountStrategy");
        reconfigureStrategy = Objects.requireNonNull(reconfigureStrategy, "reconfigureStrategy");
    }

    /** 返回该实现是否声明了公开配置契约。 */
    public boolean configured() {
        return factoryKind == FactoryKind.CONFIGURED;
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
                FactoryKind.PLAIN,
                null,
                directNoConfigMount(factory, options),
                ReconfigureStrategy.unsupportedPlain());
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
     * 执行一次。Loader 记账中的配置以 Object 流转，类型恢复只发生在
     * {@link ConfiguredBoundary} 这个擦除适配点。</p>
     */
    public static <C> ResolvedFactory of(
            FactoryIdentity identity,
            ComponentFactory<C> factory,
            ConfigDecoder<C> configDecoder,
            MountOptions options) {
        Objects.requireNonNull(factory, "factory");
        Objects.requireNonNull(configDecoder, "configDecoder");
        ConfigDecoder<Object> adapted = configDecoder::decode;
        return new ResolvedFactory(
                identity,
                FactoryKind.CONFIGURED,
                adapted,
                directConfiguredMount(factory, options),
                ReconfigureStrategy.direct(FactoryKind.CONFIGURED));
    }

    /**
     * 在结构修改前解码 raw 配置。plain 定义只接受 null/NoConfig；
     * 配置型定义的 decoder 失败会成为 CONFIG_INVALID。
     */
    public Object decodeConfig(Object raw) throws Exception {
        return configDecoder.decode(raw);
    }

    private static ControlledMountStrategy directNoConfigMount(
            ComponentFactory<NoConfig> factory,
            MountOptions options) {
        return (context, config) -> context.mountAsync(factory, options);
    }

    private static <C> ControlledMountStrategy directConfiguredMount(
            ComponentFactory<C> factory,
            MountOptions options) {
        return (context, config) -> mountConfigured(context, factory, options, config);
    }

    private static <C> CompletionStage<MountHandle> mountConfigured(
            ControlledMountContext context,
            ComponentFactory<C> factory,
            MountOptions options,
            Object config) {
        return context.mountAsync(factory, ConfiguredBoundary.coerce(config), options)
                .thenApply(MountHandle.class::cast);
    }
}
