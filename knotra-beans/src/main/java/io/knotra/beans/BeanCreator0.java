package io.knotra.beans;

/** 无配置、无依赖的 Bean creator。 */
@FunctionalInterface
public interface BeanCreator0<T> {
    T create() throws Exception;
}
