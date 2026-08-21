package io.knotra.events;

import java.util.List;

/**
 * EventBus 的稳定 Snapshot。Snapshot 只包含总线状态和订阅元数据，不引用监听对象、事件值或 Throwable，
 * 可安全用于诊断和跨层观测。
 *
 * @param busId 总线标识
 * @param closed 总线是否已观察到关闭请求
 * @param subscriptions 按事件名、模式与注册序号排序的订阅数据
 */
public final record EventBusSnapshot(String busId, boolean closed, List<Item> subscriptions) {

    /**
     * 固化不可变订阅列表；{@code null} 被归一化为空列表，避免诊断数据出现可选状态。
     */
    public EventBusSnapshot {
        subscriptions = List.copyOf(subscriptions == null ? List.of() : subscriptions);
    }

    /**
     * 返回 Snapshot 中的订阅数量。
     *
     * @return 订阅数量
     */
    public int subscriptionCount() {
        return subscriptions.size();
    }

    /**
     * 单个订阅的稳定诊断数据。字段只描述注册身份与分发模式，不暴露监听实例。
     *
     * @param subscriptionId 订阅标识
     * @param eventName 事件名
     * @param eventTypeName 事件类型的 JVM 全限定名
     * @param mode 分发模式
     * @param sequence 同一总线内的注册序号
     */
    public record Item(
            String subscriptionId,
            String eventName,
            String eventTypeName,
            EventMode mode,
            long sequence) {
    }
}
