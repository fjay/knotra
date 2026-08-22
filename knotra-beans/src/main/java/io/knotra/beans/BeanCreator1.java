package io.knotra.beans;

/** 无配置、1 个依赖的 Bean creator。 */
@FunctionalInterface
public interface BeanCreator1<D1, T> {
    T create(D1 d1) throws Exception;
}
