package io.knotra.events;

import java.util.concurrent.CompletionStage;

/**
 * 已注册事件监听的受控句柄。句柄暴露稳定订阅元数据，以及取消未来分发和等待已接受分发收敛的关闭操作。
 *
 * <p>回调可以取消自身订阅；但回调不能阻塞等待自己的关闭完成，因为关闭必须先等待该回调返回。</p>
 */
public interface EventSubscription extends AutoCloseable {
    /**
     * 返回订阅的稳定标识。标识在单个 EventBus 内唯一，可用于日志、诊断和 Snapshot 对应。
     *
     * @return 订阅标识
     */
    String subscriptionId();

    /**
     * 返回订阅的事件名。事件名来自 {@link EventKey#name()}。
     *
     * @return 事件名
     */
    String eventName();

    /**
     * 返回订阅对应的分发模式。该模式在注册时由事件定义固定。
     *
     * @return 分发模式
     */
    EventMode mode();

    /**
     * 返回同一 EventBus 内的注册序号。序号单调且唯一，保证 Snapshot 的稳定排序。
     *
     * @return 注册序号
     */
    long sequence();

    /**
     * 返回订阅是否仍会被未来分发选中。
     *
     * @return 仍处于活跃状态时为 {@code true}
     */
    boolean active();

    /**
     * 从未来分发中移除本订阅。该方法不等待已经接受的工作，也不会中断执行中的监听。
     */
    void unsubscribe();

    /**
     * 取消未来分发，并等待关闭请求被观察到之前本订阅已接受的全部分发。
     * 即使监听因串行停止或瀑布失败而被跳过，其已获得的接受租约也会被释放。
     * 重复调用返回同一个 completion stage。
     *
     * @return 本订阅已接受分发全部完成后完成的 stage
     */
    CompletionStage<Void> closeAsync();

    /**
     * 阻塞等待 {@link #closeAsync()}。监听回调中不得调用本方法，否则会等待自身完成而形成死锁。
     */
    @Override
    void close();
}
