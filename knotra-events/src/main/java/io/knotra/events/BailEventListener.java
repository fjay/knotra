package io.knotra.events;

/**
 * 应急（bail）事件的监听合约。监听按订阅顺序同步执行，第一个返回 claimed 结果的监听会终结分发。
 *
 * <p>返回 {@code false} 表示未认领，后续监听继续执行；监听抛出的异常会被转换为
 * {@link EventFailure} 并停止本次分发。</p>
 */
@FunctionalInterface
public interface BailEventListener<T> {
    /**
     * 处理一个应急事件。
     *
     * @param event 待处理的事件值
     * @return {@code true} 表示认领结果并停止后续监听，{@code false} 表示继续分发
     */
    boolean bail(T event) throws Exception;
}
