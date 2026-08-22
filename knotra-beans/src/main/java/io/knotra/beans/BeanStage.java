package io.knotra.beans;

import io.knotra.NoConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

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
        this.componentId = Beans.requireComponentId(componentId);
        this.configType = Objects.requireNonNull(configType, "configType");
        this.dependencies = List.copyOf(dependencies);
        this.creator = Objects.requireNonNull(creator, "creator");
        this.outputs = List.copyOf(outputs);
        this.initializer = initializer;
        this.normalizer = normalizer;
        this.disposal = disposal;
    }

    BeanStage<C, T> withOutput(BeanOutput<T, ?> output) {
        Objects.requireNonNull(output, "output");
        String name = output.key().name();
        for (BeanOutput<T, ?> existing : outputs) {
            if (existing.key().name().equals(name)) {
                throw new IllegalArgumentException("duplicate output name: " + name);
            }
        }
        List<BeanOutput<T, ?>> next = new ArrayList<>(outputs);
        next.add(output);
        return new BeanStage<>(
                componentId, configType, dependencies, creator, List.copyOf(next),
                initializer, normalizer, disposal);
    }

    BeanStage<C, T> withInitializer(Beans.Initializer<? super T> next) {
        return new BeanStage<>(componentId, configType, dependencies, creator, outputs,
                Objects.requireNonNull(next, "initializer"), normalizer, disposal);
    }

    BeanStage<C, T> withNormalizer(Beans.Normalizer<C> next) {
        return new BeanStage<>(componentId, configType, dependencies, creator, outputs,
                initializer, Objects.requireNonNull(next, "normalizer"), disposal);
    }

    BeanStage<C, T> withDisposal(BeanDisposal<T> next) {
        return new BeanStage<>(componentId, configType, dependencies, creator, outputs,
                initializer, normalizer, Objects.requireNonNull(next, "disposal"));
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
}
