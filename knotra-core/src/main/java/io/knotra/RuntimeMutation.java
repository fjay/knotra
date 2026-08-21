package io.knotra;

public interface RuntimeMutation {
    <T> RegistrationHandle provide(
            ContextHandle context,
            CapabilityKey<T> key,
            T value);

    void revoke(RegistrationHandle registration);

    ContextHandle childContext(
            ContextHandle parent,
            String name);

    <C> ComponentHandle<C> mount(
            ContextHandle context,
            String mountId,
            ComponentFactory<C> factory,
            C config);

    <C> ComponentHandle<C> mount(
            ContextHandle context,
            String mountId,
            ComponentFactory<C> factory,
            C config,
            MountOptions options);

    <C> ComponentHandle<C> reconfigure(
            ComponentHandle<C> handle,
            C config);

    void dispose(ComponentHandle<?> handle);

    void dispose(ContextHandle context);
}
