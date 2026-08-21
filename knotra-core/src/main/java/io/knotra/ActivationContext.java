package io.knotra;

import java.util.Optional;

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

    LifecycleScope lifecycle();

    ContextInfo contextInfo();
}
