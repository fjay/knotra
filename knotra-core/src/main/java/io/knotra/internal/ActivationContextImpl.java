package io.knotra.internal;

import io.knotra.ActivationContext;
import io.knotra.CapabilityKey;
import io.knotra.ComponentFactory;
import io.knotra.ComponentHandle;
import io.knotra.ContextInfo;
import io.knotra.LifecycleScope;
import io.knotra.MountOptions;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;


/**
 * 交给用户组件 {@code start()} 的 ActivationContext 实现。
 *
 * <p>读取只允许访问启动前固定的 BindingSet 及其捕获值，未声明的 Capability 会被拒绝，避免组件
 * 绕过依赖声明参与一致性检查。{@code provide} 与 {@code mountChild} 写入的是
 * {@link ActivationRuntime} 的暂存状态，父 Activation 验证并原子发布前对其他读取方不可见。
 * {@code start()} 返回后上下文立即关闭；若协调器已将候选标记为 stale，则新的暂存也会被拒绝。</p>
 */
final class ActivationContextImpl implements ActivationContext {
    private final DefaultKnotraRuntime runtime;
    private final ActivationRuntime activation;
    private final List<ChildMountPlan<?>> childPlans;

    ActivationContextImpl(
            DefaultKnotraRuntime runtime,
            ActivationRuntime activation,
            List<ChildMountPlan<?>> childPlans) {
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
        if (!binding.present()) {
            return Optional.empty();
        }
        // 只读取启动代际捕获的值，避免用户代码在 start() 中观察到后续发布的新注册。
        Object value = activation.capturedValues.get(key.name());
        return Optional.ofNullable(key.type().cast(value));
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
        // 先按已发布视图做类型检查；槽位占用和并发注册在 Activation 提交临界区内最终裁决。
        runtime.validateCapabilityType(key);
        if (activation.stagedRegistrations.containsKey(key.name())) {
            throw new IllegalArgumentException("capability already staged: " + key.name());
        }
        activation.stage(key, value, activation.owner.contextId);
    }

    @Override
    public <C> ComponentHandle<C> mountChild(
            String mountId,
            ComponentFactory<C> factory,
            C config) {
        return mountChild(mountId, factory, config, null);
    }

    @Override
    public <C> ComponentHandle<C> mountChild(
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
        PreparedComponent<C> prepared = PreparedComponent.prepare(
                factory,
                config,
                effectiveOptions);
        // 挂载 ID 采用乐观查重：用户工厂代码在协调器锁外执行，提交时必须基于最新视图重试冲突判定。
        if (runtime.mountIdReserved(activation.owner.contextId, mountId)) {
            throw new IllegalArgumentException("mountId is already in use: " + mountId);
        }
        ComponentHandleImpl<C> handle = runtime.createProvisionalHandle();
        addPlan(handle, mountId, prepared);
        return handle;
    }

    @Override
    public LifecycleScope lifecycle() {
        ensureOpen();
        return activation.scope;
    }

    @Override
    public ContextInfo contextInfo() {
        ensureOpen();
        return runtime.contextInfo(activation.owner.contextId);
    }

    private <C> void addPlan(
            ComponentHandleImpl<C> handle,
            String mountId,
            PreparedComponent<C> prepared) {
        childPlans.add(new ChildMountPlan<>(handle, mountId, prepared));
    }

    List<ChildMountPlan<?>> plans() {
        return new ArrayList<>(childPlans);
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
