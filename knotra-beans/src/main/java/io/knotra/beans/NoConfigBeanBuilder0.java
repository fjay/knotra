package io.knotra.beans;

import io.knotra.NoConfig;

import java.util.List;
import java.util.Objects;

/** 无配置 Bean 定义的 0 依赖阶段；不可变，每次调用返回新实例。 */
public final class NoConfigBeanBuilder0 {

    private final String componentId;

    NoConfigBeanBuilder0(String componentId) {
        this.componentId = componentId;
    }

    public <D1> NoConfigBeanBuilder1<D1> with(BeanDependency<D1> first) {
        Objects.requireNonNull(first, "first");
        return new NoConfigBeanBuilder1<>(componentId, first);
    }

    public <D1, D2> NoConfigBeanBuilder2<D1, D2> with(
            BeanDependency<D1> first,
            BeanDependency<D2> second) {
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(second, "second");
        return new NoConfigBeanBuilder2<>(componentId, first, second);
    }

    public <D1, D2, D3> NoConfigBeanBuilder3<D1, D2, D3> with(
            BeanDependency<D1> first,
            BeanDependency<D2> second,
            BeanDependency<D3> third) {
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(second, "second");
        Objects.requireNonNull(third, "third");
        return new NoConfigBeanBuilder3<>(componentId, first, second, third);
    }

    public <D1, D2, D3, D4> NoConfigBeanBuilder4<D1, D2, D3, D4> with(
            BeanDependency<D1> first,
            BeanDependency<D2> second,
            BeanDependency<D3> third,
            BeanDependency<D4> fourth) {
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(second, "second");
        Objects.requireNonNull(third, "third");
        Objects.requireNonNull(fourth, "fourth");
        return new NoConfigBeanBuilder4<>(componentId, first, second, third, fourth);
    }

    public <D1, D2, D3, D4, D5> NoConfigBeanBuilder5<D1, D2, D3, D4, D5> with(
            BeanDependency<D1> first,
            BeanDependency<D2> second,
            BeanDependency<D3> third,
            BeanDependency<D4> fourth,
            BeanDependency<D5> fifth) {
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(second, "second");
        Objects.requireNonNull(third, "third");
        Objects.requireNonNull(fourth, "fourth");
        Objects.requireNonNull(fifth, "fifth");
        return new NoConfigBeanBuilder5<>(componentId, first, second, third, fourth, fifth);
    }

    public <T> BeanOutputStage<NoConfig, T> create(BeanCreator0<T> creator) {
        Objects.requireNonNull(creator, "creator");
        return BeanOutputStage.of(componentId, NoConfig.class, List.of(), (context, config) -> creator.create());
    }
}
