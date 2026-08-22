package io.knotra.internal;

import io.knotra.ActivationContext;
import io.knotra.CapabilityKey;
import io.knotra.CapabilityRequirement;
import io.knotra.DynamicCapability;
import io.knotra.ComponentFactory;
import io.knotra.MountHandle;
import io.knotra.ConfiguredMountHandle;
import io.knotra.ContextInfo;
import io.knotra.LifecycleScope;
import io.knotra.MountOptions;
import io.knotra.NoConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 交给用户组件 start 的一次性 ActivationContext。 */
final class ActivationContextImpl implements ActivationContext {
    private final DefaultKnotraRuntime runtime;
    private final ActivationRuntime activation;
    private final List<ChildMountPlan> childPlans;

    ActivationContextImpl(
            DefaultKnotraRuntime runtime,
            ActivationRuntime activation,
            List<ChildMountPlan> childPlans) {
        this.runtime = runtime;
        this.activation = activation;
        this.childPlans = childPlans;
    }

    @Override
    public <T> T require(CapabilityKey<T> key) {
        return find(key).orElseThrow(() -> new IllegalArgumentException(
                "required capability is not available: " + key.name()));
    }

    @Override
    public <T> Optional<T> find(CapabilityKey<T> key) {
        ensureOpen();
        Objects.requireNonNull(key, "key");
        RuntimeView.BindingData binding = activation.bindings.get(key.name());
        if (binding == null) {
            throw new IllegalArgumentException(
                    "capability is not declared by component: " + key.name());
        }
        if (binding.binding() ==
                CapabilityRequirement.CapabilityBinding.DYNAMIC) {
            throw new IllegalArgumentException(
                    "dynamic capability must be accessed through subscribe: " + key.name());
        }
        if (!binding.present()) {
            return Optional.empty();
        }
        return Optional.ofNullable(key.type().cast(
                activation.capturedValues.get(key.name())));
    }

    @Override
    public <T> DynamicCapability<T> subscribe(CapabilityKey<T> key) {
        ensureOpen();
        ensureNotStale();
        Objects.requireNonNull(key, "key");
        CapabilityRequirement requirement = activation.owner.prepared.descriptor()
                .requirement(key)
                .orElseThrow(() -> new IllegalArgumentException(
                        "capability is not declared by component: " + key.name()));
        if (!requirement.key().equals(key)) {
            throw new IllegalArgumentException(
                    "capability declaration type mismatch: " + key.name());
        }
        if (requirement.binding() ==
                CapabilityRequirement.CapabilityBinding.DYNAMIC) {
            return new DynamicCapabilityImpl<>(runtime, activation, key);
        }
        throw new IllegalArgumentException(
                "subscribe is only valid for dynamic capability: " + key.name());
    }

    @Override
    public <T> void provide(CapabilityKey<T> key, T value) {
        ensureOpen();
        ensureNotStale();
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        if (!key.type().isInstance(value)) {
            throw new IllegalArgumentException(
                    "capability value is not an instance of " + key.typeName());
        }
        runtime.validateCapabilityType(key);
        if (activation.stagedRegistrations.containsKey(key.name())) {
            throw new IllegalArgumentException("capability already staged: " + key.name());
        }
        activation.stage(key, value);
    }

    @Override
    public <C> ConfiguredMountHandle<C> mountChild(
            String mountId,
            ComponentFactory<C> factory,
            C config) {
        return mountChild(mountId, factory, config, null);
    }

    @Override
    public <C> ConfiguredMountHandle<C> mountChild(
            String mountId,
            ComponentFactory<C> factory,
            C config,
            MountOptions options) {
        ensureOpen();
        ensureNotStale();
        if (mountId == null || mountId.isBlank()) {
            throw new IllegalArgumentException("mountId must not be blank");
        }
        for (ChildMountPlan plan : childPlans) {
            if (plan.mountId().equals(mountId)) {
                throw new IllegalArgumentException("mountId is already staged: " + mountId);
            }
        }
        MountOptions effectiveOptions = options == null
                ? new MountOptions(activation.owner.prepared.options().origin())
                : options;
        PreparedComponent<C> prepared = PreparedComponent.prepare(factory, config, effectiveOptions);
        if (runtime.mountIdReserved(activation.owner.contextId, mountId)) {
            throw new IllegalArgumentException("mountId is already in use: " + mountId);
        }
        ConfiguredMountHandleImpl<C> handle = new ConfiguredMountHandleImpl<>(
                runtime,
                Sequences.handle(),
                new MountHandleImpl.Identity(
                        mountId,
                        prepared.descriptor().componentId(),
                        prepared.factoryId(),
                        activation.owner.contextId));
        childPlans.add(new ChildMountPlan(handle, mountId, prepared));
        return handle;
    }

    @Override
    public MountHandle mountChild(
            String mountId,
            ComponentFactory<NoConfig> factory) {
        return mountPlain(mountId, factory, null);
    }

    @Override
    public MountHandle mountChild(
            String mountId,
            ComponentFactory<NoConfig> factory,
            MountOptions options) {
        return mountPlain(mountId, factory, options);
    }

    @Override
    public LifecycleScope lifecycle() {
        ensureOpen();
        return activation.scope;
    }

    @Override
    public ContextInfo info() {
        ensureOpen();
        return runtime.contextInfo(activation.owner.contextId);
    }

    List<ChildMountPlan> plans() {
        return new ArrayList<>(childPlans);
    }

    private MountHandleImpl mountPlain(
            String mountId,
            ComponentFactory<NoConfig> factory,
            MountOptions options) {
        ensureOpen();
        ensureNotStale();
        if (mountId == null || mountId.isBlank()) {
            throw new IllegalArgumentException("mountId must not be blank");
        }
        for (ChildMountPlan plan : childPlans) {
            if (plan.mountId().equals(mountId)) {
                throw new IllegalArgumentException("mountId is already staged: " + mountId);
            }
        }
        MountOptions effectiveOptions = options == null
                ? new MountOptions(activation.owner.prepared.options().origin())
                : options;
        PreparedComponent<NoConfig> prepared =
                PreparedComponent.prepare(factory, NoConfig.INSTANCE, effectiveOptions);
        if (runtime.mountIdReserved(activation.owner.contextId, mountId)) {
            throw new IllegalArgumentException("mountId is already in use: " + mountId);
        }
        PlainMountHandleImpl handle = new PlainMountHandleImpl(
                runtime,
                Sequences.handle(),
                new MountHandleImpl.Identity(
                        mountId,
                        prepared.descriptor().componentId(),
                        prepared.factoryId(),
                        activation.owner.contextId));
        childPlans.add(new ChildMountPlan(handle, mountId, prepared));
        return handle;
    }

    private void ensureOpen() {
        if (activation.closed.get()) {
            throw new IllegalStateException("activation context is closed");
        }
    }

    private void ensureNotStale() {
        if (activation.stale.get()) {
            throw new IllegalStateException("activation is stale");
        }
    }
}
