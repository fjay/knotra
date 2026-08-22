package io.knotra.beans;

/** 自定义同步清理动作；失败条目保持 FAILED，可通过组件 retry 语义重试。 */
@FunctionalInterface
public interface BeanDisposer<T> {
    void dispose(T bean) throws Exception;
}
