package io.knotra.beans;

import java.util.List;
import java.util.Objects;

/** 类型化配置 Bean 定义的 4 依赖阶段；不可变，每次调用返回新实例。 */
public final class ConfiguredBeanBuilder4<C, D1, D2, D3, D4> {

    private final String componentId;
    private final Class<C> configType;
    private final BeanDependency<D1> first;
    private final BeanDependency<D2> second;
    private final BeanDependency<D3> third;
    private final BeanDependency<D4> fourth;

    ConfiguredBeanBuilder4(
            String componentId,
            Class<C> configType,
            BeanDependency<D1> first,
            BeanDependency<D2> second,
            BeanDependency<D3> third,
            BeanDependency<D4> fourth) {
        this.componentId = componentId;
        this.configType = configType;
        this.first = first;
        this.second = second;
        this.third = third;
        this.fourth = fourth;
    }

    public <D5> ConfiguredBeanBuilder5<C, D1, D2, D3, D4, D5> with(BeanDependency<D5> fifth) {
        Objects.requireNonNull(fifth, "fifth");
        return new ConfiguredBeanBuilder5<>(
                componentId, configType, first, second, third, fourth, fifth);
    }

    public <T> BeanOutputStage<C, T> create(ConfigBeanCreator4<C, D1, D2, D3, D4, T> creator) {
        Objects.requireNonNull(creator, "creator");
        return BeanOutputStage.of(
                componentId,
                configType,
                List.of(first, second, third, fourth),
                (context, config) -> creator.create(
                        config,
                        first.resolver().resolve(context),
                        second.resolver().resolve(context),
                        third.resolver().resolve(context),
                        fourth.resolver().resolve(context)));
    }
}
