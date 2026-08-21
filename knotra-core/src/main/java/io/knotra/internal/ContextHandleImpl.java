package io.knotra.internal;

import io.knotra.ContextHandle;
import io.knotra.ContextInfo;
import io.knotra.ContextState;
import io.knotra.RuntimeContext;

import java.util.concurrent.CompletionStage;

final class ContextHandleImpl implements ContextHandle {
    final DefaultKnotraRuntime runtime;
    final String id;

    ContextHandleImpl(DefaultKnotraRuntime runtime, String id) {
        this.runtime = runtime;
        this.id = id;
    }

    @Override
    public String contextId() {
        return id;
    }

    @Override
    public ContextInfo contextInfo() {
        return runtime.contextInfo(id);
    }

    public RuntimeContext context() {
        return new RuntimeContextImpl(runtime, id);
    }

    @Override
    public ContextState state() {
        return runtime.contextState(id);
    }

    @Override
    public CompletionStage<Void> disposeAsync() {
        return runtime.disposeContext(this);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (other == null || other.getClass() != getClass()) {
            return false;
        }
        ContextHandleImpl handle = (ContextHandleImpl) other;
        return runtime == handle.runtime && id.equals(handle.id);
    }

    @Override
    public int hashCode() {
        return 31 * System.identityHashCode(runtime) + id.hashCode();
    }

    @Override
    public String toString() {
        return "ContextHandle[" + id + "]";
    }
}
