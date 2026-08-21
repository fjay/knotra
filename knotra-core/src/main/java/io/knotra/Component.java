package io.knotra;

/**
 * 组件实例契约：由工厂创建、由 Runtime 驱动其激活生命周期。
 *
 * <p>组件通过 {@link ComponentDescriptor} 预先声明依赖需求；同一实例可能被多次启动，
 * 每次启动获得新的 Activation 与新的 BindingSet。跨激活需要释放的资源必须登记到当次
 * {@code start()} 收到的 {@link ActivationContext} 的 LifecycleScope，使释放可逆、可收敛。
 */
public interface Component<C> {
    ComponentDescriptor descriptor();

    /**
     * 启动组件的一次 Activation，在协调器锁外调用。
     *
     * <p>抛出异常会使本次激活失败并触发回滚；启动期间通过 {@code context} 暂存的注册与
     * 子挂载只有在正常返回且验证成功后才发布。申请的资源必须登记到
     * {@code context.lifecycle()}，不要自行保存后手工释放。
     */
    void start(ActivationContext context, C config) throws Exception;
}
