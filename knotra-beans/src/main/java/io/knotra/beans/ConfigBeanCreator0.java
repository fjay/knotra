package io.knotra.beans;

/** 类型化配置、无依赖的 Bean creator。 */
@FunctionalInterface
public interface ConfigBeanCreator0<C, T> {
    T create(C config) throws Exception;
}
