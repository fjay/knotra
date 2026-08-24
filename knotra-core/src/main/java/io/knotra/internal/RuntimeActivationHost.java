package io.knotra.internal;

import io.knotra.ActivationContext;

import java.util.List;

/** Adapter that keeps ActivationCoordinator decoupled from the runtime facade. */
final class RuntimeActivationHost
        implements ActivationCoordinator.ActivationHost {
    private final DefaultKnotraRuntime runtime;

    RuntimeActivationHost(DefaultKnotraRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public ActivationContext activationContext(
            ActivationRuntime activation,
            List<ChildMountPlan> plans) {
        return new ActivationContextImpl(runtime, activation, plans);
    }

    @Override
    public boolean isClosing() {
        return runtime.isClosing();
    }
}
