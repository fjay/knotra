package io.knotra.beans;

import io.knotra.NoConfig;

import java.util.List;
import java.util.Objects;

/** 无配置 Bean 定义的 3 依赖阶段；不可变，每次调用返回新实例。 */
public final class NoConfigBeanBuilder3<D1, D2, D3> {

    private final String componentId;
    private final BeanDependency<D1> first;
    private final BeanDependency<D2> second;
    private final BeanDependency<D3> third;

    NoConfigBeanBuilder3(
            String componentId,
            BeanDependency<D1> first,
            BeanDependency<D2> second,
            BeanDependency<D3> third) {
        this.componentId = componentId;
        this.first = first;
        this.second = second;
        this.third = third;
    }

    public <D4> NoConfigBeanBuilder4<D1, D2, D3, D4> with(BeanDependency<D4> fourth) {
        Objects.requireNonNull(fourth, "fourth");
        return new NoConfigBeanBuilder4<>(componentId, first, second, third, fourth);
    }

    public <D4, D5> NoConfigBeanBuilder5<D1, D2, D3, D4, D5> with(
            BeanDependency<D4> fourth,
            BeanDependency<D5> fifth) {
        Objects.requireNonNull(fourth, "fourth");
        Objects.requireNonNull(fifth, "fifth");
        return new NoConfigBeanBuilder5<>(componentId, first, second, third, fourth, fifth);
    }

    public <T> BeanOutputStage<NoConfig, T> create(BeanCreator3<D1, D2, D3, T> creator) {
        Objects.requireNonNull(creator, "creator");
        return BeanOutputStage.of(
                componentId,
                NoConfig.class,
                List.of(first, second, third),
                (context, config) -> creator.create(
                        first.resolver().resolve(context),
                        second.resolver().resolve(context),
                        third.resolver().resolve(context)));
    }
}
