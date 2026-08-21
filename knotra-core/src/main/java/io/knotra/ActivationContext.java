package io.knotra;

import java.util.Optional;

/** 组件 start 执行期间使用的一次性激活上下文。 */
public interface ActivationContext {
    <T> T require(CapabilityKey<T> key);

    <T> Optional<T> find(CapabilityKey<T> key);

    <T> void provide(CapabilityKey<T> key, T value);

    <C> ComponentHandle<C> mountChild(
            String mountId,
            ComponentFactory<C> factory,
            C config);

    <C> ComponentHandle<C> mountChild(
            String mountId,
            ComponentFactory<C> factory,
            C config,
            MountOptions options);

    /** 挂载无配置子组件。 */
    ComponentHandle<NoConfig> mountChild(
            String mountId,
            ComponentFactory<NoConfig> factory);

    /** 挂载无配置子组件并覆盖挂载选项。 */
    ComponentHandle<NoConfig> mountChild(
            String mountId,
            ComponentFactory<NoConfig> factory,
            MountOptions options);

    LifecycleScope lifecycle();

    ContextInfo info();
}
