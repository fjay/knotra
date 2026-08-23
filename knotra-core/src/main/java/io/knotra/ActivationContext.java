package io.knotra;

/** 传递给组件启动回调的一次性激活上下文（ActivationContext）。 */
public interface ActivationContext extends CapabilityLookup {
    <T> DynamicCapability<T> subscribe(CapabilityKey<T> key);

    default <T> DynamicCapability<T> subscribe(Class<T> type) {
        return subscribe(CapabilityKey.of(type));
    }

    <T> void provide(CapabilityKey<T> key, T value);

    default <T> void provide(Class<T> type, T value) {
        provide(CapabilityKey.of(type), value);
    }

    <C> ConfiguredMountHandle<C> mountChild(
            String mountId,
            ComponentFactory<C> factory,
            C config);

    <C> ConfiguredMountHandle<C> mountChild(
            String mountId,
            ComponentFactory<C> factory,
            C config,
            MountOptions options);

    MountHandle mountChild(
            String mountId,
            ComponentFactory<NoConfig> factory);

    MountHandle mountChild(
            String mountId,
            ComponentFactory<NoConfig> factory,
            MountOptions options);

    LifecycleScope lifecycle();

    ContextInfo info();
}
