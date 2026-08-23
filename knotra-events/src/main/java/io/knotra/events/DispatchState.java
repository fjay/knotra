package io.knotra.events;

import java.util.ArrayList;
import java.util.List;

/**
 * 串行、应急与瀑布共享的不可变分发状态。状态本身不携带 completion；调用方把固定监听集合
 * 折叠成一条 stage 链，并在每个监听结束后基于当前状态派生下一个状态。
 */
final class DispatchState<T> {
    private final T initialEvent;
    private final T event;
    private final EventMode mode;
    private final int listenerCount;
    private final int completed;
    private final boolean stopped;
    private final List<EventFailure> failures;

    private DispatchState(
            T initialEvent,
            T event,
            EventMode mode,
            int listenerCount,
            int completed,
            boolean stopped,
            List<EventFailure> failures) {
        this.initialEvent = initialEvent;
        this.event = event;
        this.mode = mode;
        this.listenerCount = listenerCount;
        this.completed = completed;
        this.stopped = stopped;
        this.failures = List.copyOf(failures);
    }

    static <T> DispatchState<T> initial(T event, EventMode mode, int listenerCount) {
        return new DispatchState<>(event, event, mode, listenerCount, 0, false, List.of());
    }

    private DispatchState<T> with(
            T event,
            int completed,
            boolean stopped,
            List<EventFailure> failures) {
        return new DispatchState<>(
                initialEvent, event, mode, listenerCount, completed, stopped, failures);
    }

    T event() {
        return event;
    }

    boolean stopped() {
        return stopped;
    }

    /** 记录串行/应急监听结果：失败不计完成并停止，无错误停止只标记 stopped。 */
    DispatchState<T> afterListener(boolean continueDispatch, EventFailure failure) {
        boolean failed = failure != null;
        return with(
                event,
                failed ? completed : completed + 1,
                failed || !continueDispatch,
                failed ? append(failure) : failures);
    }

    /** 瀑布监听成功才推进事件值；失败保留上一个成功值并停止后续变换。 */
    DispatchState<T> afterTransform(T nextEvent, EventFailure failure) {
        boolean failed = failure != null;
        return with(
                failed ? event : nextEvent,
                failed ? completed : completed + 1,
                failed,
                failed ? append(failure) : failures);
    }

    private List<EventFailure> append(EventFailure failure) {
        List<EventFailure> next = new ArrayList<>(failures);
        next.add(failure);
        return next;
    }

    EventDispatch<T> toDispatch() {
        return new EventDispatch<>(
                initialEvent,
                event,
                mode,
                listenerCount,
                completed,
                stopped,
                failures);
    }
}
