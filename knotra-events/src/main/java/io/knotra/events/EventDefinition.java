package io.knotra.events;

import java.util.Objects;

/**
 * 类型化事件定义。定义将 {@link EventKey} 与不可变的 {@link EventMode} 绑定，
 * 订阅和分发必须使用同一模式。
 *
 * <p>事件名默认是 JVM Class 的全限定名；同一事件名在不同 ClassLoader 中加载出的不同 Class 会视为不同事件身份。</p>
 *
 * @param <T> 事件值类型
 */
public final record EventDefinition<T>(EventKey<T> key, EventMode mode) {

    /**
     * 校验并固化事件身份与分发模式。
     *
     * @throws NullPointerException 事件身份或分发模式为 {@code null}
     */
    public EventDefinition {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(mode, "mode");
    }

    /**
     * 创建同步事件定义。
     *
     * @param key 事件身份
     * @return 使用 {@link EventMode#SYNC} 的定义
     */
    public static <T> EventDefinition<T> sync(EventKey<T> key) {
        return new EventDefinition<>(key, EventMode.SYNC);
    }

    /**
     * 创建并行事件定义。
     *
     * @param key 事件身份
     * @return 使用 {@link EventMode#PARALLEL} 的定义
     */
    public static <T> EventDefinition<T> parallel(EventKey<T> key) {
        return new EventDefinition<>(key, EventMode.PARALLEL);
    }

    /**
     * 创建串行事件定义。
     *
     * @param key 事件身份
     * @return 使用 {@link EventMode#SERIAL} 的定义
     */
    public static <T> EventDefinition<T> serial(EventKey<T> key) {
        return new EventDefinition<>(key, EventMode.SERIAL);
    }

    /**
     * 创建应急事件定义。
     *
     * @param key 事件身份
     * @return 使用 {@link EventMode#BAIL} 的定义
     */
    public static <T> EventDefinition<T> bail(EventKey<T> key) {
        return new EventDefinition<>(key, EventMode.BAIL);
    }

    /**
     * 创建瀑布事件定义。
     *
     * @param key 事件身份
     * @return 使用 {@link EventMode#WATERFALL} 的定义
     */
    public static <T> EventDefinition<T> waterfall(EventKey<T> key) {
        return new EventDefinition<>(key, EventMode.WATERFALL);
    }

    /**
     * 返回事件名，与 {@link EventKey#name()} 一致。
     *
     * @return 事件名
     */
    public String name() {
        return key.name();
    }

    /**
     * 返回定义绑定的 JVM 事件类型。
     *
     * @return 事件类型
     */
    public Class<T> eventType() {
        return key.eventType();
    }
}
