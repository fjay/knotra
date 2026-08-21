package io.knotra.pf4j;

import java.util.Objects;

import io.knotra.Component;
import io.knotra.ComponentFactory;

/**
 * 包内工厂适配层，用于消除绕过 artifact 来源与 guarded 边界的入口。
 *
 * <p>工厂 ID 在包装时一次性读取并固定；每次创建组件都会继续套上 guarded 组件。
 * 配置归一化仍委托给 Core factory，raw 配置解码则留在 artifact catalog 边界。</p>
 */
final class GuardedComponentFactory<C> implements ComponentFactory<C> {

    private final ComponentFactory<C> delegate;
    private final String fixedFactoryId;
    private final KnotraClassLoaderPolicy policy;
    private final ArtifactMetadata metadata;

    private GuardedComponentFactory(
            ComponentFactory<C> delegate,
            String fixedFactoryId,
            KnotraClassLoaderPolicy policy,
            ArtifactMetadata metadata) {
        this.delegate = delegate;
        this.fixedFactoryId = fixedFactoryId;
        this.policy = policy;
        this.metadata = metadata;
    }

    static <C> ComponentFactory<C> wrap(
            ComponentFactory<C> factory,
            KnotraClassLoaderPolicy policy,
            ArtifactMetadata metadata) {
        Objects.requireNonNull(factory, "factory");
        policy.validateInterface(factory.getClass(), ComponentFactory.class, metadata.artifactId());
        String factoryId = factory.factoryId();
        if (factoryId == null || factoryId.isBlank()) {
            throw new ArtifactOperationException(
                    metadata.artifactId(),
                    "factory",
                    "factory returned a blank factory id");
        }
        return new GuardedComponentFactory<>(
                factory,
                factoryId.trim(),
                policy,
                metadata);
    }

    @Override
    public String factoryId() {
        return fixedFactoryId;
    }

    @Override
    public Component<C> create() {
        return GuardedComponent.wrap(delegate.create(), policy, metadata);
    }

    @Override
    public C normalizeConfig(C config) throws Exception {
        return delegate.normalizeConfig(config);
    }
}
