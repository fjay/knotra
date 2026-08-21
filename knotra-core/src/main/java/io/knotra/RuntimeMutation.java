package io.knotra;

/**
 * 宿主结构事务的意图记录接口，仅在 {@link KnotraRuntime#mutate} 回调内有效。
 *
 * <p>方法只记录意图，返回的句柄为临时对象，事务提交后才对应真实实体；事务被拒绝时
 * 所有意图不发布。全部意图在协调锁内一次性应用到视图草稿，任一意图违反结构约束
 * 都会拒绝整个事务。
 */
public interface RuntimeMutation {
    /**
     * 意图：在指定 Context 发布宿主 Capability。
     *
     * <p>值必须匹配合约类型，同一 Context 内同一名称已占用时事务被拒绝。
     * Capability 合约类型在 Runtime 生命周期内按名称固定。
     */
    <T> RegistrationHandle provide(
            ContextHandle context,
            CapabilityKey<T> key,
            T value);

    /**
     * 意图：撤销宿主注册。撤销会使依赖它的消费方重新收敛：绑定可用时重新激活，
     * REQUIRED 缺失时保持 WAITING。组件 Activation 拥有的注册不能通过此方法撤销。
     */
    void revoke(RegistrationHandle registration);

    /**
     * 意图：创建子 Context。名称必须是非空路径段且在父 Context 内唯一，
     * 规范路径冲突时事务被拒绝。
     */
    ContextHandle childContext(
            ContextHandle parent,
            String name);

    /**
     * 意图：挂载组件。配置先经工厂 schema 归一化，mountId 必须在目标 Context 内唯一，
     * 已占用时事务被拒绝。返回的句柄在事务提交后即可观察状态。
     */
    <C> ComponentHandle<C> mount(
            ContextHandle context,
            String mountId,
            ComponentFactory<C> factory,
            C config);

    /** {@link #mount(ContextHandle, String, ComponentFactory, Object)} 的变体，允许指定挂载选项。 */
    <C> ComponentHandle<C> mount(
            ContextHandle context,
            String mountId,
            ComponentFactory<C> factory,
            C config,
            MountOptions options);

    /**
     * 意图：重配置组件。配置经 schema 归一化，与当前期望等价时不产生新代际；
     * 目标已释放，或并发修改使配置代际过期时，事务被拒绝。
     */
    <C> ComponentHandle<C> reconfigure(
            ComponentHandle<C> handle,
            C config);

    /** 意图：逻辑释放组件，目标置为 DISPOSED 并递归释放其拥有的子挂载与注册。 */
    void dispose(ComponentHandle<?> handle);

    /**
     * 意图：释放 Context 及其整个子树。根 Context 必须通过 Runtime 关闭释放，
     * 直接对其释放会被事务拒绝。
     */
    void dispose(ContextHandle context);
}
