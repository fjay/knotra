package io.knotra.beans;

/** Bean 创建成功、cleanup 已登记后执行的初始化动作；抛出异常会触发本次 Activation 回滚。 */
@FunctionalInterface
public interface BeanInitializer<T> {
    void initialize(T bean) throws Exception;
}
