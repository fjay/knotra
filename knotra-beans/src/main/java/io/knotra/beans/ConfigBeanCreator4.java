package io.knotra.beans;

/** 类型化配置、4 个依赖的 Bean creator。 */
@FunctionalInterface
public interface ConfigBeanCreator4<C, D1, D2, D3, D4, T> {
    T create(C config, D1 d1, D2 d2, D3 d3, D4 d4) throws Exception;
}
