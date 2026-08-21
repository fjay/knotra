package io.knotra.events;

import io.knotra.ComponentFactory;
import io.knotra.NoConfig;

/**
 * EventBus 组件的宿主工厂。事件模块作为普通组件挂载，不依赖内核专用通道。
 */
public final class EventBusFactory implements ComponentFactory<NoConfig> {
    /**
     * 稳定工厂标识，用于宿主事务和 Loader 声明中的类型化挂载。
     */
    public static final String FACTORY_ID = "knotra-event-bus";

    @Override
    public String factoryId() {
        return FACTORY_ID;
    }

    /**
     * 创建无状态组件实例。总线状态属于每次 Activation，不在工厂层共享。
     *
     * @return 事件组件实例
     */
    @Override
    public EventBusComponent create() {
        return EventBusComponent.INSTANCE;
    }
}
