package io.knotra.events;

/** 单个监听的模式化结果：串行的继续标记、瀑布的下一个事件值或失败诊断。 */
record ListenerOutcome<T>(
        boolean continueDispatch,
        T event,
        EventFailure failure) {

    static <T> ListenerOutcome<T> success() {
        return new ListenerOutcome<>(true, null, null);
    }

    static <T> ListenerOutcome<T> failure(EventFailure failure) {
        return new ListenerOutcome<>(false, null, failure);
    }
}
