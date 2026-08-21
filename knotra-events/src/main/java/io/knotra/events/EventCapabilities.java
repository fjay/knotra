package io.knotra.events;

import io.knotra.CapabilityKey;

/**
 * 事件组件发布的 Capability key。事件模块没有内核专用通道，消费方像依赖其他类型化能力一样依赖这里声明的
 * {@link EventBus}。
 */
public final class EventCapabilities {
    /**
     * 类型化 EventBus 的 Capability key。注册归属提供该能力的 Activation。
     */
    public static final CapabilityKey<EventBus> EVENT_BUS =
            CapabilityKey.of("knotra.event-bus", EventBus.class);

    private EventCapabilities() {
    }
}
