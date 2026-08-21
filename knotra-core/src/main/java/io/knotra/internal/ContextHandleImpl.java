package io.knotra.internal;

import io.knotra.ContextHandle;
import io.knotra.ContextInfo;
import io.knotra.ContextState;
import io.knotra.ContextView;

import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

/** Context 树节点的稳定结构句柄。 */
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
    public ContextInfo info() {
        return runtime.contextInfo(id);
    }

    @Override
    public ContextView view() {
        return new ContextViewImpl(runtime, id);
    }

    @Override
    public ContextState state() {
        return runtime.contextState(id);
    }

    @Override
    public CompletionStage<ContextState> disposeAsync() {
        return runtime.disposeContext(this).handle((ignored, error) -> {
            ContextState settled = state();
            if (error != null && settled != ContextState.FAILED) {
                throw new CompletionException(error);
            }
            return settled;
        });
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof ContextHandleImpl handle
                && runtime == handle.runtime && id.equals(handle.id);
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
