package io.knotra.beans;

import io.knotra.NoConfig;

import java.util.List;
import java.util.Objects;

/** 无配置 Bean 定义的 5 依赖阶段（上限）；不可变。 */
public final class NoConfigBeanBuilder5<D1, D2, D3, D4, D5> {

    private final String componentId;
    private final BeanDependency<D1> first;
    private final BeanDependency<D2> second;
    private final BeanDependency<D3> third;
    private final BeanDependency<D4> fourth;
    private final BeanDependency<D5> fifth;

    NoConfigBeanBuilder5(
            String componentId,
            BeanDependency<D1> first,
            BeanDependency<D2> second,
            BeanDependency<D3> third,
            BeanDependency<D4> fourth,
            BeanDependency<D5> fifth) {
        this.componentId = componentId;
        this.first = first;
        this.second = second;
        this.third = third;
        this.fourth = fourth;
        this.fifth = fifth;
    }

    public <T> BeanOutputStage<NoConfig, T> create(BeanCreator5<D1, D2, D3, D4, D5, T> creator) {
        Objects.requireNonNull(creator, "creator");
        return BeanOutputStage.of(
                componentId,
                NoConfig.class,
                List.of(first, second, third, fourth, fifth),
                (context, config) -> creator.create(
                        first.resolver().resolve(context),
                        second.resolver().resolve(context),
                        third.resolver().resolve(context),
                        fourth.resolver().resolve(context),
                        fifth.resolver().resolve(context)));
    }
}
