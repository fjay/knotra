package io.knotra.beans;

/** 类型化配置、5 个依赖的 Bean creator。 */
@FunctionalInterface
public interface ConfigBeanCreator5<C, D1, D2, D3, D4, D5, T> {
    T create(C config, D1 d1, D2 d2, D3 d3, D4 d4, D5 d5) throws Exception;
}
