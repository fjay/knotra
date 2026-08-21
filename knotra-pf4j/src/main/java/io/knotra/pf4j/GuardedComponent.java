package io.knotra.pf4j;

import java.util.Objects;
import java.util.Optional;

import io.knotra.ActivationContext;
import io.knotra.Component;
import io.knotra.ComponentDescriptor;
import io.knotra.ComponentFactory;
import io.knotra.ConfigSchema;

/**
 * 固定描述符并守护一个组件实例所有用户 start 调用的包装器。
 *
 * <p>包装时先校验描述符声明的依赖合约类型；启动时只替换 ActivationContext 边界，
 * 不改变组件配置和 Core 生命周期语义。</p>
 */
final class GuardedComponent<C> implements Component<C> {

    private final Component<C> delegate;
    private final ComponentDescriptor fixedDescriptor;
    private final KnotraClassLoaderPolicy policy;
    private final ArtifactMetadata metadata;

    private GuardedComponent(
            Component<C> delegate,
            ComponentDescriptor fixedDescriptor,
            KnotraClassLoaderPolicy policy,
            ArtifactMetadata metadata) {
        this.delegate = delegate;
        this.fixedDescriptor = fixedDescriptor;
        this.policy = policy;
        this.metadata = metadata;
        fixedDescriptor.sortedRequirements().forEach(requirement ->
                policy.validateContractType(requirement.key().type(), metadata.artifactId()));
    }

    static <C> Component<C> wrap(
            Component<C> delegate,
            KnotraClassLoaderPolicy policy,
            ArtifactMetadata metadata) {
        ComponentDescriptor descriptor = Objects.requireNonNull(
                delegate.descriptor(), "component.descriptor()");
        return new GuardedComponent<>(delegate, descriptor, policy, metadata);
    }

    @Override
    public ComponentDescriptor descriptor() {
        return fixedDescriptor;
    }

    @Override
    public void start(ActivationContext context, C config) throws Exception {
        delegate.start(new GuardedActivationContext(context, policy, metadata), config);
    }
}
