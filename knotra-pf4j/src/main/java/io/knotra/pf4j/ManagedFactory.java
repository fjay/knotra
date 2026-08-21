package io.knotra.pf4j;

import io.knotra.ComponentFactory;
import io.knotra.ComponentHandle;
import io.knotra.ConfigSchema;
import io.knotra.ContextHandle;

import java.util.Objects;
import java.util.Optional;

/**
 * 活跃 artifact 工厂的内部句柄实现。
 *
 * <p>句柄只在 artifact 处于 ACTIVE 时有效；drain 会清空工厂与 schema，使陈旧句柄
 * 无法继续挂载。挂载前重新执行配置 token 与实例类型校验，防止 raw cast 绕过类型化
 * 解析。</p>
 */
final class ManagedFactory<C> implements ArtifactFactoryHandle<C> {

    final DefaultPf4jArtifactAdapter owner;
    final ManagedArtifact artifact;
    final String factoryId;
    final Class<C> configType;
    volatile Optional<ConfigSchema<C>> configSchema;
    volatile ComponentFactory<C> factory;

    ManagedFactory(
            DefaultPf4jArtifactAdapter owner,
            ManagedArtifact artifact,
            String factoryId,
            Class<C> configType,
            ComponentFactory<C> factory,
            Optional<ConfigSchema<C>> configSchema) {
        this.owner = owner;
        this.artifact = artifact;
        this.factoryId = factoryId;
        this.configType = configType;
        this.factory = factory;
        this.configSchema = configSchema;
    }

    @Override
    public String artifactId() {
        return artifact.artifactId;
    }

    @Override
    public String artifactVersion() {
        return artifact.version;
    }

    @Override
    public String artifactPath() {
        return artifact.path.toString();
    }

    @Override
    public String configTypeName() {
        return configType.getName();
    }

    @Override
    public String factoryId() {
        return factoryId;
    }

    @Override
    public Class<C> configType() {
        return configType;
    }

    @Override
    public Optional<ConfigSchema<C>> configSchema() {
        requireFactory();
        return configSchema;
    }

    @Override
    public ComponentHandle<C> mount(ContextHandle context, String mountId, C config) {
        ComponentFactory<C> current = requireFactory();
        if (config == null) {
            throw new ArtifactOperationException(
                    artifact.artifactId,
                    "mount",
                    "factory " + factoryId + " requires non-null config type "
                            + configType.getName()
                            + "; use NoConfig.INSTANCE for NoConfig factories");
        }
        if (!configType.isInstance(config)) {
            throw new ArtifactOperationException(
                    artifact.artifactId,
                    "mount",
                    "factory " + factoryId + " requires config type "
                            + configType.getName() + ", not "
                            + config.getClass().getName());
        }
        return owner.mount(this, context, mountId, current, config);
    }

    private ComponentFactory<C> requireFactory() {
        ComponentFactory<C> current = factory;
        if (current == null
                || !artifact.acceptingMounts
                || artifact.state != ArtifactState.ACTIVE) {
            throw new ArtifactOperationException(
                    artifact.artifactId,
                    "mount",
                    "factory handle is no longer usable: " + factoryId);
        }
        return current;
    }
}
