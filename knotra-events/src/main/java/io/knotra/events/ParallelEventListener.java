package io.knotra.events;

import java.util.concurrent.CompletionStage;

/**
 * 并行事件的监听合约。同一次分发中的所有已接受监听并发执行，EventBus 等待它们全部收敛后，
 * 再统一生成错误聚合与完成计数。
 */
@FunctionalInterface
public interface ParallelEventListener<T> {
    /**
     * 异步处理事件值，返回的 stage 决定该监听何时完成；返回 {@code null} 视为监听失败。
     *
     * @param event 待处理的事件值
     * @return 表示监听完成的 stage
     */
    CompletionStage<Void> listen(T event) throws Exception;
}
