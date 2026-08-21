package io.knotra.internal;

import io.knotra.CleanupState;
import io.knotra.ManagedHandle;

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
