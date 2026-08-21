package io.knotra;

/**
 * Activation 拥有的可逆资源域。
 *
 * <p>Scope 组成树，默认按 LIFO 顺序释放；{@link #parallelChild(String)} 建立的并行组内
 * 直属条目同时释放，组本身仍按外层 LIFO 语义参与释放。释放异步且聚合：任一条目失败不会
 * 中断其余条目，失败条目保持可重试，并可通过 {@link ComponentHandle#retry()} 重试。
 * Scope 关闭（进入 STOPPING）后不能再登记新条目或创建子 Scope。实现线程安全。
 */
public interface LifecycleScope {
    String scopeId();

    /**
     * 登记 AutoCloseable 资源，返回原资源便于链式使用。
     *
     * @throws IllegalStateException 本 Scope 或其父 Scope 已关闭
     */
    <T extends AutoCloseable> T manage(String description, T resource);

    /** 以同步 Runnable 登记清理动作，语义同 {@link #manage(String, AutoCloseable)}。 */
    ManagedHandle onClose(String description, Runnable disposer);

    /**
     * 登记异步释放器。返回的 stage 完成才算该条目清理收敛；
     * 实现应只等待关闭请求之前已接受的工作。
     */
    ManagedHandle manageAsync(String description, AsyncDisposer disposer);

    /** 创建串行子 Scope，与父 Scope 一起按 LIFO 释放。 */
    LifecycleScope child(String description);

    /** 创建并行子 Scope：组内直属条目同时释放，该组整体仍参与外层 LIFO 释放。 */
    LifecycleScope parallelChild(String description);

    /** 返回父 Scope；激活的根 Scope 返回 null。 */
    LifecycleScope parent();

    LifecycleState state();
}
