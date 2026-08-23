package io.knotra.events;

import io.knotra.ActivationContext;
import io.knotra.Component;
import io.knotra.ComponentDescriptor;
import io.knotra.NoConfig;

/**
 * 将类型化 EventBus 作为普通 Capability 发布的事件组件。每次 Activation 都会创建独立总线，
 * 能力注册由该 Activation 拥有；组件 teardown 时 LifecycleScope 会先等待已接受分发收敛。
 */
public final class EventBusComponent implements Component<NoConfig> {
    /**
     * 无状态组件实例。重复 Activation 复用该实例不会共享总线或订阅状态。
     */
    public static final EventBusComponent INSTANCE = new EventBusComponent();

    private static final ComponentDescriptor DESCRIPTOR =
            ComponentDescriptor.named("knotra-event-bus");

    private EventBusComponent() {
    }

    @Override
    public ComponentDescriptor descriptor() {
        return DESCRIPTOR;
    }

    /**
     * 为本次 Activation 创建 EventBus，先注册生命周期清理，再向当前 ActivationContext 发布能力。
     * 因此提供方卸载或重新激活时，旧总线会等待已接受分发并拒绝后续工作，消费方会绑定新的能力代际。
     *
     * @param context 当前 Activation 的上下文
     * @param config 组件配置，本组件不使用
     */
    @Override
    public void start(ActivationContext context, NoConfig config) {
        // 默认构造路径由组件宿主决定：Activation 独立使用自带 cached pool；宿主若注入执行器，
        // 线程策略和生命周期治理也应由宿主统一决定。
        EventBus bus = new DefaultEventBus();
        context.lifecycle().manageAsync("event-bus", bus);
        context.provide(EventCapabilities.EVENT_BUS, bus);
    }
}
