package io.knotra.events;

import java.util.Objects;

/**
 * 事件的稳定身份。身份由精确的 JVM {@link Class} 决定，而不是仅由类型名决定；
 * 这条规则同样跨越 artifact ClassLoader 生效。
 *
 * @param <T> 事件值类型
 */
public final record EventKey<T>(Class<T> eventType) implements Comparable<EventKey<T>> {

    /**
     * 校验事件类型并固定身份。
     *
     * @throws NullPointerException 事件类型为 {@code null}
     * @throws IllegalArgumentException 事件类型是原始类型
     */
    public EventKey {
        Objects.requireNonNull(eventType, "eventType");
        if (eventType.isPrimitive()) {
            throw new IllegalArgumentException("primitive event types are not supported");
        }
    }

    /**
     * 用指定 JVM Class 创建事件身份。
     *
     * @param eventType 事件类型
     * @return 事件身份
     */
    public static <T> EventKey<T> of(Class<T> eventType) {
        return new EventKey<>(eventType);
    }

    /**
     * 返回 JVM Class 全限定名，作为事件的可读名称。
     *
     * @return 事件名
     */
    public String name() {
        return eventType.getName();
    }

    /**
     * 按事件名做稳定排序；相同名称下的实际身份仍由 {@link #eventType()} 的 JVM Class 决定。
     *
     * @param other 另一个事件身份
     * @return 名称的字典序比较结果
     */
    @Override
    public int compareTo(EventKey<T> other) {
        return name().compareTo(other.name());
    }
}
