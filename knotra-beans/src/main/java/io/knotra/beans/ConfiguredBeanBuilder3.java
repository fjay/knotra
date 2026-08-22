package io.knotra.beans;

import java.util.List;
import java.util.Objects;

/** 类型化配置 Bean 定义的 3 依赖阶段；不可变，每次调用返回新实例。 */
public final class ConfiguredBeanBuilder3<C, D1, D2, D3> {

    private final String componentId;
    private final Class<C> configType;
    private final BeanDependency<D1> first;
    private final BeanDependency<D2> second;
    private final BeanDependency<D3> third;

    ConfiguredBeanBuilder3(
            String componentId,
            Class<C> configType,
            BeanDependency<D1> first,
            BeanDependency<D2> second,
            BeanDependency<D3> third) {
        this.componentId = componentId;
        this.configType = configType;
        this.first = first;
        this.second = second;
        this.third = third;
    }

    public <D4> ConfiguredBeanBuilder4<C, D1, D2, D3, D4> with(BeanDependency<D4> fourth) {
        Objects.requireNonNull(fourth, "fourth");
        return new ConfiguredBeanBuilder4<>(componentId, configType, first, second, third, fourth);
    }

    public <D4, D5> ConfiguredBeanBuilder5<C, D1, D2, D3, D4, D5> with(
            BeanDependency<D4> fourth,
            BeanDependency<D5> fifth) {
        Objects.requireNonNull(fourth, "fourth");
        Objects.requireNonNull(fifth, "fifth");
        return new ConfiguredBeanBuilder5<>(
                componentId, configType, first, second, third, fourth, fifth);
    }

    public <T> BeanOutputStage<C, T> create(ConfigBeanCreator3<C, D1, D2, D3, T> creator) {
        Objects.requireNonNull(creator, "creator");
        return BeanOutputStage.of(
                componentId,
                configType,
                List.of(first, second, third),
                (context, config) -> creator.create(
                        config,
                        first.resolver().resolve(context),
                        second.resolver().resolve(context),
                        third.resolver().resolve(context)));
    }
}
