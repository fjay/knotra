package io.knotra.beans;

import io.knotra.CapabilityKey;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Bean 定义中与输出、初始化和清理相关的配置阶段。
 *
 * <p>本阶段同样不可变：{@code provide}、{@code initializer}、清理策略等方法都返回
 * 新实例；{@link #build()} 产出不可变的 {@link BeanDefinition}。清理策略后调用覆盖
 * 先前调用；默认 AUTO 策略优先按 {@code AsyncCloseable} 登记，其次按
 * {@code AutoCloseable} 登记，普通对象不登记清理。</p>
 *
 * @param <C> 组件配置类型
 * @param <T> Bean 类型
 */
public final class BeanOutputStage<C, T> {

    enum LifecycleMode { AUTO, UNMANAGED, CUSTOM_SYNC, CUSTOM_ASYNC }

    record Disposal<T>(
            LifecycleMode mode,
            BeanDisposer<? super T> syncDisposer,
            AsyncBeanDisposer<? super T> asyncDisposer) {

        Disposal {
            Objects.requireNonNull(mode, "mode");
            boolean argumentsMatchMode = switch (mode) {
                case AUTO, UNMANAGED -> syncDisposer == null && asyncDisposer == null;
                case CUSTOM_SYNC -> syncDisposer != null && asyncDisposer == null;
                case CUSTOM_ASYNC -> syncDisposer == null && asyncDisposer != null;
            };
            if (!argumentsMatchMode) {
                throw new IllegalArgumentException("disposer arguments do not match mode: " + mode);
            }
        }

        static <T> Disposal<T> auto() {
            return new Disposal<>(LifecycleMode.AUTO, null, null);
        }
    }

    record Output<T, P>(CapabilityKey<P> key, OutputMapper<? super T, ? extends P> mapper) {
        Output {
            Objects.requireNonNull(key, "key");
        }
    }

    private final String componentId;
    private final Class<C> configType;
    private final List<BeanDependency<?>> dependencies;
    private final ExpertBeanCreator<C, T> creator;
    private final List<Output<T, ?>> outputs;
    private final BeanInitializer<? super T> initializer;
    private final ConfigNormalizer<C> normalizer;
    private final Disposal<T> disposal;

    private BeanOutputStage(
            String componentId,
            Class<C> configType,
            List<BeanDependency<?>> dependencies,
            ExpertBeanCreator<C, T> creator,
            List<Output<T, ?>> outputs,
            BeanInitializer<? super T> initializer,
            ConfigNormalizer<C> normalizer,
            Disposal<T> disposal) {
        this.componentId = componentId;
        this.configType = configType;
        this.dependencies = dependencies;
        this.creator = creator;
        this.outputs = outputs;
        this.initializer = initializer;
        this.normalizer = normalizer;
        this.disposal = disposal;
    }

    static <C, T> BeanOutputStage<C, T> of(
            String componentId,
            Class<C> configType,
            List<BeanDependency<?>> dependencies,
            ExpertBeanCreator<C, T> creator) {
        Objects.requireNonNull(creator, "creator");
        Objects.requireNonNull(configType, "configType");
        return new BeanOutputStage<>(
                componentId,
                configType,
                List.copyOf(dependencies),
                creator,
                List.of(),
                null,
                null,
                Disposal.auto());
    }

    /** 以 Bean 本身作为一个 Capability 输出。 */
    public BeanOutputStage<C, T> provide(CapabilityKey<T> key) {
        Objects.requireNonNull(key, "key");
        return withOutput(new Output<>(key, null));
    }

    /** 以映射结果作为一个 Capability 输出；多个输出随 Activation 原子提交。 */
    public <P> BeanOutputStage<C, T> provideAs(
            CapabilityKey<P> key,
            OutputMapper<? super T, ? extends P> mapper) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(mapper, "mapper");
        return withOutput(new Output<>(key, mapper));
    }

    /** 在 cleanup 登记之后、输出提交之前执行初始化；抛出异常会回滚本次 Activation。 */
    public BeanOutputStage<C, T> initializer(BeanInitializer<? super T> initializer) {
        Objects.requireNonNull(initializer, "initializer");
        return new BeanOutputStage<>(
                componentId, configType, dependencies, creator, outputs, initializer, normalizer, disposal);
    }

    /** 设置类型化配置归一化器；在挂载与 reconfigure 之前执行。 */
    public BeanOutputStage<C, T> normalizeConfig(ConfigNormalizer<C> normalizer) {
        Objects.requireNonNull(normalizer, "normalizer");
        return new BeanOutputStage<>(
                componentId, configType, dependencies, creator, outputs, initializer, normalizer, disposal);
    }

    /** 不为 Bean 登记任何清理动作；Bean 生命周期由创建方自行管理。 */
    public BeanOutputStage<C, T> unmanaged() {
        return new BeanOutputStage<>(
                componentId, configType, dependencies, creator, outputs, initializer, normalizer,
                new Disposal<>(LifecycleMode.UNMANAGED, null, null));
    }

    /** 用自定义同步清理动作替代 AUTO 生命周期推断。 */
    public BeanOutputStage<C, T> destroyWith(BeanDisposer<? super T> disposer) {
        Objects.requireNonNull(disposer, "disposer");
        return new BeanOutputStage<>(
                componentId, configType, dependencies, creator, outputs, initializer, normalizer,
                new Disposal<>(LifecycleMode.CUSTOM_SYNC, disposer, null));
    }

    /** 用自定义异步清理动作替代 AUTO 生命周期推断；settle 会等待返回的 stage。 */
    public BeanOutputStage<C, T> destroyAsyncWith(AsyncBeanDisposer<? super T> disposer) {
        Objects.requireNonNull(disposer, "disposer");
        return new BeanOutputStage<>(
                componentId, configType, dependencies, creator, outputs, initializer, normalizer,
                new Disposal<>(LifecycleMode.CUSTOM_ASYNC, null, disposer));
    }

    /** 冻结为不可变的 {@link BeanDefinition}。 */
    public BeanDefinition<C, T> build() {
        return new BeanDefinition<>(componentId, configType, dependencies, creator, outputs, initializer, normalizer, disposal);
    }

    private BeanOutputStage<C, T> withOutput(Output<T, ?> output) {
        String name = output.key().name();
        for (Output<T, ?> existing : outputs) {
            if (existing.key().name().equals(name)) {
                throw new IllegalArgumentException("duplicate output name: " + name);
            }
        }
        List<Output<T, ?>> next = new ArrayList<>(outputs);
        next.add(output);
        return new BeanOutputStage<>(
                componentId, configType, dependencies, creator, List.copyOf(next), initializer, normalizer, disposal);
    }
}
