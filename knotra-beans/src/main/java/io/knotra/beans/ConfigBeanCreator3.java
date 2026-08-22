package io.knotra.beans;

/** 类型化配置、3 个依赖的 Bean creator。 */
@FunctionalInterface
public interface ConfigBeanCreator3<C, D1, D2, D3, T> {
    T create(C config, D1 d1, D2 d2, D3 d3) throws Exception;
}
