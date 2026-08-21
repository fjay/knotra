package io.knotra.events;

import java.util.Objects;

/**
 * 类型化事件定义。每个嵌套类型同时固定事件身份和分发模式，订阅与分发的非法组合在编译期被排除。
 *
 * <p>事件默认使用 JVM Class 的全限定名。显式名称只改变可读身份；同一名称在存活期间仍只能绑定一个
 * 精确的 JVM Class，不同 ClassLoader 加载出的同名 Class 不是同一事件身份。</p>
 *
 * @param <T> 事件值类型
 */
public sealed interface EventDefinition<T> permits EventDefinition.Sync, EventDefinition.Parallel,
        EventDefinition.Serial, EventDefinition.Bail, EventDefinition.Waterfall {

    /** 返回事件名。事件名用于诊断、排序和规范 Class 绑定。 */
    String name();

    /** 返回定义持有的精确 JVM 事件类型。 */
    Class<T> eventType();

    /** 返回该定义类型固定的分发模式。 */
    EventMode mode();

    /** 创建使用 Class 全限定名的事件定义。 */
    static <T> Sync<T> sync(Class<T> eventType) {
        return sync(eventType.getName(), eventType);
    }

    /** 创建使用显式名称的事件定义。 */
    static <T> Sync<T> sync(String name, Class<T> eventType) {
        return new Sync<>(name, eventType);
    }

    /** 创建使用 Class 全限定名的事件定义。 */
    static <T> Parallel<T> parallel(Class<T> eventType) {
        return parallel(eventType.getName(), eventType);
    }

    /** 创建使用显式名称的事件定义。 */
    static <T> Parallel<T> parallel(String name, Class<T> eventType) {
        return new Parallel<>(name, eventType);
    }

    /** 创建使用 Class 全限定名的事件定义。 */
    static <T> Serial<T> serial(Class<T> eventType) {
        return serial(eventType.getName(), eventType);
    }

    /** 创建使用显式名称的事件定义。 */
    static <T> Serial<T> serial(String name, Class<T> eventType) {
        return new Serial<>(name, eventType);
    }

    /** 创建使用 Class 全限定名的事件定义。 */
    static <T> Bail<T> bail(Class<T> eventType) {
        return bail(eventType.getName(), eventType);
    }

    /** 创建使用显式名称的事件定义。 */
    static <T> Bail<T> bail(String name, Class<T> eventType) {
        return new Bail<>(name, eventType);
    }

    /** 创建使用 Class 全限定名的事件定义。 */
    static <T> Waterfall<T> waterfall(Class<T> eventType) {
        return waterfall(eventType.getName(), eventType);
    }

    /** 创建使用显式名称的事件定义。 */
    static <T> Waterfall<T> waterfall(String name, Class<T> eventType) {
        return new Waterfall<>(name, eventType);
    }

    private static void validate(String name, Class<?> eventType) {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("event name must not be blank");
        }
        Objects.requireNonNull(eventType, "eventType");
        if (eventType.isPrimitive()) {
            throw new IllegalArgumentException("primitive event types are not supported");
        }
    }

    /** 调用线程内按订阅顺序执行的同步事件。 */
    record Sync<T>(String name, Class<T> eventType) implements EventDefinition<T> {
        public Sync {
            validate(name, eventType);
        }

        @Override
        public EventMode mode() {
            return EventMode.SYNC;
        }
    }

    /** 同一次分发中的监听并发执行并统一收敛的事件。 */
    record Parallel<T>(String name, Class<T> eventType) implements EventDefinition<T> {
        public Parallel {
            validate(name, eventType);
        }

        @Override
        public EventMode mode() {
            return EventMode.PARALLEL;
        }
    }

    /** 监听按顺序执行、可用无错误结果提前停止的事件。 */
    record Serial<T>(String name, Class<T> eventType) implements EventDefinition<T> {
        public Serial {
            validate(name, eventType);
        }

        @Override
        public EventMode mode() {
            return EventMode.SERIAL;
        }
    }

    /** 第一个认领结果的监听停止后续监听的事件。 */
    record Bail<T>(String name, Class<T> eventType) implements EventDefinition<T> {
        public Bail {
            validate(name, eventType);
        }

        @Override
        public EventMode mode() {
            return EventMode.BAIL;
        }
    }

    /** 监听按顺序变换事件值的事件。 */
    record Waterfall<T>(String name, Class<T> eventType) implements EventDefinition<T> {
        public Waterfall {
            validate(name, eventType);
        }

        @Override
        public EventMode mode() {
            return EventMode.WATERFALL;
        }
    }
}
