package io.knotra.events;

import io.knotra.AsyncCloseable;

import java.util.concurrent.CompletionStage;

/**
 * 类型化事件总线。事件定义同时固定事件类型和分发模式，同名事件可在不同模式下拥有独立订阅。
 *
 * <p>关闭会等待“关闭请求被观察到之前”已接受的分发收敛；关闭之后的新订阅和分发会被拒绝。
 * 订阅应由消费方的 LifecycleScope 管理，使组件 teardown 遵循同一收敛规则。</p>
 */
public interface EventBus extends AsyncCloseable {

    /** 返回当前总线的稳定标识。标识用于诊断和 Snapshot 对应关系，不代表全局单例身份。 */
    String busId();

    /** 注册同步监听。监听只在同步 dispatch 的调用线程中执行，并按注册顺序排列。 */
    <T> EventSubscription subscribe(
            EventDefinition.Sync<T> definition,
            EventListener<? super T> listener);

    /** 注册并行监听。每次分发给定的监听集合会在总线执行器上并发执行。 */
    <T> EventSubscription subscribe(
            EventDefinition.Parallel<T> definition,
            ParallelEventListener<? super T> listener);

    /** 注册串行监听。前一个监听返回的 stage 完成后，后一个监听才会开始。 */
    <T> EventSubscription subscribe(
            EventDefinition.Serial<T> definition,
            SerialEventListener<? super T> listener);

    /** 注册应急监听。第一个返回 claimed 结果的监听会停止后续监听。 */
    <T> EventSubscription subscribe(
            EventDefinition.Bail<T> definition,
            BailEventListener<? super T> listener);

    /** 注册瀑布监听。监听顺序处理并变换事件值，返回值会传给下一个监听。 */
    <T> EventSubscription subscribe(
            EventDefinition.Waterfall<T> definition,
            WaterfallEventListener<T> listener);

    /**
     * 在当前线程同步分发事件。监听失败不会中断后续监听，所有失败会出现在返回结果中。
     *
     * @throws ClassCastException 事件值不符合定义的事件类型
     * @throws IllegalStateException 总线已关闭
     */
    <T> EventDispatch<T> dispatch(EventDefinition.Sync<T> definition, T event);

    /**
     * 并行分发事件。返回的 stage 只报告接受分发或组装分发时的失败；监听失败聚合在
     * {@link EventDispatch} 中，不会让返回的 stage 异常完成。
     */
    <T> CompletionStage<EventDispatch<T>> dispatch(
            EventDefinition.Parallel<T> definition,
            T event);

    /** 按串行语义分发事件。监听返回 {@code false} 或失败都会体现在分发结果中。 */
    <T> CompletionStage<EventDispatch<T>> dispatch(
            EventDefinition.Serial<T> definition,
            T event);

    /** 按应急语义分发事件。第一个认领结果的监听会停止后续监听。 */
    <T> CompletionStage<EventDispatch<T>> dispatch(
            EventDefinition.Bail<T> definition,
            T event);

    /** 按瀑布语义分发事件。最终结果保留初始事件与最终事件。 */
    <T> CompletionStage<EventDispatch<T>> dispatch(
            EventDefinition.Waterfall<T> definition,
            T event);

    /** 返回当前订阅的稳定 Snapshot。Snapshot 不引用监听对象或 Throwable。 */
    EventBusSnapshot snapshot();

    /** 返回在本次调用被观察到之前已接受分发的收敛 stage。 */
    CompletionStage<Void> whenIdle();
}
