package io.knotra.pf4j;

import java.util.Objects;
import java.util.Optional;

import io.knotra.ActivationContext;
import io.knotra.CapabilityKey;
import io.knotra.Component;
import io.knotra.ComponentDescriptor;
import io.knotra.ComponentFactory;
import io.knotra.DynamicCapability;
import io.knotra.ContextInfo;
import io.knotra.LifecycleScope;
import io.knotra.MountOptions;

/**
 * artifact 组件启动时的受控 ActivationContext 边界。
 *
 * <p>所有 Capability 合约类型在进入 Core 类型表之前都会校验共享合约身份；子挂载
 * 会被递归包装，并强制继承 artifact 来源，防止插件改写 provenance 或借宿主挂载
 * 逃逸出受控边界。</p>
 */
final class GuardedActivationContext implements ActivationContext {

    private final ActivationContext delegate;
    private final KnotraClassLoaderPolicy policy;
    private final ArtifactMetadata metadata;

    GuardedActivationContext(
            ActivationContext delegate,
            KnotraClassLoaderPolicy policy,
            ArtifactMetadata metadata) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.metadata = Objects.requireNonNull(metadata, "metadata");
    }

    @Override
    public <T> T require(CapabilityKey<T> key) {
        validate(key);
        return delegate.require(key);
    }

    @Override
    public <T> Optional<T> find(CapabilityKey<T> key) {
        validate(key);
        return delegate.find(key);
    }

    @Override
    public <T> DynamicCapability<T> subscribe(CapabilityKey<T> key) {
        validate(key);
        return delegate.subscribe(key);
    }

    @Override
    public <T> void provide(CapabilityKey<T> key, T value) {
        validate(key);
        delegate.provide(key, value);
    }

    @Override
    public <C> io.knotra.ComponentHandle<C> mountChild(
            String mountId,
            ComponentFactory<C> factory,
            C config) {
        return mountChild(mountId, factory, config, null);
    }

    @Override
    public <C> io.knotra.ComponentHandle<C> mountChild(
            String mountId,
            ComponentFactory<C> factory,
            C config,
            MountOptions options) {
        // 忽略调用方传入的 origin；artifact 来源必须被精确继承到所有子挂载。
        ComponentFactory<C> guarded = GuardedComponentFactory.wrap(
                factory,
                policy,
                metadata);
        return delegate.mountChild(
                mountId,
                guarded,
                config,
                new MountOptions(metadata.origin(), options == null
                        ? metadata.options().metadata()
                        : options.metadata()));
    }
    @Override
    public io.knotra.ComponentHandle<io.knotra.NoConfig> mountChild(
            String mountId,
            ComponentFactory<io.knotra.NoConfig> factory) {
        return mountChild(
                mountId,
                factory,
                io.knotra.NoConfig.INSTANCE,
                io.knotra.MountOptions.DEFAULT);
    }

    @Override
    public io.knotra.ComponentHandle<io.knotra.NoConfig> mountChild(
            String mountId,
            ComponentFactory<io.knotra.NoConfig> factory,
            MountOptions options) {
        return mountChild(
                mountId,
                factory,
                io.knotra.NoConfig.INSTANCE,
                options);
    }

    @Override
    public LifecycleScope lifecycle() {
        return delegate.lifecycle();
    }

    @Override
    public ContextInfo info() {
        return delegate.info();
    }

    private void validate(CapabilityKey<?> key) {
        Objects.requireNonNull(key, "key");
        policy.validateContractType(key.type(), metadata.artifactId());
    }
}
