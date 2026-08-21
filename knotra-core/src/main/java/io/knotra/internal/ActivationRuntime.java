package io.knotra.internal;

import io.knotra.CapabilityKey;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

final class ActivationRuntime {
    final String activationId;
    final ComponentRuntime owner;
    final Object config;
    final long configRevision;
    final Map<String, RuntimeView.BindingData> bindings;
    final Map<String, Object> capturedValues = new ConcurrentHashMap<>();
    final LifecycleScopeImpl scope;
    final Map<String, RuntimeView.RegistrationData> stagedRegistrations =
            new ConcurrentHashMap<>();
    final List<ChildMountPlan<?>> childPlans;
    final AtomicBoolean stale = new AtomicBoolean();
    final AtomicBoolean closed = new AtomicBoolean();

    ActivationRuntime(
            String activationId,
            ComponentRuntime owner,
            Object config,
            long configRevision,
            Map<String, RuntimeView.BindingData> bindings,
            List<ChildMountPlan<?>> childPlans) {
        this.activationId = activationId;
        this.owner = owner;
        this.config = config;
        this.configRevision = configRevision;
        this.bindings = Map.copyOf(bindings);
        this.scope = LifecycleScopeImpl.root(activationId);
        this.childPlans = List.copyOf(childPlans);
    }

    void markStale() {
        stale.set(true);
    }

    RuntimeView.RegistrationData stage(
            CapabilityKey<?> key,
            Object value,
            String contextId) {
        String id = Sequences.registration();
        RuntimeView.RegistrationData registration = new RuntimeView.RegistrationData(
                id,
                key,
                contextId,
                new RuntimeView.OwnerData.Activation(activationId),
                value);
        stagedRegistrations.put(key.name(), registration);
        return registration;
    }
}
