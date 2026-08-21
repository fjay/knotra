package io.knotra;

public interface LifecycleScope {
    String scopeId();

    <T extends AutoCloseable> T manage(String description, T resource);

    ManagedHandle onClose(String description, Runnable disposer);

    ManagedHandle manageAsync(String description, AsyncDisposer disposer);

    LifecycleScope child(String description);

    LifecycleScope parallelChild(String description);

    LifecycleScope parent();

    LifecycleState state();
}
