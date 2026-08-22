package io.knotra;

/** 宿主结构化事务意图记录器，仅在 transact 回调内部有效。 */
public interface RuntimeTransaction {
    <T> StagedRegistration<T> provide(
            ContextHandle context,
            CapabilityKey<T> key,
            T value);

    default <T> StagedRegistration<T> provide(ContextHandle context, Class<T> type, T value) {
        return provide(context, CapabilityKey.of(type), value);
    }

    void revoke(RegistrationHandle registration);

    ContextHandle childContext(ContextHandle parent, String name);

    <C> ConfiguredMountHandle<C> mount(
            ContextHandle context,
            String mountId,
            ComponentFactory<C> factory,
            C config);

    <C> ConfiguredMountHandle<C> mount(
            ContextHandle context,
            String mountId,
            ComponentFactory<C> factory,
            C config,
            MountOptions options);

    default MountHandle mount(
            ContextHandle context,
            String mountId,
            ComponentFactory<NoConfig> factory) {
        return mount(context, mountId, factory, NoConfig.INSTANCE, null);
    }

    default MountHandle mount(
            ContextHandle context,
            String mountId,
            ComponentFactory<NoConfig> factory,
            MountOptions options) {
        return mount(context, mountId, factory, NoConfig.INSTANCE, options);
    }

    <C> ConfiguredMountHandle<C> reconfigure(
            ConfiguredMountHandle<C> handle,
            C config);

    void dispose(MountHandle handle);

    void dispose(ContextHandle context);
}
