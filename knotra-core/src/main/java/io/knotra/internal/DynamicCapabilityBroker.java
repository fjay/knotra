package io.knotra.internal;

import io.knotra.CapabilityKey;
import io.knotra.CapabilityUnavailableException;
import io.knotra.DynamicCapabilityClosedException;

import java.util.Objects;

/** 在协调器临界区内解析动态 Capability 的当前 provider 并建立双重租约。 */
final class DynamicCapabilityBroker {
    private final DefaultKnotraRuntime runtime;

    DynamicCapabilityBroker(DefaultKnotraRuntime runtime) {
        this.runtime = runtime;
    }

    boolean isDynamicAvailable(ActivationRuntime activation, CapabilityKey<?> key) {
        synchronized (runtime.coordinator) {
            PublishedKernelState state = runtime.publishedState();
            if (activation.dynamicCalls.isClosed()
                    || !dynamicConsumerActiveLocked(activation, state)) {
                return false;
            }
            RuntimeView current = state.view;
            return current.resolve(activation.owner.contextId(), key).isPresent();
        }
    }

    <T> DynamicLease<T> acquireDynamic(ActivationRuntime activation, CapabilityKey<T> key) {
        Objects.requireNonNull(key, "key");
        synchronized (runtime.coordinator) {
            if (!activation.dynamicCalls.tryAcquire()) {
                throw new DynamicCapabilityClosedException(
                        "dynamic capability activation is closed: " + key.name(),
                        key.name());
            }
            PublishedKernelState state = runtime.publishedState();
            boolean consumerActive =
                    dynamicConsumerActiveLocked(activation, state);
            RuntimeView current = state.view;
            RuntimeView.RegistrationData registration =
                    consumerActive
                            ? current.resolve(activation.owner.contextId(), key).orElse(null)
                            : null;
            ProviderLeaseRuntime leases =
                    registration == null
                            ? null
                            : state.index.providerLeases.get(registration.registrationId());
            if (registration == null
                    || leases == null
                    || leases.isRetired()
                    || !leases.tryAcquire()) {
                activation.dynamicCalls.release();
                throw new CapabilityUnavailableException(
                        "dynamic capability is not available: " + key.name(),
                        key);
            }
            Object value = registration.value();
            if (!key.type().isInstance(value)) {
                leases.release();
                activation.dynamicCalls.release();
                throw new IllegalStateException(
                        "capability registration type mismatch: " + key.name());
            }
            return new DynamicLease<>(
                    key.type().cast(value),
                    leases,
                    activation.dynamicCalls);
        }
    }

    private boolean dynamicConsumerActiveLocked(
            ActivationRuntime activation,
            PublishedKernelState state) {
        RuntimeView current = state.view;
        RuntimeView.ComponentData component =
                current.components.get(activation.owner.handleId());
        if (component == null || !activation.activationId.equals(component.currentActivationId())) {
            return false;
        }
        RuntimeView.ActivationData data = current.activations.get(activation.activationId);
        return data != null && RuntimeView.activationTracksGraph(data.state());
    }
}
