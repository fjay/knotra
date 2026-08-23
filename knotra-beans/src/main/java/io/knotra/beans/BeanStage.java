package io.knotra.beans;

import io.knotra.NoConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Bean 构建过程中的不可变中间状态。
 *
 * <p>本类只承载状态与单项配置冲突检查；名称不变量和不可变列表的所有权统一归
 * {@link BeanDefinitionSupport}。</p>
 */
final class BeanStage<C, T> {

    private final String componentId;
    private final Class<C> configType;
    private final List<BeanDependency<?>> dependencies;
    private final Beans.ConfigExpertCreator<C, T> creator;
    private final List<BeanOutput<T, ?>> outputs;
    private final Beans.Initializer<? super T> initializer;
    private final Beans.Normalizer<C> normalizer;
    private final BeanDisposal<T> disposal;

    BeanStage(
            String componentId,
            Class<C> configType,
            List<BeanDependency<?>> dependencies,
            Beans.ConfigExpertCreator<C, T> creator) {
        this(
                componentId,
                configType,
                dependencies,
                creator,
                List.of(),
                null,
                null,
                BeanDisposal.auto());
    }

    private BeanStage(
            String componentId,
            Class<C> configType,
            List<BeanDependency<?>> dependencies,
            Beans.ConfigExpertCreator<C, T> creator,
            List<BeanOutput<T, ?>> outputs,
            Beans.Initializer<? super T> initializer,
            Beans.Normalizer<C> normalizer,
            BeanDisposal<T> disposal) {
        this.componentId = componentId;
        this.configType = configType;
        this.dependencies = dependencies;
        this.creator = creator;
        this.outputs = outputs;
        this.initializer = initializer;
        this.normalizer = normalizer;
        this.disposal = disposal;
    }

    BeanStage<C, T> withOutput(BeanOutput<T, ?> output) {
        Objects.requireNonNull(output, "output");
        List<BeanOutput<T, ?>> next = new ArrayList<>(outputs);
        next.add(output);
        return new BeanStage<>(
                componentId, configType, dependencies, creator, next,
                initializer, normalizer, disposal);
    }

    BeanStage<C, T> withInitializer(Beans.Initializer<? super T> next) {
        Objects.requireNonNull(next, "initializer");
        rejectExisting(initializer, "initializer");
        return new BeanStage<>(componentId, configType, dependencies, creator, outputs,
                next, normalizer, disposal);
    }

    BeanStage<C, T> withNormalizer(Beans.Normalizer<C> next) {
        Objects.requireNonNull(next, "normalizer");
        rejectExisting(normalizer, "config normalizer");
        return new BeanStage<>(componentId, configType, dependencies, creator, outputs,
                initializer, next, disposal);
    }

    BeanStage<C, T> withDisposal(BeanDisposal<T> next) {
        Objects.requireNonNull(next, "disposal");
        rejectExisting(disposal.mode() == Beans.LifecycleMode.AUTO
                ? null
                : disposal, "disposal");
        return new BeanStage<>(componentId, configType, dependencies, creator, outputs,
                initializer, normalizer, next);
    }

    BeanDefinitionSupport<C, T> build() {
        return new BeanDefinitionSupport<>(
                componentId, configType, dependencies, creator, outputs,
                initializer, normalizer, disposal);
    }

    static <T> NoConfigBeanDefinitionSupport<T> mountFactory(BeanStage<NoConfig, T> stage) {
        return new NoConfigBeanDefinitionSupport<>(
                stage.componentId,
                stage.dependencies,
                stage.creator,
                stage.outputs,
                stage.initializer,
                stage.disposal);
    }

    private static void rejectExisting(Object existing, String configurationName) {
        if (existing != null) {
            throw new IllegalStateException(configurationName + " has already been configured");
        }
    }
}
