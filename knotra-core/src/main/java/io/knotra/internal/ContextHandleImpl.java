package io.knotra.internal;

import io.knotra.ContextHandle;
import io.knotra.ContextInfo;
import io.knotra.ContextState;
import io.knotra.RuntimeContext;

import java.util.concurrent.CompletionStage;


/**
 * 层级 Context 的句柄实现。
 *
 * <p>ID 在 {@link DefaultKnotraRuntime} 的视图和句柄表中唯一；读取与处置均委托回 Runtime，
 * 句柄不持有组件或 Capability 值。Context 成功清理后会从视图移除，但句柄身份仍可与随后重建的同名 Context 区分。</p>
 */
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
