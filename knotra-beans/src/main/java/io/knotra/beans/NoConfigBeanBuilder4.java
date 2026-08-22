package io.knotra.beans;

import io.knotra.NoConfig;

import java.util.List;
import java.util.Objects;

/** 无配置 Bean 定义的 4 依赖阶段；不可变，每次调用返回新实例。 */
public final class NoConfigBeanBuilder4<D1, D2, D3, D4> {

    private final String componentId;
    private final BeanDependency<D1> first;
    private final BeanDependency<D2> second;
    private final BeanDependency<D3> third;
    private final BeanDependency<D4> fourth;

    NoConfigBeanBuilder4(
            String componentId,
            BeanDependency<D1> first,
            BeanDependency<D2> second,
            BeanDependency<D3> third,
            BeanDependency<D4> fourth) {
        this.componentId = componentId;
        this.first = first;
        this.second = second;
        this.third = third;
        this.fourth = fourth;
    }

    public <D5> NoConfigBeanBuilder5<D1, D2, D3, D4, D5> with(BeanDependency<D5> fifth) {
        Objects.requireNonNull(fifth, "fifth");
        return new NoConfigBeanBuilder5<>(componentId, first, second, third, fourth, fifth);
    }

    public <T> BeanOutputStage<NoConfig, T> create(BeanCreator4<D1, D2, D3, D4, T> creator) {
        Objects.requireNonNull(creator, "creator");
        return BeanOutputStage.of(
                componentId,
                NoConfig.class,
                List.of(first, second, third, fourth),
                (context, config) -> creator.create(
                        first.resolver().resolve(context),
                        second.resolver().resolve(context),
                        third.resolver().resolve(context),
                        fourth.resolver().resolve(context)));
    }
}
