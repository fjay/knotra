package io.knotra;

/**
 * 无配置组件的配置哨兵值。
 *
 * <p>工厂未声明配置 schema 时，挂载与重配置都使用 {@link #INSTANCE}；
 * 配置参数不允许为 null。
 */
public enum NoConfig {
    /** 唯一实例。 */
    INSTANCE
}
