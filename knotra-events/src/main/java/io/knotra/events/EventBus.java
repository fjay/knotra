package io.knotra.events;

import java.util.concurrent.CompletionStage;

/**
 * 类型化事件总线。事件定义同时固定事件类型和分发模式，同名事件可在不同模式下拥有独立订阅。
 *
 * <p>所有关闭方法都会等待“关闭请求被观察到之前”已接受的分发收敛；关闭之后的新订阅和分发会被拒绝。
 * 订阅应由消费方的 LifecycleScope 管理，使组件 teardown 也遵循同一收敛规则。</p>
 */
public interface EventBus extends AutoCloseable {
    /**
     * 返回当前总线的稳定标识。标识用于诊断和 Snapshot 对应关系，不代表全局单例身份。
     *
     * @return 总线标识
     */
    String busId();

    /**
     * 注册同步监听。监听只在 {@link #emit(EventDefinition, Object)} 的调用线程中执行，并按注册顺序排列。
     *
     * @param definition 必须为 {@link EventMode#SYNC} 的事件定义
     * @param listener 待注册的监听
     * @return 可单独取消或关闭的订阅
     * @throws IllegalArgumentException 事件定义的分发模式不是 {@code SYNC}
     * @throws IllegalStateException 总线已关闭
     */
    <T> EventSubscription on(EventDefinition<T> definition, EventListener<? super T> listener);

    /**
     * 注册并行监听。每次分发给定的监听集合会在总线执行器上并发执行。
     *
     * @param definition 必须为 {@link EventMode#PARALLEL} 的事件定义
     * @param listener 待注册的监听
     * @return 可单独取消或关闭的订阅
     * @throws IllegalArgumentException 事件定义的分发模式不是 {@code PARALLEL}
     * @throws IllegalStateException 总线已关闭
     */
    <T> EventSubscription onParallel(
            EventDefinition<T> definition, ParallelEventListener<? super T> listener);

    /**
     * 注册串行监听。监听按注册顺序执行，前一个监听返回的 stage 完成后才进入下一个监听。
     *
     * @param definition 必须为 {@link EventMode#SERIAL} 的事件定义
     * @param listener 待注册的监听
     * @return 可单独取消或关闭的订阅
     * @throws IllegalArgumentException 事件定义的分发模式不是 {@code SERIAL}
     * @throws IllegalStateException 总线已关闭
     */
    <T> EventSubscription onSerial(
            EventDefinition<T> definition, SerialEventListener<? super T> listener);

    /**
     * 注册应急监听。第一个返回 claimed 结果的监听会停止后续监听。
     *
     * @param definition 必须为 {@link EventMode#BAIL} 的事件定义
     * @param listener 待注册的监听
     * @return 可单独取消或关闭的订阅
     * @throws IllegalArgumentException 事件定义的分发模式不是 {@code BAIL}
     * @throws IllegalStateException 总线已关闭
     */
    <T> EventSubscription onBail(
            EventDefinition<T> definition, BailEventListener<? super T> listener);

    /**
     * 注册瀑布监听。监听顺序处理并变换事件值，返回值会传给下一个监听。
     *
     * @param definition 必须为 {@link EventMode#WATERFALL} 的事件定义
     * @param listener 待注册的监听，事件类型必须与定义完全一致，因为它产出后续输入
     * @return 可单独取消或关闭的订阅
     * @throws IllegalArgumentException 事件定义的分发模式不是 {@code WATERFALL}
     * @throws IllegalStateException 总线已关闭
     */
    <T> EventSubscription onWaterfall(
            EventDefinition<T> definition, WaterfallEventListener<T> listener);

    /**
     * 在当前线程同步分发事件。监听失败不会中断后续监听，所有失败会出现在返回结果中。
     *
     * @param definition 必须为 {@link EventMode#SYNC} 的事件定义
     * @param event 待分发的事件值，必须可赋给定义的事件类型
     * @return 表示本次分发 Listener 数、完成数与失败诊断的稳定结果
     * @throws IllegalArgumentException 事件定义的分发模式不是 {@code SYNC}，或事件名已绑定到不同的 JVM Class
     * @throws ClassCastException 事件值不符合定义的事件类型
     * @throws IllegalStateException 总线已关闭
     */
    <T> EventDispatch<T> emit(EventDefinition<T> definition, T event);

    /**
     * 并行分发事件。返回的 stage 只报告接受分发或组装分发时的失败；监听失败会聚合在
     * {@link EventDispatch} 中，不会让返回的 stage 异常完成。
     *
     * @param definition 必须为 {@link EventMode#PARALLEL} 的事件定义
     * @param event 待分发的事件值，必须可赋给定义的事件类型
     * @return 所有已接受监听收敛后完成的 stage
     * @throws IllegalArgumentException 事件定义的分发模式不是 {@code PARALLEL}，或事件名已绑定到不同的 JVM Class
     * @throws ClassCastException 事件值不符合定义的事件类型
     * @throws IllegalStateException 总线已关闭
     */
    <T> CompletionStage<EventDispatch<T>> parallel(EventDefinition<T> definition, T event);

    /**
     * 串行分发事件。监听按注册顺序执行，返回 {@code false} 或失败都会无异常地体现在分发结果中。
     *
     * @param definition 必须为 {@link EventMode#SERIAL} 的事件定义
     * @param event 待分发的事件值，必须可赋给定义的事件类型
     * @return 分发链收敛后完成的 stage
     * @throws IllegalArgumentException 事件定义的分发模式不是 {@code SERIAL}，或事件名已绑定到不同的 JVM Class
     * @throws ClassCastException 事件值不符合定义的事件类型
     * @throws IllegalStateException 总线已关闭
     */
    <T> CompletionStage<EventDispatch<T>> serial(EventDefinition<T> definition, T event);

    /**
     * 按应急语义分发事件。第一个认领结果的监听会停止后续监听；未认领时分发执行完所有监听。
     *
     * @param definition 必须为 {@link EventMode#BAIL} 的事件定义
     * @param event 待分发的事件值，必须可赋给定义的事件类型
     * @return 应急分发链收敛后完成的 stage
     * @throws IllegalArgumentException 事件定义的分发模式不是 {@code BAIL}，或事件名已绑定到不同的 JVM Class
     * @throws ClassCastException 事件值不符合定义的事件类型
     * @throws IllegalStateException 总线已关闭
     */
    <T> CompletionStage<EventDispatch<T>> bail(EventDefinition<T> definition, T event);

    /**
     * 按瀑布语义分发事件。每个监听收到前一个监听产出的事件值，最终结果保留初始事件与最终事件。
     *
     * @param definition 必须为 {@link EventMode#WATERFALL} 的事件定义
     * @param event 待分发的事件值，必须可赋给定义的事件类型
     * @return 瀑布分发链收敛后完成的 stage
     * @throws IllegalArgumentException 事件定义的分发模式不是 {@code WATERFALL}，或事件名已绑定到不同的 JVM Class
     * @throws ClassCastException 事件值不符合定义的事件类型
     * @throws IllegalStateException 总线已关闭
     */
    <T> CompletionStage<EventDispatch<T>> waterfall(EventDefinition<T> definition, T event);

    /**
     * 返回当前订阅的稳定 Snapshot。Snapshot 不引用监听对象或 Throwable，排序稳定，适合诊断和观测。
     *
     * @return 当前总线与活跃订阅的只读数据
     */
    EventBusSnapshot snapshot();

    /**
     * 返回在本次调用被观察到之前已接受分发的收敛 stage；之后新接受的分发不属于该 stage 的等待范围。
     *
     * @return 已接受分发全部完成后完成的 stage
     */
    CompletionStage<Void> whenIdle();

    /**
     * 关闭未来工作，等待关闭请求被观察到之前已接受的分发，并在拥有执行器时停止它。
     * 重复调用返回同一个 completion stage。
     *
     * @return 已接受分发与自有执行器均收敛后完成的 stage
     */
    CompletionStage<Void> closeAsync();

    /**
     * 阻塞等待 {@link #closeAsync()}。监听回调中不得调用本方法，否则会等待自身完成而形成死锁。
     */
    @Override
    void close();
}
