package io.knotra.events;

import java.util.List;
import java.util.Objects;

/**
 * 一次分发的稳定结果。结果只包含计数、停止状态和错误诊断，不引用监听回调或 Throwable，
 * 因此可在分发完成后安全持有。
 *
 * @param initialEvent 分发初始接受的事件值
 * @param finalEvent 分发结束时的事件值；瀑布模式下为最后一次成功变换的值
 * @param mode 分发模式
 * @param listenerCount 本次分发接受到的监听数量
 * @param completedCount 成功完成的监听数量，失败或被跳过的监听不计入
 * @param stoppedEarly 分发链是否提前停止；无错误的串行停止、应急认领和监听失败都可能为 {@code true}
 * @param failures 按监听执行结果收集的失败诊断
 */
public final record EventDispatch<T>(
        T initialEvent,
        T finalEvent,
        EventMode mode,
        int listenerCount,
        int completedCount,
        boolean stoppedEarly,
        List<EventFailure> failures) {

    /**
     * 固化分发模式并归一化失败列表；{@code null} 失败列表表示没有失败。
     */
    public EventDispatch {
        Objects.requireNonNull(mode, "mode");
        failures = List.copyOf(failures == null ? List.of() : failures);
    }

    /**
     * 创建同步分发结果。同步监听失败不会停止后续监听，因此完成数为监听数减失败数。
     *
     * @param event 初始与最终事件值
     * @param listenerCount 接受到的监听数量
     * @param failures 按监听顺序收集的失败
     * @return 同步分发结果
     */
    public static <T> EventDispatch<T> sync(T event, int listenerCount, List<EventFailure> failures) {
        return new EventDispatch<>(event, event, EventMode.SYNC, listenerCount,
                listenerCount - (failures == null ? 0 : failures.size()), false,
                failures == null ? List.of() : failures);
    }

    /**
     * 返回本次分发是否没有监听失败。提前停止本身不是失败，因此不能由本方法排除。
     *
     * @return 没有监听失败时为 {@code true}
     */
    public boolean successful() {
        return failures.isEmpty();
    }

    /**
     * 返回监听失败数量。
     *
     * @return 失败数量
     */
    public int failureCount() {
        return failures.size();
    }
}
