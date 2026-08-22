package io.knotra;

import java.util.Optional;
/** One-time activation context handed to a component's start callback. */
public interface ActivationContext {
    <T> T require(CapabilityKey<T> key);

    default <T> T require(Class<T> type) {
        return require(CapabilityKey.of(type));
    }

    <T> Optional<T> find(CapabilityKey<T> key);

    default <T> Optional<T> find(Class<T> type) {
        return find(CapabilityKey.of(type));
    }

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
