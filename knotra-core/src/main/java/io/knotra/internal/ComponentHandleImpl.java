package io.knotra.internal;

import io.knotra.ComponentGoal;
import io.knotra.ComponentHandle;
import io.knotra.ComponentState;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

final class ComponentHandleImpl<C> implements ComponentHandle<C> {
    final DefaultKnotraRuntime runtime;
    final String id;

    ComponentHandleImpl(DefaultKnotraRuntime runtime, String id) {
        this.runtime = runtime;
        this.id = id;
    }

    @Override
    public String handleId() {
        return id;
    }

    @Override
    public String mountId() {
        return runtime.componentMountId(id);
    }

    @Override
    public String componentId() {
        return runtime.componentField(id, component -> component.componentId());
    }

    @Override
    public String factoryId() {
        return runtime.componentField(id, component -> component.factoryId());
    }

    @Override
    public String contextId() {
        return runtime.componentField(id, component -> component.contextId());
    }

    @Override
    public ComponentState state() {
        return runtime.componentState(id);
    }

    @Override
    public ComponentGoal goal() {
        return runtime.componentGoal(id);
    }

    @Override
    public long configRevision() {
        return runtime.componentConfigRevision(id);
    }

    @Override
    public CompletionStage<ComponentState> whenSettled() {
        return runtime.whenSettled(id);
    }

    @Override
    public CompletionStage<ComponentState> reconfigure(C config) {
        return runtime.reconfigure(this, config);
    }

    @Override
    public CompletionStage<ComponentState> retry() {
        return runtime.retry(this);
    }

    @Override
    public CompletionStage<ComponentState> dispose() {
        return runtime.dispose(this);
    }

    CompletionStage<ComponentState> rejected(String message) {
        CompletableFuture<ComponentState> future = new CompletableFuture<>();
        future.completeExceptionally(new IllegalStateException(message));
        return future;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (other == null || other.getClass() != getClass()) {
            return false;
        }
        ComponentHandleImpl<?> handle = (ComponentHandleImpl<?>) other;
        return runtime == handle.runtime && id.equals(handle.id);
    }

    @Override
    public int hashCode() {
        return 31 * System.identityHashCode(runtime) + id.hashCode();
    }

    @Override
    public String toString() {
        return "ComponentHandle[" + id + "]";
    }
}
