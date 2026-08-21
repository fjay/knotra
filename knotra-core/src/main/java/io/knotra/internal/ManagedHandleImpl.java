package io.knotra.internal;

import io.knotra.CleanupState;
import io.knotra.ManagedHandle;


/**
 * LifecycleScope 受管条目的稳定诊断句柄。
 *
 * <p>句柄不持有释放器或资源实例，只保存条目 ID 并委托 {@link LifecycleScopeImpl} 加锁读取状态；
 * 因此 Snapshot 与诊断不会延长组件资源或 ClassLoader 的生命周期。</p>
 */
final class ManagedHandleImpl implements ManagedHandle {
    private final LifecycleScopeImpl scope;
    private final String id;

    ManagedHandleImpl(LifecycleScopeImpl scope, String id) {
        this.scope = scope;
        this.id = id;
    }

    @Override
    public String entryId() {
        return id;
    }

    @Override
    public String description() {
        return scope.entryDescription(id);
    }

    @Override
    public CleanupState state() {
        return scope.entryState(id);
    }

    @Override
    public int attempts() {
        return scope.entryAttempts(id);
    }

    @Override
    public String lastError() {
        return scope.entryLastError(id);
    }
}
