package io.knotra.events;

import java.util.concurrent.CompletionStage;

/**
 * 瀑布事件的监听合约。监听按订阅顺序执行，前一个监听返回的事件值会作为下一个监听的输入。
 *
 * <p>监听异常会转换为 {@link EventFailure} 并停止后续变换；{@link EventDispatch#finalEvent()} 保留
 * 最后一次成功变换后的值。</p>
 */
@FunctionalInterface
public interface WaterfallEventListener<T> {
    /**
     * 变换当前事件值。
     *
     * @param event 当前事件值
     * @return 传给后续监听的下一个事件值所在的 stage
     */
    CompletionStage<T> transform(T event) throws Exception;
}
