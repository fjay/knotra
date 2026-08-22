package io.knotra.beans;

/** 从 Bean 派生次要 Capability 输出的映射函数；返回 null 会使本次 Activation 回滚。 */
@FunctionalInterface
public interface OutputMapper<T, P> {
    P map(T bean) throws Exception;
}
