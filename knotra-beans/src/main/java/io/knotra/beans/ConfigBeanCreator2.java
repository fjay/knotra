package io.knotra.beans;

/** 类型化配置、2 个依赖的 Bean creator。 */
@FunctionalInterface
public interface ConfigBeanCreator2<C, D1, D2, T> {
    T create(C config, D1 d1, D2 d2) throws Exception;
}
