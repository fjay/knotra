package io.knotra.internal;

import java.util.Map;

/**
 * Live execution objects belonging to one published {@link RuntimeView} generation.
 *
 * <p>The maps are immutable and have the same membership as that view. Their values are still
 * coordinator-owned mutable state machines; publication only freezes membership and identity,
 * not the fields inside those runtimes.</p>
 */
final class ExecutionIndex {
    final Map<String, ComponentRuntime> components;
    final Map<String, MountHandleImpl> componentHandles;
    final Map<String, ActivationRuntime> activations;
    final Map<String, RegistrationHandleImpl> registrationHandles;
    final Map<String, ProviderLeaseRuntime> providerLeases;
    final Map<String, ContextHandleImpl> contextHandles;

    ExecutionIndex(
            Map<String, ComponentRuntime> components,
            Map<String, MountHandleImpl> componentHandles,
            Map<String, ActivationRuntime> activations,
            Map<String, RegistrationHandleImpl> registrationHandles,
            Map<String, ProviderLeaseRuntime> providerLeases,
            Map<String, ContextHandleImpl> contextHandles) {
        this.components = Map.copyOf(components);
        this.componentHandles = Map.copyOf(componentHandles);
        this.activations = Map.copyOf(activations);
        this.registrationHandles = Map.copyOf(registrationHandles);
        this.providerLeases = Map.copyOf(providerLeases);
        this.contextHandles = Map.copyOf(contextHandles);
    }

    static ExecutionIndex empty() {
        return new ExecutionIndex(
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
    }
}
