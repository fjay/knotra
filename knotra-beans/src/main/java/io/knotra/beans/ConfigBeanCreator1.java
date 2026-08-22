package io.knotra.beans;

/** 类型化配置、1 个依赖的 Bean creator。 */
@FunctionalInterface
public interface ConfigBeanCreator1<C, D1, T> {
    T create(C config, D1 d1) throws Exception;
}
