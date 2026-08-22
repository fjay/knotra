package io.knotra.beans;

import java.util.List;
import java.util.Objects;

/** 类型化配置 Bean 定义的 1 依赖阶段；不可变，每次调用返回新实例。 */
public final class ConfiguredBeanBuilder1<C, D1> {

    private final String componentId;
    private final Class<C> configType;
    private final BeanDependency<D1> first;

    ConfiguredBeanBuilder1(String componentId, Class<C> configType, BeanDependency<D1> first) {
        this.componentId = componentId;
        this.configType = configType;
        this.first = first;
    }

    public <D2> ConfiguredBeanBuilder2<C, D1, D2> with(BeanDependency<D2> second) {
        Objects.requireNonNull(second, "second");
        return new ConfiguredBeanBuilder2<>(componentId, configType, first, second);
    }

    public <D2, D3> ConfiguredBeanBuilder3<C, D1, D2, D3> with(
            BeanDependency<D2> second,
            BeanDependency<D3> third) {
        Objects.requireNonNull(second, "second");
        Objects.requireNonNull(third, "third");
        return new ConfiguredBeanBuilder3<>(componentId, configType, first, second, third);
    }

    public <D2, D3, D4> ConfiguredBeanBuilder4<C, D1, D2, D3, D4> with(
            BeanDependency<D2> second,
            BeanDependency<D3> third,
            BeanDependency<D4> fourth) {
        Objects.requireNonNull(second, "second");
        Objects.requireNonNull(third, "third");
        Objects.requireNonNull(fourth, "fourth");
        return new ConfiguredBeanBuilder4<>(componentId, configType, first, second, third, fourth);
    }

    public <D2, D3, D4, D5> ConfiguredBeanBuilder5<C, D1, D2, D3, D4, D5> with(
            BeanDependency<D2> second,
            BeanDependency<D3> third,
            BeanDependency<D4> fourth,
            BeanDependency<D5> fifth) {
        Objects.requireNonNull(second, "second");
        Objects.requireNonNull(third, "third");
        Objects.requireNonNull(fourth, "fourth");
        Objects.requireNonNull(fifth, "fifth");
        return new ConfiguredBeanBuilder5<>(
                componentId, configType, first, second, third, fourth, fifth);
    }

    public <T> BeanOutputStage<C, T> create(ConfigBeanCreator1<C, D1, T> creator) {
        Objects.requireNonNull(creator, "creator");
        return BeanOutputStage.of(
                componentId,
                configType,
                List.of(first),
                (context, config) -> creator.create(
                        config,
                        first.resolver().resolve(context)));
    }
}
