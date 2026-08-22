package io.knotra.beans;

import io.knotra.CapabilityKey;
import io.knotra.CapabilityRequirement;
import io.knotra.ConfiguredMountHandle;
import io.knotra.DynamicCapability;
import io.knotra.KnotraRuntime;
import io.knotra.MountHandle;
import io.knotra.NoConfig;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Type-safe DSL for adapting ordinary Java objects to Activation-owned beans.
 *
 * <p>All builder stages are immutable. Definitions can be built once and mounted repeatedly.</p>
 */
public final class Beans {

    private Beans() {
    }

    public enum LifecycleMode { AUTO, UNMANAGED, CUSTOM_SYNC, CUSTOM_ASYNC }

    @FunctionalInterface
    public interface Creator0<T> {
        T create() throws Exception;
    }

    @FunctionalInterface
    public interface Creator1<D1, T> {
        T create(D1 d1) throws Exception;
    }

    @FunctionalInterface
    public interface Creator2<D1, D2, T> {
        T create(D1 d1, D2 d2) throws Exception;
    }

    @FunctionalInterface
    public interface Creator3<D1, D2, D3, T> {
        T create(D1 d1, D2 d2, D3 d3) throws Exception;
    }

    @FunctionalInterface
    public interface Creator4<D1, D2, D3, D4, T> {
        T create(D1 d1, D2 d2, D3 d3, D4 d4) throws Exception;
    }

    @FunctionalInterface
    public interface Creator5<D1, D2, D3, D4, D5, T> {
        T create(D1 d1, D2 d2, D3 d3, D4 d4, D5 d5) throws Exception;
    }

    @FunctionalInterface
    public interface ConfigCreator0<C, T> {
        T create(C config) throws Exception;
    }

    @FunctionalInterface
    public interface ConfigCreator1<C, D1, T> {
        T create(C config, D1 d1) throws Exception;
    }

    @FunctionalInterface
    public interface ConfigCreator2<C, D1, D2, T> {
        T create(C config, D1 d1, D2 d2) throws Exception;
    }

    @FunctionalInterface
    public interface ConfigCreator3<C, D1, D2, D3, T> {
        T create(C config, D1 d1, D2 d2, D3 d3) throws Exception;
    }

    @FunctionalInterface
    public interface ConfigCreator4<C, D1, D2, D3, D4, T> {
        T create(C config, D1 d1, D2 d2, D3 d3, D4 d4) throws Exception;
    }

    @FunctionalInterface
    public interface ConfigCreator5<C, D1, D2, D3, D4, D5, T> {
        T create(C config, D1 d1, D2 d2, D3 d3, D4 d4, D5 d5) throws Exception;
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

    public static final class Builder0 {
        private final String componentId;

        Builder0(String componentId) {
            this.componentId = componentId;
        }

        public <D1> Builder1<D1> with(BeanDependency<D1> first) {
            return new Builder1<>(componentId, first);
        }

        public <D1, D2> Builder2<D1, D2> with(
                BeanDependency<D1> first,
                BeanDependency<D2> second) {
            return new Builder2<>(componentId, first, second);
        }

        public <D1, D2, D3> Builder3<D1, D2, D3> with(
                BeanDependency<D1> first,
                BeanDependency<D2> second,
                BeanDependency<D3> third) {
            return new Builder3<>(componentId, first, second, third);
        }

        public <D1, D2, D3, D4> Builder4<D1, D2, D3, D4> with(
                BeanDependency<D1> first,
                BeanDependency<D2> second,
                BeanDependency<D3> third,
                BeanDependency<D4> fourth) {
            return new Builder4<>(componentId, first, second, third, fourth);
        }

        public <D1, D2, D3, D4, D5> Builder5<D1, D2, D3, D4, D5> with(
                BeanDependency<D1> first,
                BeanDependency<D2> second,
                BeanDependency<D3> third,
                BeanDependency<D4> fourth,
                BeanDependency<D5> fifth) {
            return new Builder5<>(componentId, first, second, third, fourth, fifth);
        }

        public <T> OutputStage<T> create(Creator0<T> creator) {
            Objects.requireNonNull(creator, "creator");
            return new OutputStage<>(new BeanStage<NoConfig, T>(
                    componentId,
                    NoConfig.class,
                    List.of(),
                    (context, config) -> creator.create()));
        }
    }

    public static final class Builder1<D1> {
        private final String componentId;
        private final BeanDependency<D1> first;

        Builder1(String componentId, BeanDependency<D1> first) {
            this.componentId = componentId;
            this.first = Objects.requireNonNull(first, "first");
        }

        public <D2> Builder2<D1, D2> with(BeanDependency<D2> second) {
            return new Builder2<>(componentId, first, second);
        }

        public <D2, D3> Builder3<D1, D2, D3> with(
                BeanDependency<D2> second,
                BeanDependency<D3> third) {
            return new Builder3<>(componentId, first, second, third);
        }

        public <D2, D3, D4> Builder4<D1, D2, D3, D4> with(
                BeanDependency<D2> second,
                BeanDependency<D3> third,
                BeanDependency<D4> fourth) {
            return new Builder4<>(componentId, first, second, third, fourth);
        }

        public <D2, D3, D4, D5> Builder5<D1, D2, D3, D4, D5> with(
                BeanDependency<D2> second,
                BeanDependency<D3> third,
                BeanDependency<D4> fourth,
                BeanDependency<D5> fifth) {
            return new Builder5<>(componentId, first, second, third, fourth, fifth);
        }

        public <T> OutputStage<T> create(Creator1<D1, T> creator) {
            Objects.requireNonNull(creator, "creator");
            return new OutputStage<>(new BeanStage<NoConfig, T>(
                    componentId,
                    NoConfig.class,
                    List.of(first),
                    (context, config) -> creator.create(first.resolve(context))));
        }
    }

    public static final class Builder2<D1, D2> {
        private final String componentId;
        private final BeanDependency<D1> first;
        private final BeanDependency<D2> second;

        Builder2(
                String componentId,
                BeanDependency<D1> first,
                BeanDependency<D2> second) {
            this.componentId = componentId;
            this.first = Objects.requireNonNull(first, "first");
            this.second = Objects.requireNonNull(second, "second");
        }

        public <D3> Builder3<D1, D2, D3> with(BeanDependency<D3> third) {
            return new Builder3<>(componentId, first, second, third);
        }

        public <D3, D4> Builder4<D1, D2, D3, D4> with(
                BeanDependency<D3> third,
                BeanDependency<D4> fourth) {
            return new Builder4<>(componentId, first, second, third, fourth);
        }

        public <D3, D4, D5> Builder5<D1, D2, D3, D4, D5> with(
                BeanDependency<D3> third,
                BeanDependency<D4> fourth,
                BeanDependency<D5> fifth) {
            return new Builder5<>(componentId, first, second, third, fourth, fifth);
        }

        public <T> OutputStage<T> create(Creator2<D1, D2, T> creator) {
            Objects.requireNonNull(creator, "creator");
            return new OutputStage<>(new BeanStage<NoConfig, T>(
                    componentId,
                    NoConfig.class,
                    List.of(first, second),
                    (context, config) -> creator.create(
                            first.resolve(context),
                            second.resolve(context))));
        }
    }

    public static final class Builder3<D1, D2, D3> {
        private final String componentId;
        private final BeanDependency<D1> first;
        private final BeanDependency<D2> second;
        private final BeanDependency<D3> third;

        Builder3(
                String componentId,
                BeanDependency<D1> first,
                BeanDependency<D2> second,
                BeanDependency<D3> third) {
            this.componentId = componentId;
            this.first = Objects.requireNonNull(first, "first");
            this.second = Objects.requireNonNull(second, "second");
            this.third = Objects.requireNonNull(third, "third");
        }

        public <D4> Builder4<D1, D2, D3, D4> with(BeanDependency<D4> fourth) {
            return new Builder4<>(componentId, first, second, third, fourth);
        }

        public <D4, D5> Builder5<D1, D2, D3, D4, D5> with(
                BeanDependency<D4> fourth,
                BeanDependency<D5> fifth) {
            return new Builder5<>(componentId, first, second, third, fourth, fifth);
        }

        public <T> OutputStage<T> create(Creator3<D1, D2, D3, T> creator) {
            Objects.requireNonNull(creator, "creator");
            return new OutputStage<>(new BeanStage<NoConfig, T>(
                    componentId,
                    NoConfig.class,
                    List.of(first, second, third),
                    (context, config) -> creator.create(
                            first.resolve(context),
                            second.resolve(context),
                            third.resolve(context))));
        }
    }

    public static final class Builder4<D1, D2, D3, D4> {
        private final String componentId;
        private final BeanDependency<D1> first;
        private final BeanDependency<D2> second;
        private final BeanDependency<D3> third;
        private final BeanDependency<D4> fourth;

        Builder4(
                String componentId,
                BeanDependency<D1> first,
                BeanDependency<D2> second,
                BeanDependency<D3> third,
                BeanDependency<D4> fourth) {
            this.componentId = componentId;
            this.first = Objects.requireNonNull(first, "first");
            this.second = Objects.requireNonNull(second, "second");
            this.third = Objects.requireNonNull(third, "third");
            this.fourth = Objects.requireNonNull(fourth, "fourth");
        }

        public <D5> Builder5<D1, D2, D3, D4, D5> with(BeanDependency<D5> fifth) {
            return new Builder5<>(componentId, first, second, third, fourth, fifth);
        }

        public <T> OutputStage<T> create(Creator4<D1, D2, D3, D4, T> creator) {
            Objects.requireNonNull(creator, "creator");
            return new OutputStage<>(new BeanStage<NoConfig, T>(
                    componentId,
                    NoConfig.class,
                    List.of(first, second, third, fourth),
                    (context, config) -> creator.create(
                            first.resolve(context),
                            second.resolve(context),
                            third.resolve(context),
                            fourth.resolve(context))));
        }
    }

    public static final class Builder5<D1, D2, D3, D4, D5> {
        private final String componentId;
        private final BeanDependency<D1> first;
        private final BeanDependency<D2> second;
        private final BeanDependency<D3> third;
        private final BeanDependency<D4> fourth;
        private final BeanDependency<D5> fifth;

        Builder5(
                String componentId,
                BeanDependency<D1> first,
                BeanDependency<D2> second,
                BeanDependency<D3> third,
                BeanDependency<D4> fourth,
                BeanDependency<D5> fifth) {
            this.componentId = componentId;
            this.first = Objects.requireNonNull(first, "first");
            this.second = Objects.requireNonNull(second, "second");
            this.third = Objects.requireNonNull(third, "third");
            this.fourth = Objects.requireNonNull(fourth, "fourth");
            this.fifth = Objects.requireNonNull(fifth, "fifth");
        }

        public <T> OutputStage<T> create(Creator5<D1, D2, D3, D4, D5, T> creator) {
            Objects.requireNonNull(creator, "creator");
            return new OutputStage<>(new BeanStage<NoConfig, T>(
                    componentId,
                    NoConfig.class,
                    List.of(first, second, third, fourth, fifth),
                    (context, config) -> creator.create(
                            first.resolve(context),
                            second.resolve(context),
                            third.resolve(context),
                            fourth.resolve(context),
                            fifth.resolve(context))));
        }
    }

    public static final class ConfigBuilder0<C> {
        private final String componentId;
        private final Class<C> configType;

        ConfigBuilder0(String componentId, Class<C> configType) {
            this.componentId = componentId;
            this.configType = Objects.requireNonNull(configType, "configType");
        }

        public <D1> ConfigBuilder1<C, D1> with(BeanDependency<D1> first) {
            return new ConfigBuilder1<>(componentId, configType, first);
        }

        public <D1, D2> ConfigBuilder2<C, D1, D2> with(
                BeanDependency<D1> first,
                BeanDependency<D2> second) {
            return new ConfigBuilder2<>(componentId, configType, first, second);
        }

        public <D1, D2, D3> ConfigBuilder3<C, D1, D2, D3> with(
                BeanDependency<D1> first,
                BeanDependency<D2> second,
                BeanDependency<D3> third) {
            return new ConfigBuilder3<>(componentId, configType, first, second, third);
        }

        public <D1, D2, D3, D4> ConfigBuilder4<C, D1, D2, D3, D4> with(
                BeanDependency<D1> first,
                BeanDependency<D2> second,
                BeanDependency<D3> third,
                BeanDependency<D4> fourth) {
            return new ConfigBuilder4<>(componentId, configType, first, second, third, fourth);
        }

        public <D1, D2, D3, D4, D5> ConfigBuilder5<C, D1, D2, D3, D4, D5> with(
                BeanDependency<D1> first,
                BeanDependency<D2> second,
                BeanDependency<D3> third,
                BeanDependency<D4> fourth,
                BeanDependency<D5> fifth) {
            return new ConfigBuilder5<>(
                    componentId, configType, first, second, third, fourth, fifth);
        }

        public <T> ConfigOutputStage<C, T> create(ConfigCreator0<C, T> creator) {
            Objects.requireNonNull(creator, "creator");
            return new ConfigOutputStage<>(new BeanStage<C, T>(
                    componentId,
                    configType,
                    List.of(),
                    (context, config) -> creator.create(config)));
        }
    }

    public static final class ConfigBuilder1<C, D1> {
        private final String componentId;
        private final Class<C> configType;
        private final BeanDependency<D1> first;

        ConfigBuilder1(
                String componentId,
                Class<C> configType,
                BeanDependency<D1> first) {
            this.componentId = componentId;
            this.configType = configType;
            this.first = Objects.requireNonNull(first, "first");
        }

        public <D2> ConfigBuilder2<C, D1, D2> with(BeanDependency<D2> second) {
            return new ConfigBuilder2<>(componentId, configType, first, second);
        }

        public <D2, D3> ConfigBuilder3<C, D1, D2, D3> with(
                BeanDependency<D2> second,
                BeanDependency<D3> third) {
            return new ConfigBuilder3<>(componentId, configType, first, second, third);
        }

        public <D2, D3, D4> ConfigBuilder4<C, D1, D2, D3, D4> with(
                BeanDependency<D2> second,
                BeanDependency<D3> third,
                BeanDependency<D4> fourth) {
            return new ConfigBuilder4<>(componentId, configType, first, second, third, fourth);
        }

        public <D2, D3, D4, D5> ConfigBuilder5<C, D1, D2, D3, D4, D5> with(
                BeanDependency<D2> second,
                BeanDependency<D3> third,
                BeanDependency<D4> fourth,
                BeanDependency<D5> fifth) {
            return new ConfigBuilder5<>(
                    componentId, configType, first, second, third, fourth, fifth);
        }

        public <T> ConfigOutputStage<C, T> create(ConfigCreator1<C, D1, T> creator) {
            Objects.requireNonNull(creator, "creator");
            return new ConfigOutputStage<>(new BeanStage<C, T>(
                    componentId,
                    configType,
                    List.of(first),
                    (context, config) -> creator.create(config, first.resolve(context))));
        }
    }

    public static final class ConfigBuilder2<C, D1, D2> {
        private final String componentId;
        private final Class<C> configType;
        private final BeanDependency<D1> first;
        private final BeanDependency<D2> second;

        ConfigBuilder2(
                String componentId,
                Class<C> configType,
                BeanDependency<D1> first,
                BeanDependency<D2> second) {
            this.componentId = componentId;
            this.configType = configType;
            this.first = Objects.requireNonNull(first, "first");
            this.second = Objects.requireNonNull(second, "second");
        }

        public <D3> ConfigBuilder3<C, D1, D2, D3> with(BeanDependency<D3> third) {
            return new ConfigBuilder3<>(componentId, configType, first, second, third);
        }

        public <D3, D4> ConfigBuilder4<C, D1, D2, D3, D4> with(
                BeanDependency<D3> third,
                BeanDependency<D4> fourth) {
            return new ConfigBuilder4<>(componentId, configType, first, second, third, fourth);
        }

        public <D3, D4, D5> ConfigBuilder5<C, D1, D2, D3, D4, D5> with(
                BeanDependency<D3> third,
                BeanDependency<D4> fourth,
                BeanDependency<D5> fifth) {
            return new ConfigBuilder5<>(
                    componentId, configType, first, second, third, fourth, fifth);
        }

        public <T> ConfigOutputStage<C, T> create(ConfigCreator2<C, D1, D2, T> creator) {
            Objects.requireNonNull(creator, "creator");
            return new ConfigOutputStage<>(new BeanStage<C, T>(
                    componentId,
                    configType,
                    List.of(first, second),
                    (context, config) -> creator.create(
                            config,
                            first.resolve(context),
                            second.resolve(context))));
        }
    }

    public static final class ConfigBuilder3<C, D1, D2, D3> {
        private final String componentId;
        private final Class<C> configType;
        private final BeanDependency<D1> first;
        private final BeanDependency<D2> second;
        private final BeanDependency<D3> third;

        ConfigBuilder3(
                String componentId,
                Class<C> configType,
                BeanDependency<D1> first,
                BeanDependency<D2> second,
                BeanDependency<D3> third) {
            this.componentId = componentId;
            this.configType = configType;
            this.first = Objects.requireNonNull(first, "first");
            this.second = Objects.requireNonNull(second, "second");
            this.third = Objects.requireNonNull(third, "third");
        }

        public <D4> ConfigBuilder4<C, D1, D2, D3, D4> with(BeanDependency<D4> fourth) {
            return new ConfigBuilder4<>(componentId, configType, first, second, third, fourth);
        }

        public <D4, D5> ConfigBuilder5<C, D1, D2, D3, D4, D5> with(
                BeanDependency<D4> fourth,
                BeanDependency<D5> fifth) {
            return new ConfigBuilder5<>(
                    componentId, configType, first, second, third, fourth, fifth);
        }

        public <T> ConfigOutputStage<C, T> create(ConfigCreator3<C, D1, D2, D3, T> creator) {
            Objects.requireNonNull(creator, "creator");
            return new ConfigOutputStage<>(new BeanStage<C, T>(
                    componentId,
                    configType,
                    List.of(first, second, third),
                    (context, config) -> creator.create(
                            config,
                            first.resolve(context),
                            second.resolve(context),
                            third.resolve(context))));
        }
    }

    public static final class ConfigBuilder4<C, D1, D2, D3, D4> {
        private final String componentId;
        private final Class<C> configType;
        private final BeanDependency<D1> first;
        private final BeanDependency<D2> second;
        private final BeanDependency<D3> third;
        private final BeanDependency<D4> fourth;

        ConfigBuilder4(
                String componentId,
                Class<C> configType,
                BeanDependency<D1> first,
                BeanDependency<D2> second,
                BeanDependency<D3> third,
                BeanDependency<D4> fourth) {
            this.componentId = componentId;
            this.configType = configType;
            this.first = Objects.requireNonNull(first, "first");
            this.second = Objects.requireNonNull(second, "second");
            this.third = Objects.requireNonNull(third, "third");
            this.fourth = Objects.requireNonNull(fourth, "fourth");
        }

        public <D5> ConfigBuilder5<C, D1, D2, D3, D4, D5> with(BeanDependency<D5> fifth) {
            return new ConfigBuilder5<>(
                    componentId, configType, first, second, third, fourth, fifth);
        }

        public <T> ConfigOutputStage<C, T> create(ConfigCreator4<C, D1, D2, D3, D4, T> creator) {
            Objects.requireNonNull(creator, "creator");
            return new ConfigOutputStage<>(new BeanStage<C, T>(
                    componentId,
                    configType,
                    List.of(first, second, third, fourth),
                    (context, config) -> creator.create(
                            config,
                            first.resolve(context),
                            second.resolve(context),
                            third.resolve(context),
                            fourth.resolve(context))));
        }
    }

    public static final class ConfigBuilder5<C, D1, D2, D3, D4, D5> {
        private final String componentId;
        private final Class<C> configType;
        private final BeanDependency<D1> first;
        private final BeanDependency<D2> second;
        private final BeanDependency<D3> third;
        private final BeanDependency<D4> fourth;
        private final BeanDependency<D5> fifth;

        ConfigBuilder5(
                String componentId,
                Class<C> configType,
                BeanDependency<D1> first,
                BeanDependency<D2> second,
                BeanDependency<D3> third,
                BeanDependency<D4> fourth,
                BeanDependency<D5> fifth) {
            this.componentId = componentId;
            this.configType = configType;
            this.first = Objects.requireNonNull(first, "first");
            this.second = Objects.requireNonNull(second, "second");
            this.third = Objects.requireNonNull(third, "third");
            this.fourth = Objects.requireNonNull(fourth, "fourth");
            this.fifth = Objects.requireNonNull(fifth, "fifth");
        }

        public <T> ConfigOutputStage<C, T> create(
                ConfigCreator5<C, D1, D2, D3, D4, D5, T> creator) {
            Objects.requireNonNull(creator, "creator");
            return new ConfigOutputStage<>(new BeanStage<C, T>(
                    componentId,
                    configType,
                    List.of(first, second, third, fourth, fifth),
                    (context, config) -> creator.create(
                            config,
                            first.resolve(context),
                            second.resolve(context),
                            third.resolve(context),
                            fourth.resolve(context),
                            fifth.resolve(context))));
        }
    }

    public static final class OutputStage<T> {
        private final BeanStage<NoConfig, T> stage;

        OutputStage(BeanStage<NoConfig, T> stage) {
            this.stage = stage;
        }

        public OutputStage<T> provide(CapabilityKey<T> key) {
            return new OutputStage<>(stage.withOutput(new BeanOutput<>(key, null)));
        }

        public OutputStage<T> provide(Class<T> type) {
            return provide(CapabilityKey.of(type));
        }

        public <P> OutputStage<T> provideAs(
                CapabilityKey<P> key,
                OutputMapper<? super T, ? extends P> mapper) {
            return new OutputStage<>(stage.withOutput(new BeanOutput<>(key, mapper)));
        }

        public <P> OutputStage<T> provideAs(
                Class<P> type,
                OutputMapper<? super T, ? extends P> mapper) {
            return provideAs(CapabilityKey.of(type), mapper);
        }

        public OutputStage<T> initializer(Initializer<? super T> initializer) {
            return new OutputStage<>(stage.withInitializer(initializer));
        }

        public OutputStage<T> unmanaged() {
            return new OutputStage<>(stage.withDisposal(
                    new BeanDisposal<>(LifecycleMode.UNMANAGED, null, null)));
        }

        public OutputStage<T> destroyWith(Disposer<? super T> disposer) {
            return new OutputStage<>(stage.withDisposal(
                    new BeanDisposal<>(LifecycleMode.CUSTOM_SYNC, disposer, null)));
        }

        public OutputStage<T> destroyAsyncWith(AsyncDisposer<? super T> disposer) {
            return new OutputStage<>(stage.withDisposal(
                    new BeanDisposal<>(LifecycleMode.CUSTOM_ASYNC, null, disposer)));
        }

        public BeanDefinition<T> build() {
            return new BeanDefinition<>(BeanStage.mountFactory(stage));
        }
    }

    public static final class ConfigOutputStage<C, T> {
        private final BeanStage<C, T> stage;

        ConfigOutputStage(BeanStage<C, T> stage) {
            this.stage = stage;
        }

        public ConfigOutputStage<C, T> provide(CapabilityKey<T> key) {
            return new ConfigOutputStage<>(stage.withOutput(new BeanOutput<>(key, null)));
        }

        public ConfigOutputStage<C, T> provide(Class<T> type) {
            return provide(CapabilityKey.of(type));
        }

        public <P> ConfigOutputStage<C, T> provideAs(
                CapabilityKey<P> key,
                OutputMapper<? super T, ? extends P> mapper) {
            return new ConfigOutputStage<>(stage.withOutput(new BeanOutput<>(key, mapper)));
        }

        public <P> ConfigOutputStage<C, T> provideAs(
                Class<P> type,
                OutputMapper<? super T, ? extends P> mapper) {
            return provideAs(CapabilityKey.of(type), mapper);
        }

        public ConfigOutputStage<C, T> initializer(Initializer<? super T> initializer) {
            return new ConfigOutputStage<>(stage.withInitializer(initializer));
        }

        public ConfigOutputStage<C, T> normalizeConfig(Normalizer<C> normalizer) {
            return new ConfigOutputStage<>(stage.withNormalizer(normalizer));
        }

        public ConfigOutputStage<C, T> unmanaged() {
            return new ConfigOutputStage<>(stage.withDisposal(
                    new BeanDisposal<>(LifecycleMode.UNMANAGED, null, null)));
        }

        public ConfigOutputStage<C, T> destroyWith(Disposer<? super T> disposer) {
            return new ConfigOutputStage<>(stage.withDisposal(
                    new BeanDisposal<>(LifecycleMode.CUSTOM_SYNC, disposer, null)));
        }

        public ConfigOutputStage<C, T> destroyAsyncWith(AsyncDisposer<? super T> disposer) {
            return new ConfigOutputStage<>(stage.withDisposal(
                    new BeanDisposal<>(LifecycleMode.CUSTOM_ASYNC, null, disposer)));
        }

        public ConfiguredBeanDefinition<C, T> build() {
            return new ConfiguredBeanDefinition<>(stage.build());
        }
    }

    public static <T> BeanDependency<T> required(CapabilityKey<T> key) {
        Objects.requireNonNull(key, "key");
        return BeanDependency.of(CapabilityRequirement.required(key), context -> context.require(key));
    }

    public static <T> BeanDependency<T> required(Class<T> type) {
        return required(CapabilityKey.of(type));
    }

    public static <T> BeanDependency<Optional<T>> optional(CapabilityKey<T> key) {
        Objects.requireNonNull(key, "key");
        return BeanDependency.of(CapabilityRequirement.optional(key), context -> context.find(key));
    }

    public static <T> BeanDependency<Optional<T>> optional(Class<T> type) {
        return optional(CapabilityKey.of(type));
    }

    /**
     * Required dynamic interface dependency.
     *
     * <p>The requirement is checked only for the bean's first activation. Once active, a missing
     * provider keeps the bean ACTIVE and method calls fail; replacing the provider does not recreate
     * the bean. Each proxy method independently holds a provider lease.</p>
     */
    public static <T> BeanDependency<T> dynamic(CapabilityKey<T> key) {
        requireProxyInterface(key);
        return BeanDependency.of(
                CapabilityRequirement.dynamicRequired(key),
                context -> context.subscribe(key).proxy(key.type()));
    }

    public static <T> BeanDependency<T> dynamic(Class<T> type) {
        return dynamic(CapabilityKey.of(type));
    }

    /** Optional dynamic interface proxy; use {@link #dynamic} for required startup semantics. */
    public static <T> BeanDependency<T> dynamicOptional(CapabilityKey<T> key) {
        requireProxyInterface(key);
        return BeanDependency.of(
                CapabilityRequirement.dynamicOptional(key),
                context -> context.subscribe(key).proxy(key.type()));
    }

    public static <T> BeanDependency<T> dynamicOptional(Class<T> type) {
        return dynamicOptional(CapabilityKey.of(type));
    }

    /**
     * Required explicit dynamic capability dependency.
     *
     * <p>REQUIRED affects only first activation. After the bean is ACTIVE, a missing provider does
     * not deactivate it; subsequent calls fail. Use {@code call} or {@code callAsync} when several
     * operations must be fixed to the same provider lease.</p>
     */
    public static <T> BeanDependency<DynamicCapability<T>> dynamicCapability(
            CapabilityKey<T> key) {
        Objects.requireNonNull(key, "key");
        return BeanDependency.of(
                CapabilityRequirement.dynamicRequired(key),
                context -> context.subscribe(key));
    }

    public static <T> BeanDependency<DynamicCapability<T>> dynamicCapability(Class<T> type) {
        return dynamicCapability(CapabilityKey.of(type));
    }

    /**
     * Optional explicit dynamic capability dependency.
     *
     * <p>An optional dynamic dependency never blocks activation. Provider disappearance likewise
     * leaves an ACTIVE bean ACTIVE; calls fail until a provider is available. Explicit calls can
     * hold one provider lease across multiple methods.</p>
     */
    public static <T> BeanDependency<DynamicCapability<T>> dynamicCapabilityOptional(
            CapabilityKey<T> key) {
        Objects.requireNonNull(key, "key");
        return BeanDependency.of(
                CapabilityRequirement.dynamicOptional(key),
                context -> context.subscribe(key));
    }

    public static <T> BeanDependency<DynamicCapability<T>> dynamicCapabilityOptional(
            Class<T> type) {
        return dynamicCapabilityOptional(CapabilityKey.of(type));
    }

    public static Builder0 component(String componentId) {
        return new Builder0(requireComponentId(componentId));
    }

    public static <C> ConfigBuilder0<C> component(String componentId, Class<C> configType) {
        return new ConfigBuilder0<>(requireComponentId(componentId), configType);
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
