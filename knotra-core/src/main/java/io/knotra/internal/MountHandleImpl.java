package io.knotra.internal;

import io.knotra.ComponentGoal;
import io.knotra.ComponentState;
import io.knotra.MountHandle;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/** Shared runtime state for one stable mount identity. */
abstract class MountHandleImpl implements MountHandle {
    final DefaultKnotraRuntime runtime;
    final String id;
    // Mount identity is fixed at creation and survives removal from the active view.
    private final Identity identity;

    MountHandleImpl(DefaultKnotraRuntime runtime, String id, Identity identity) {
        this.runtime = runtime;
        this.id = id;
        this.identity = Objects.requireNonNull(identity, "identity");
    }

    @Override
    public String handleId() {
        return id;
    }

    @Override
    public String mountId() {
        return identity.mountId();
    }

    @Override
    public String componentId() {
        return identity.componentId();
    }

    @Override
    public String factoryId() {
        return identity.factoryId();
    }

    @Override
    public String contextId() {
        return identity.contextId();
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
    public MountHandle requireActive() {
        return runtime.requireActive(this, null);
    }

    @Override
    public MountHandle requireActive(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        return runtime.requireActive(this, timeout);
    }

    @Override
    public CompletionStage<ComponentState> retryAsync() {
        return runtime.retry(this);
    }

    @Override
    public CompletionStage<ComponentState> disposeAsync() {
        return runtime.dispose(this);
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof MountHandleImpl handle
                && runtime == handle.runtime && id.equals(handle.id);
    }

    @Override
    public int hashCode() {
        return 31 * System.identityHashCode(runtime) + id.hashCode();
    }

    @Override
    public String toString() {
        return "MountHandle[" + id + "]";
    }

    /** Stable logical identity containing strings only; no Class, ClassLoader, or component value. */
    record Identity(
            String mountId,
            String componentId,
            String factoryId,
            String contextId) {

        Identity {
            Objects.requireNonNull(mountId, "mountId");
            Objects.requireNonNull(componentId, "componentId");
            Objects.requireNonNull(factoryId, "factoryId");
            Objects.requireNonNull(contextId, "contextId");
        }
    }
}
