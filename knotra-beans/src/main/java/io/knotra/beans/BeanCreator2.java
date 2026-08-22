package io.knotra.beans;

/** 无配置、2 个依赖的 Bean creator。 */
@FunctionalInterface
public interface BeanCreator2<D1, D2, T> {
    T create(D1 d1, D2 d2) throws Exception;
}
