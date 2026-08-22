package io.knotra.beans;

/** 无配置、3 个依赖的 Bean creator。 */
@FunctionalInterface
public interface BeanCreator3<D1, D2, D3, T> {
    T create(D1 d1, D2 d2, D3 d3) throws Exception;
}
