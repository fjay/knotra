package io.knotra.events;

import io.knotra.AsyncCloseable;

/**
 * 已注册事件监听的受控句柄。句柄暴露稳定订阅元数据，以及取消未来分发和等待已接受分发收敛的关闭操作。
 *
 * <p>回调可以取消自身订阅；但回调不能阻塞等待自己的关闭完成，因为关闭必须先等待该回调返回。</p>
 *
 * <p>{@code closeAsync()} 每次调用返回独立观察：取消返回的 stage 只放弃该次观察，
 * 不会中断订阅 drain，也不影响其他调用者或 {@code close()} 的真实收敛。</p>
 */
public interface EventSubscription extends AsyncCloseable {

    /** 返回订阅的稳定标识。标识在单个 EventBus 内唯一，可用于日志、诊断和 Snapshot 对应。 */
    String subscriptionId();

    /** 返回订阅的事件名。事件名来自 {@link EventDefinition#name()}。 */
    String eventName();

    /** 返回订阅对应的分发模式。该模式在注册时由事件定义固定。 */
    EventMode mode();

    /** 返回同一 EventBus 内的注册序号。序号单调且唯一，保证 Snapshot 的稳定排序。 */
    long sequence();

    /** 返回订阅是否仍会被未来分发选中。 */
    boolean active();

    /** 从未来分发中移除本订阅。该方法不等待已经接受的工作，也不会中断执行中的监听。 */
    void unsubscribe();
}
