package io.knotra.events;

import java.util.concurrent.CompletionStage;

/**
 * 串行事件的监听合约。监听按订阅顺序执行，前一个监听返回的 stage 完成后，后一个监听才会开始。
 *
 * <p>返回 {@code false} 是无错误的提前停止；监听异常会转换为 {@link EventFailure}，同样停止后续监听。</p>
 */
@FunctionalInterface
public interface SerialEventListener<T> {
    /**
     * 处理事件值并声明分发链是否继续。
     *
     * @param event 当前事件值
     * @return {@code true} 继续执行后续监听，{@code false} 无错误地提前停止
     */
    CompletionStage<Boolean> listen(T event) throws Exception;
}
