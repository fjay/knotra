package io.knotra.loader;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import io.knotra.ComponentFactory;
import io.knotra.ComponentHandle;
import io.knotra.ConfigSchema;
import io.knotra.MountOptions;
import io.knotra.NoConfig;

/**
 * 解析器返回的不透明、已就绪的受控组件定义。
 *
 * <p>定义聚合实现身份、配置 schema、受控挂载策略与重配置策略，是 Loader
 * 与实现来源之间的全部契约：Loader 不直接接触工厂实例或 artifact 句柄，
 * 所有结构挂载都经由 {@link ControlledMountContext}，所有配置变更都经由
 * {@link ReconfigureStrategy}。
 *
 * @param identity 实现身份；身份变化触发替换而不是重配置
 * @param configSchema 配置 schema；null 时回退为“null 折算为 NoConfig.INSTANCE，
 *                     否则原样返回”
 * @param mountStrategy 受控挂载策略
 * @param reconfigureStrategy 重配置策略
 */
public record ResolvedComponentDefinition(
        FactoryIdentity identity,
        ConfigSchema<Object> configSchema,
        ControlledMountStrategy mountStrategy,
        ReconfigureStrategy reconfigureStrategy) {

    public ResolvedComponentDefinition {
        Objects.requireNonNull(identity, "identity");
        configSchema = configSchema == null ? raw -> noConfigOrValue(raw) : configSchema;
        mountStrategy = Objects.requireNonNull(mountStrategy, "mountStrategy");
        reconfigureStrategy = Objects.requireNonNull(reconfigureStrategy, "reconfigureStrategy");
    }

    /** 包装 Core 工厂为直接定义，使用默认挂载选项。 */
    public static ResolvedComponentDefinition of(
            FactoryIdentity identity,
            ComponentFactory<?> factory) {
        return of(identity, factory, MountOptions.DEFAULT);
    }

    /** 包装 Core 工厂与显式配置 schema，使用默认挂载选项。 */
    public static ResolvedComponentDefinition of(
            FactoryIdentity identity,
            ComponentFactory<?> factory,
            ConfigSchema<?> configSchema) {
        return of(identity, factory, configSchema, MountOptions.DEFAULT);
    }

    /** 包装 Core 工厂与显式挂载选项，配置 schema 回退到工厂自带 schema。 */
    public static ResolvedComponentDefinition of(
            FactoryIdentity identity,
            ComponentFactory<?> factory,
            MountOptions options) {
        return of(identity, factory, null, options);
    }

    /**
     * 包装 Core 工厂为直接定义。schema 选择顺序：显式 schema 优先，
     * 其次工厂自带 schema，两者皆无时使用 NoConfig 兜底；挂载直接委托
     * 分配的受控槽位，重配置直接委托句柄自身。
     */
    public static ResolvedComponentDefinition of(
            FactoryIdentity identity,
            ComponentFactory<?> factory,
            ConfigSchema<?> configSchema,
            MountOptions options) {
        Objects.requireNonNull(factory, "factory");
        ConfigSchema<Object> adapted = raw -> {
            ConfigSchema<?> selected = configSchema == null
                    ? factory.configSchema().orElse(null)
                    : configSchema;
            if (selected == null) {
                return noConfigOrValue(raw);
            }
            return selected.validate(raw);
        };
        return new ResolvedComponentDefinition(
                identity,
                adapted,
                directMount(factory, options),
                ReconfigureStrategy.direct());
    }

    /**
     * 校验并归一化原始配置。
     *
     * <p>抛出的异常会被 Loader 记为 CONFIG_INVALID 诊断，并使整批期望树
     * 在任何挂载之前被拒绝。
     */
    public Object normalizeConfig(Object raw) throws Exception {
        return configSchema.validate(raw);
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
        return context.mount(factory, typedConfig, options);
    }

    private static Object noConfigOrValue(Object raw) {
        return raw == null ? NoConfig.INSTANCE : raw;
    }
}
