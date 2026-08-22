package io.knotra.internal;

import io.knotra.ComponentGoal;
import io.knotra.ComponentHandle;
import io.knotra.ComponentState;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** ComponentHandle 的内核实现。 */
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
    public ComponentHandle<C> requireActive() {
        return runtime.requireActive(this, null);
    }

    @Override
    public ComponentHandle<C> requireActive(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        return runtime.requireActive(this, timeout);
    }

    @Override
    public CompletionStage<ComponentState> reconfigureAsync(C config) {
        return runtime.reconfigure(this, config);
    }

    @Override
    public CompletionStage<ComponentState> retryAsync() {
        return runtime.retry(this);
    }

    @Override
    public CompletionStage<ComponentState> disposeAsync() {
        return runtime.dispose(this);
    }

    CompletionStage<ComponentState> rejected(String message) {
        return CompletableFuture.failedFuture(new IllegalStateException(message));
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof ComponentHandleImpl<?> handle
                && runtime == handle.runtime && id.equals(handle.id);
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
