package io.knotra.beans;

import io.knotra.CapabilityKey;

import java.util.Objects;

/**
 * 输出与生命周期配置的包内通用实现。
 *
 * <p>公开的无配置/配置阶段只保留类型正确的薄包装；名称唯一性统一由
 * {@link BeanDefinitionSupport} 在最终构造定义时校验。</p>
 */
final class OutputStageSupport<C, T> {

    private final BeanStage<C, T> stage;

    OutputStageSupport(BeanStage<C, T> stage) {
        this.stage = Objects.requireNonNull(stage, "stage");
    }

    BeanStage<C, T> stage() {
        return stage;
    }

    OutputStageSupport<C, T> provide(CapabilityKey<T> key) {
        Objects.requireNonNull(key, "key");
        return new OutputStageSupport<>(stage.withOutput(new BeanOutput<>(key, null)));
    }

    OutputStageSupport<C, T> provide(Class<T> type) {
        Objects.requireNonNull(type, "type");
        return provide(CapabilityKey.of(type));
    }

    <P> OutputStageSupport<C, T> provideAs(Class<P> type) {
        Objects.requireNonNull(type, "type");
        return provideAs(type, type::cast);
    }

    <P> OutputStageSupport<C, T> provideAs(CapabilityKey<P> key) {
        Objects.requireNonNull(key, "key");
        return provideAs(key, key.type()::cast);
    }

    <P> OutputStageSupport<C, T> provideAs(
            CapabilityKey<P> key,
            Beans.OutputMapper<? super T, ? extends P> mapper) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(mapper, "mapper");
        return new OutputStageSupport<>(stage.withOutput(new BeanOutput<>(key, mapper)));
    }

    <P> OutputStageSupport<C, T> provideAs(
            Class<P> type,
            Beans.OutputMapper<? super T, ? extends P> mapper) {
        Objects.requireNonNull(type, "type");
        return provideAs(CapabilityKey.of(type), mapper);
    }

    OutputStageSupport<C, T> initializer(Beans.Initializer<? super T> next) {
        Objects.requireNonNull(next, "initializer");
        return new OutputStageSupport<>(stage.withInitializer(next));
    }

    OutputStageSupport<C, T> normalizeConfig(Beans.Normalizer<C> next) {
        Objects.requireNonNull(next, "normalizer");
        return new OutputStageSupport<>(stage.withNormalizer(next));
    }

    OutputStageSupport<C, T> unmanaged() {
        return new OutputStageSupport<>(stage.withDisposal(BeanDisposal.unmanaged()));
    }

    OutputStageSupport<C, T> destroyWith(Beans.Disposer<? super T> disposer) {
        Objects.requireNonNull(disposer, "disposer");
        return new OutputStageSupport<>(stage.withDisposal(BeanDisposal.sync(disposer)));
    }

    OutputStageSupport<C, T> destroyAsyncWith(Beans.AsyncDisposer<? super T> disposer) {
        Objects.requireNonNull(disposer, "disposer");
        return new OutputStageSupport<>(stage.withDisposal(BeanDisposal.async(disposer)));
    }
}
