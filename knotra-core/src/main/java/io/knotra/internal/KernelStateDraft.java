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
    private Map<String, ProviderLeaseRuntime> providerLeases;
    private Map<String, ContextHandleImpl> contextHandles;
    private Map<String, PublicationSlotTerminalRef> publicationSlotRefs;

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

    Map<String, ProviderLeaseRuntime> providerLeases() {
        if (providerLeases == null) {
            providerLeases = new HashMap<>(base.index.providerLeases);
        }
        return providerLeases;
    }

    Map<String, ContextHandleImpl> contextHandles() {
        if (contextHandles == null) {
            contextHandles = new HashMap<>(base.index.contextHandles);
        }
        return contextHandles;
    }

    Map<String, PublicationSlotTerminalRef> publicationSlotRefs() {
        if (publicationSlotRefs == null) {
            publicationSlotRefs = new HashMap<>(base.index.publicationSlotRefs);
        }
        return publicationSlotRefs;
    }

    PublishedKernelState publish(RuntimeView next) {
        syncProviderLeases(next);
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
                        providerLeases == null ? base.index.providerLeases : providerLeases,
                        contextHandles == null
                                ? base.index.contextHandles
                                : contextHandles,
                        publicationSlotRefs == null
                                ? base.index.publicationSlotRefs
                                : publicationSlotRefs));
    }

    private void syncProviderLeases(RuntimeView next) {
        if (base.index.providerLeases.isEmpty() && next.registrations.isEmpty()) {
            return;
        }
        Map<String, ProviderLeaseRuntime> leases = providerLeases();
        leases.keySet().retainAll(next.registrations.keySet());
        next.registrations.forEach((registrationId, registration) ->
                leases.put(registrationId, registration.leases()));
    }
}
