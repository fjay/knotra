package io.knotra.beans;

import io.knotra.CapabilityKey;
import io.knotra.CapabilityRequirement;
import io.knotra.ConfiguredMountHandle;
import io.knotra.DynamicCapability;
import io.knotra.KnotraRuntime;
import io.knotra.MountHandle;
import io.knotra.MountOptions;
import io.knotra.NoConfig;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 将普通 Java 对象适配为受 Knotra 激活生命周期托管的类型安全 Bean DSL。
 *
 * <p>所有 Builder 阶段均为不可变（Immutable），一次构建生成的 {@link BeanDefinition} 可重复挂载。</p>
 */
public final class Beans {

    private Beans() {
    }

    /** Bean 的生命周期托管模式。 */
    public enum LifecycleMode {
        /** 自动模式：若对象实现了 AutoCloseable 则自动注册关闭钩子。 */
        AUTO,
        /** 非托管模式：不注册任何生命周期回调。 */
        UNMANAGED,
        /** 自定义同步销毁模式。 */
        CUSTOM_SYNC,
        /** 自定义异步销毁模式。 */
        CUSTOM_ASYNC
    }

    @FunctionalInterface
    public interface Creator0<T> {
        T create() throws Exception;
    }

    @FunctionalInterface
    public interface Creator<T> {
        T create(BeanDependencies dependencies) throws Exception;
    }

    @FunctionalInterface
    public interface ConfigCreator0<C, T> {
        T create(C config) throws Exception;
    }

    @FunctionalInterface
    public interface ConfigCreator<C, T> {
        T create(C config, BeanDependencies dependencies) throws Exception;
    }

    @FunctionalInterface
    public interface ExpertCreator<T> {
        T create(io.knotra.ActivationContext context) throws Exception;
    }

    @FunctionalInterface
    public interface ConfigExpertCreator<C, T> {
        T create(io.knotra.ActivationContext context, C config) throws Exception;
    }

    @FunctionalInterface
    public interface Initializer<T> {
        void initialize(T bean) throws Exception;
    }

    @FunctionalInterface
    public interface Disposer<T> {
        void dispose(T bean) throws Exception;
    }

    @FunctionalInterface
    public interface AsyncDisposer<T> {
        java.util.concurrent.CompletionStage<Void> disposeAsync(T bean) throws Exception;
    }

    @FunctionalInterface
    public interface Normalizer<C> {
        C normalize(C config) throws Exception;
    }

    @FunctionalInterface
    public interface OutputMapper<T, P> {
        P map(T bean) throws Exception;
    }

    public static final class Builder {
        private final String componentId;
        private final List<BeanDependency<?>> dependencies;

        Builder(String componentId) {
            this(componentId, List.of());
        }

        private Builder(String componentId, List<BeanDependency<?>> dependencies) {
            this.componentId = componentId;
            this.dependencies = dependencies;
        }

        public Builder with(BeanDependency<?> dependency) {
            Objects.requireNonNull(dependency, "dependency");
            List<BeanDependency<?>> next = new ArrayList<>(this.dependencies.size() + 1);
            next.addAll(this.dependencies);
            next.add(dependency);
            return new Builder(componentId, List.copyOf(next));
        }

        public Builder with(BeanDependency<?>... dependencies) {
            Objects.requireNonNull(dependencies, "dependencies");
            List<BeanDependency<?>> next = new ArrayList<>(this.dependencies.size() + dependencies.length);
            next.addAll(this.dependencies);
            for (BeanDependency<?> dependency : dependencies) {
                next.add(Objects.requireNonNull(dependency, "dependency"));
            }
            return new Builder(componentId, List.copyOf(next));
        }

        public Builder with(Collection<? extends BeanDependency<?>> dependencies) {
            Objects.requireNonNull(dependencies, "dependencies");
            List<BeanDependency<?>> next = new ArrayList<>(this.dependencies.size() + dependencies.size());
            next.addAll(this.dependencies);
            for (BeanDependency<?> dependency : dependencies) {
                next.add(Objects.requireNonNull(dependency, "dependency"));
            }
            return new Builder(componentId, List.copyOf(next));
        }

        public <T> OutputStage<T> create(Creator0<T> creator) {
            Objects.requireNonNull(creator, "creator");
            return new OutputStage<>(new BeanStage<>(
                    componentId,
                    NoConfig.class,
                    dependencies,
                    (context, config) -> creator.create()));
        }

        public <T> OutputStage<T> create(Creator<T> creator) {
            Objects.requireNonNull(creator, "creator");
            return new OutputStage<>(new BeanStage<>(
                    componentId,
                    NoConfig.class,
                    dependencies,
                    (context, config) -> creator.create(
                            new DefaultBeanDependencies(componentId, context, dependencies))));
        }
    }

    public static final class ConfigBuilder<C> {
        private final String componentId;
        private final Class<C> configType;
        private final List<BeanDependency<?>> dependencies;

        ConfigBuilder(String componentId, Class<C> configType) {
            this(componentId, configType, List.of());
        }

        private ConfigBuilder(String componentId, Class<C> configType, List<BeanDependency<?>> dependencies) {
            this.componentId = componentId;
            this.configType = Objects.requireNonNull(configType, "configType");
            this.dependencies = dependencies;
        }

        public ConfigBuilder<C> with(BeanDependency<?> dependency) {
            Objects.requireNonNull(dependency, "dependency");
            List<BeanDependency<?>> next = new ArrayList<>(this.dependencies.size() + 1);
            next.addAll(this.dependencies);
            next.add(dependency);
            return new ConfigBuilder<>(componentId, configType, List.copyOf(next));
        }

        public ConfigBuilder<C> with(BeanDependency<?>... dependencies) {
            Objects.requireNonNull(dependencies, "dependencies");
            List<BeanDependency<?>> next = new ArrayList<>(this.dependencies.size() + dependencies.length);
            next.addAll(this.dependencies);
            for (BeanDependency<?> dependency : dependencies) {
                next.add(Objects.requireNonNull(dependency, "dependency"));
            }
            return new ConfigBuilder<>(componentId, configType, List.copyOf(next));
        }

        public ConfigBuilder<C> with(Collection<? extends BeanDependency<?>> dependencies) {
            Objects.requireNonNull(dependencies, "dependencies");
            List<BeanDependency<?>> next = new ArrayList<>(this.dependencies.size() + dependencies.size());
            next.addAll(this.dependencies);
            for (BeanDependency<?> dependency : dependencies) {
                next.add(Objects.requireNonNull(dependency, "dependency"));
            }
            return new ConfigBuilder<>(componentId, configType, List.copyOf(next));
        }

        public <T> ConfigOutputStage<C, T> create(ConfigCreator0<C, T> creator) {
            Objects.requireNonNull(creator, "creator");
            return new ConfigOutputStage<>(new BeanStage<>(
                    componentId,
                    configType,
                    dependencies,
                    (context, config) -> creator.create(config)));
        }

        public <T> ConfigOutputStage<C, T> create(ConfigCreator<C, T> creator) {
            Objects.requireNonNull(creator, "creator");
            return new ConfigOutputStage<>(new BeanStage<>(
                    componentId,
                    configType,
                    dependencies,
                    (context, config) -> creator.create(
                            config,
                            new DefaultBeanDependencies(componentId, context, dependencies))));
        }
    }

    public static final class OutputStage<T> {
        private final OutputStageSupport<NoConfig, T> outputs;

        OutputStage(BeanStage<NoConfig, T> stage) {
            this.outputs = new OutputStageSupport<>(stage);
        }

        public OutputStage<T> provide(CapabilityKey<T> key) {
            return new OutputStage<>(outputs.provide(key).stage());
        }

        public OutputStage<T> provide(Class<T> type) {
            return new OutputStage<>(outputs.provide(type).stage());
        }

        public <P> OutputStage<T> provideAs(Class<P> type) {
            return new OutputStage<>(outputs.provideAs(type).stage());
        }

        public <P> OutputStage<T> provideAs(CapabilityKey<P> key) {
            return new OutputStage<>(outputs.provideAs(key).stage());
        }

        public <P> OutputStage<T> provideAs(
                CapabilityKey<P> key,
                OutputMapper<? super T, ? extends P> mapper) {
            return new OutputStage<>(outputs.provideAs(key, mapper).stage());
        }

        public <P> OutputStage<T> provideAs(
                Class<P> type,
                OutputMapper<? super T, ? extends P> mapper) {
            return new OutputStage<>(outputs.provideAs(type, mapper).stage());
        }

        public OutputStage<T> initializer(Initializer<? super T> initializer) {
            return new OutputStage<>(outputs.initializer(initializer).stage());
        }

        public OutputStage<T> unmanaged() {
            return new OutputStage<>(outputs.unmanaged().stage());
        }

        public OutputStage<T> destroyWith(Disposer<? super T> disposer) {
            return new OutputStage<>(outputs.destroyWith(disposer).stage());
        }

        public OutputStage<T> destroyAsyncWith(AsyncDisposer<? super T> disposer) {
            return new OutputStage<>(outputs.destroyAsyncWith(disposer).stage());
        }

        public BeanDefinition<T> build() {
            return new BeanDefinition<>(BeanStage.mountFactory(outputs.stage()));
        }

        public MountHandle mount(KnotraRuntime runtime) {
            return build().mount(runtime);
        }

        public MountHandle mount(KnotraRuntime runtime, String mountId) {
            return build().mount(runtime, mountId);
        }

        public MountHandle mount(KnotraRuntime runtime, MountOptions options) {
            return build().mount(runtime, options);
        }

        public MountHandle mount(KnotraRuntime runtime, String mountId, MountOptions options) {
            return build().mount(runtime, mountId, options);
        }
    }

    public static final class ConfigOutputStage<C, T> {
        private final OutputStageSupport<C, T> outputs;

        ConfigOutputStage(BeanStage<C, T> stage) {
            this.outputs = new OutputStageSupport<>(stage);
        }

        public ConfigOutputStage<C, T> provide(CapabilityKey<T> key) {
            return new ConfigOutputStage<>(outputs.provide(key).stage());
        }

        public ConfigOutputStage<C, T> provide(Class<T> type) {
            return new ConfigOutputStage<>(outputs.provide(type).stage());
        }

        public <P> ConfigOutputStage<C, T> provideAs(Class<P> type) {
            return new ConfigOutputStage<>(outputs.provideAs(type).stage());
        }

        public <P> ConfigOutputStage<C, T> provideAs(CapabilityKey<P> key) {
            return new ConfigOutputStage<>(outputs.provideAs(key).stage());
        }

        public <P> ConfigOutputStage<C, T> provideAs(
                CapabilityKey<P> key,
                OutputMapper<? super T, ? extends P> mapper) {
            return new ConfigOutputStage<>(outputs.provideAs(key, mapper).stage());
        }

        public <P> ConfigOutputStage<C, T> provideAs(
                Class<P> type,
                OutputMapper<? super T, ? extends P> mapper) {
            return new ConfigOutputStage<>(outputs.provideAs(type, mapper).stage());
        }

        public ConfigOutputStage<C, T> initializer(Initializer<? super T> initializer) {
            return new ConfigOutputStage<>(outputs.initializer(initializer).stage());
        }

        public ConfigOutputStage<C, T> normalizeConfig(Normalizer<C> normalizer) {
            return new ConfigOutputStage<>(outputs.normalizeConfig(normalizer).stage());
        }

        public ConfigOutputStage<C, T> unmanaged() {
            return new ConfigOutputStage<>(outputs.unmanaged().stage());
        }

        public ConfigOutputStage<C, T> destroyWith(Disposer<? super T> disposer) {
            return new ConfigOutputStage<>(outputs.destroyWith(disposer).stage());
        }

        public ConfigOutputStage<C, T> destroyAsyncWith(AsyncDisposer<? super T> disposer) {
            return new ConfigOutputStage<>(outputs.destroyAsyncWith(disposer).stage());
        }

        public ConfiguredBeanDefinition<C, T> build() {
            return new ConfiguredBeanDefinition<>(outputs.stage().build());
        }

        public ConfiguredMountHandle<C> mount(KnotraRuntime runtime, C config) {
            return build().mount(runtime, config);
        }

        public ConfiguredMountHandle<C> mount(KnotraRuntime runtime, String mountId, C config) {
            return build().mount(runtime, mountId, config);
        }

        public ConfiguredMountHandle<C> mount(KnotraRuntime runtime, C config, MountOptions options) {
            return build().mount(runtime, config, options);
        }

        public ConfiguredMountHandle<C> mount(
                KnotraRuntime runtime,
                String mountId,
                C config,
                MountOptions options) {
            return build().mount(runtime, mountId, config, options);
        }
    }

    /** 声明必需的固定依赖项（启动时必须就绪，提供方被替换将导致 Bean 重新激活）。 */
    public static <T> BeanDependency<T> fixed(CapabilityKey<T> key) {
        Objects.requireNonNull(key, "key");
        return BeanDependency.of(CapabilityRequirement.required(key), context -> context.require(key));
    }

    /** 基于类型声明必需的固定依赖项。 */
    public static <T> BeanDependency<T> fixed(Class<T> type) {
        return fixed(CapabilityKey.of(type));
    }

    /** 声明可选的固定依赖项。 */
    public static <T> BeanDependency<Optional<T>> fixedOptional(CapabilityKey<T> key) {
        Objects.requireNonNull(key, "key");
        return BeanDependency.of(CapabilityRequirement.optional(key), context -> context.find(key));
    }

    /** 基于类型声明可选的固定依赖项。 */
    public static <T> BeanDependency<Optional<T>> fixedOptional(Class<T> type) {
        return fixedOptional(CapabilityKey.of(type));
    }

    /**
     * 声明必需的动态接口代理依赖项。
     *
     * <p>仅在组件首次启动激活时校验提供方是否存在；一旦处于 ACTIVE 状态，提供方缺失或热替换不会重启该 Bean，
     * 每次方法调用会自动获取提供方租约并透明路由。</p>
     */
    public static <T> BeanDependency<T> dynamic(CapabilityKey<T> key) {
        requireProxyInterface(key);
        return BeanDependency.of(
                CapabilityRequirement.dynamicRequired(key),
                context -> context.subscribe(key).proxy(key.type()));
    }

    /** 基于接口类型声明必需的动态代理依赖项。 */
    public static <T> BeanDependency<T> dynamic(Class<T> type) {
        return dynamic(CapabilityKey.of(type));
    }

    /** 声明可选的动态接口代理依赖项。 */
    public static <T> BeanDependency<T> dynamicOptional(CapabilityKey<T> key) {
        requireProxyInterface(key);
        return BeanDependency.of(
                CapabilityRequirement.dynamicOptional(key),
                context -> context.subscribe(key).proxy(key.type()));
    }

    /** 基于接口类型声明可选的动态代理依赖项。 */
    public static <T> BeanDependency<T> dynamicOptional(Class<T> type) {
        return dynamicOptional(CapabilityKey.of(type));
    }

    /**
     * 声明显式的必需动态能力句柄依赖项（DynamicCapability）。
     *
     * <p>供需要在一个原子租约（Lease）内连续调用多次方法的高级场景使用。</p>
     */
    public static <T> BeanDependency<DynamicCapability<T>> dynamicCapability(
            CapabilityKey<T> key) {
        Objects.requireNonNull(key, "key");
        return BeanDependency.of(
                CapabilityRequirement.dynamicRequired(key),
                context -> context.subscribe(key));
    }

    /** 基于类型声明显式的必需动态能力句柄依赖项。 */
    public static <T> BeanDependency<DynamicCapability<T>> dynamicCapability(Class<T> type) {
        return dynamicCapability(CapabilityKey.of(type));
    }

    /** 声明显式的可选动态能力句柄依赖项。 */
    public static <T> BeanDependency<DynamicCapability<T>> dynamicCapabilityOptional(
            CapabilityKey<T> key) {
        Objects.requireNonNull(key, "key");
        return BeanDependency.of(
                CapabilityRequirement.dynamicOptional(key),
                context -> context.subscribe(key));
    }

    /** 基于类型声明显式的可选动态能力句柄依赖项。 */
    public static <T> BeanDependency<DynamicCapability<T>> dynamicCapabilityOptional(
            Class<T> type) {
        return dynamicCapabilityOptional(CapabilityKey.of(type));
    }

    /** 创建无配置 Bean 定义的流式构建器。 */
    public static Builder component(String componentId) {
        return new Builder(requireComponentId(componentId));
    }

    /** 创建带类型化配置的 Bean 定义流式构建器。 */
    public static <C> ConfigBuilder<C> component(String componentId, Class<C> configType) {
        return new ConfigBuilder<>(requireComponentId(componentId), configType);
    }

    public static <T> OutputStage<T> expert(
            String componentId,
            List<BeanDependency<?>> dependencies,
            ExpertCreator<T> creator) {
        Objects.requireNonNull(creator, "creator");
        return new OutputStage<>(new BeanStage<NoConfig, T>(
                requireComponentId(componentId),
                NoConfig.class,
                dependencies,
                (context, config) -> creator.create(context)));
    }

    public static <C, T> ConfigOutputStage<C, T> expert(
            String componentId,
            Class<C> configType,
            List<BeanDependency<?>> dependencies,
            ConfigExpertCreator<C, T> creator) {
        return new ConfigOutputStage<>(new BeanStage<>(
                requireComponentId(componentId),
                configType,
                dependencies,
                creator));
    }

    public static <T> MountHandle mount(
            KnotraRuntime runtime,
            BeanDefinition<T> definition) {
        return mount(runtime, definition, definition.componentId());
    }

    public static <T> MountHandle mount(
            KnotraRuntime runtime,
            BeanDefinition<T> definition,
            String mountId) {
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(mountId, "mountId");
        return runtime.mount(mountId, definition.asFactory());
    }

    public static <T> MountHandle mount(
            KnotraRuntime runtime,
            BeanDefinition<T> definition,
            MountOptions options) {
        return mount(runtime, definition, definition.componentId(), options);
    }

    public static <T> MountHandle mount(
            KnotraRuntime runtime,
            BeanDefinition<T> definition,
            String mountId,
            MountOptions options) {
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(mountId, "mountId");
        return runtime.mount(mountId, definition.asFactory(), options);
    }

    public static <C, T> ConfiguredMountHandle<C> mount(
            KnotraRuntime runtime,
            ConfiguredBeanDefinition<C, T> definition,
            C config) {
        return mount(runtime, definition, definition.componentId(), config);
    }

    public static <C, T> ConfiguredMountHandle<C> mount(
            KnotraRuntime runtime,
            ConfiguredBeanDefinition<C, T> definition,
            String mountId,
            C config) {
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(mountId, "mountId");
        return runtime.mount(mountId, definition.asFactory(), config);
    }

    public static <C, T> ConfiguredMountHandle<C> mount(
            KnotraRuntime runtime,
            ConfiguredBeanDefinition<C, T> definition,
            C config,
            MountOptions options) {
        return mount(runtime, definition, definition.componentId(), config, options);
    }

    public static <C, T> ConfiguredMountHandle<C> mount(
            KnotraRuntime runtime,
            ConfiguredBeanDefinition<C, T> definition,
            String mountId,
            C config,
            MountOptions options) {
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(mountId, "mountId");
        return runtime.mount(mountId, definition.asFactory(), config, options);
    }

    static String requireComponentId(String componentId) {
        Objects.requireNonNull(componentId, "componentId");
        String trimmed = componentId.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("componentId must not be blank");
        }
        return trimmed;
    }

    private static void requireProxyInterface(CapabilityKey<?> key) {
        Objects.requireNonNull(key, "key");
        if (!key.type().isInterface()) {
            throw new IllegalArgumentException(
                    "dynamic proxy capability must be an interface: " + key.typeName());
        }
    }
}
