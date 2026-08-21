package io.knotra;

import java.util.Optional;

/** Activation 拥有的可逆资源域。 */
public interface LifecycleScope {
    String scopeId();

    <T extends AutoCloseable> T manage(String description, T resource);

    ManagedHandle onClose(String description, Runnable disposer);

    /** 登记一个异步清理动作。 */
    ManagedHandle onCloseAsync(String description, AsyncDisposer disposer);

    /** 登记异步资源并返回原资源，便于直接保存订阅或连接句柄。 */
    <T extends AsyncCloseable> T manageAsync(String description, T resource);

    LifecycleScope child(String description);

    LifecycleScope parallelChild(String description);

    Optional<LifecycleScope> parent();

    LifecycleState state();
}
