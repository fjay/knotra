package io.knotra;

/**
 * 无配置组件的 unit 类型。
 *
 * <p>宿主和组件的 no-config mount overload 会在内部提供 {@link #INSTANCE}；
 * typed artifact handle 的两参数 mount 也通过 decoder 提供该值。配置对象本身不使用 null。</p>
 */
public enum NoConfig {
    INSTANCE
}
