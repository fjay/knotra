package io.knotra;

/**
 * 宿主结构事务的意图记录接口，仅在 {@link KnotraRuntime#transact} 回调内有效。
 *
 * <p>全部意图通过结构校验后才原子发布；任一意图失败都会拒绝整个事务。</p>
 */
public interface RuntimeTransaction {
    <T> RegistrationHandle provide(
            ContextHandle context,
            CapabilityKey<T> key,
            T value);

    void revoke(RegistrationHandle registration);

    ContextHandle childContext(ContextHandle parent, String name);

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

    /** 挂载无配置组件，调用方无需传递 NoConfig.INSTANCE。 */
    ComponentHandle<NoConfig> mount(
            ContextHandle context,
            String mountId,
            ComponentFactory<NoConfig> factory);

    /** 挂载无配置组件并指定挂载选项。 */
    ComponentHandle<NoConfig> mount(
            ContextHandle context,
            String mountId,
            ComponentFactory<NoConfig> factory,
            MountOptions options);

    <C> ComponentHandle<C> reconfigure(
            ComponentHandle<C> handle,
            C config);

    void dispose(ComponentHandle<?> handle);

    void dispose(ContextHandle context);
}
