package io.knotra.events;

/**
 * 事件分发策略。模式由 {@link EventDefinition} 固定，订阅与分发必须使用完全相同的模式。
 */
public enum EventMode {
    /** 调用线程内按订阅顺序执行，每个监听同步完成。 */
    SYNC,
    /** 同一次分发的监听并发执行，结果在全部监听收敛后聚合。 */
    PARALLEL,
    /** 监听按订阅顺序执行，可用无错误结果提前停止分发链。 */
    SERIAL,
    /** 监听按订阅顺序执行，第一个认领结果的监听停止分发。 */
    BAIL,
    /** 监听按订阅顺序变换事件值，输出值传给下一个监听。 */
    WATERFALL
}
