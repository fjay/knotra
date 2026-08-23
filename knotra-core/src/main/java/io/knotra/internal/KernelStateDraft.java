package io.knotra.internal;

import java.util.HashMap;
import java.util.Map;

/**
 * Copy-on-write draft for the execution index.
 *
 * <p>A map is copied only when changed; untouched maps are reused by the next published state.
 * Instances are coordinator-local and must be published before any external reader can see the
 * corresponding RuntimeView generation.</p>
 */
final class KernelStateDraft {
    private final PublishedKernelState base;

    private Map<String, ComponentRuntime> components;
    private Map<String, MountHandleImpl> componentHandles;
    private Map<String, ActivationRuntime> activations;
    private Map<String, RegistrationHandleImpl> registrationHandles;
    private Map<String, ContextHandleImpl> contextHandles;

    KernelStateDraft(PublishedKernelState base) {
        this.base = base;
    }

    Map<String, ComponentRuntime> components() {
        if (components == null) {
            components = new HashMap<>(base.index.components);
        }
        return components;
    }

    Map<String, MountHandleImpl> componentHandles() {
        if (componentHandles == null) {
            componentHandles = new HashMap<>(base.index.componentHandles);
        }
        return componentHandles;
    }

    Map<String, ActivationRuntime> activations() {
        if (activations == null) {
            activations = new HashMap<>(base.index.activations);
        }
        return activations;
    }

    Map<String, RegistrationHandleImpl> registrationHandles() {
        if (registrationHandles == null) {
            registrationHandles = new HashMap<>(base.index.registrationHandles);
        }
        return registrationHandles;
    }

    Map<String, ContextHandleImpl> contextHandles() {
        if (contextHandles == null) {
            contextHandles = new HashMap<>(base.index.contextHandles);
        }
        return contextHandles;
    }

    PublishedKernelState publish(RuntimeView next) {
        return new PublishedKernelState(
                next,
                new ExecutionIndex(
                        components == null ? base.index.components : components,
                        componentHandles == null
                                ? base.index.componentHandles
                                : componentHandles,
                        activations == null ? base.index.activations : activations,
                        registrationHandles == null
                                ? base.index.registrationHandles
                                : registrationHandles,
                        contextHandles == null
                                ? base.index.contextHandles
                                : contextHandles));
    }
}
