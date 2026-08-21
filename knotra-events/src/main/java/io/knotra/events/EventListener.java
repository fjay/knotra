package io.knotra.events;

/**
 * 同步事件的监听合约。{@link EventBus#emit(EventDefinition, Object)} 会在调用线程中按订阅顺序执行监听，
 * 单个监听失败不会阻止后续监听，失败会被聚合进 {@link EventDispatch#failures()}。
 */
@FunctionalInterface
public interface EventListener<T> {
    /**
     * 同步处理事件值。回调内取消自身订阅是允许的，且不会使本次已接受的分发失败。
     *
     * @param event 待处理的事件值
     */
    void listen(T event) throws Exception;
}
