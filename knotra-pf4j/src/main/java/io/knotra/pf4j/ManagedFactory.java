package io.knotra.pf4j;

import io.knotra.ComponentFactory;
import io.knotra.ComponentHandle;
import io.knotra.ConfigDecoder;
import io.knotra.ContextHandle;

import java.util.Objects;

/**
 * 活跃 artifact 工厂的内部句柄实现。
 *
 * <p>句柄只在 artifact 处于 ACTIVE 时有效；drain 会清空工厂与 decoder，使陈旧句柄
 * 无法继续挂载或解码。挂载前重新执行配置 token 与实例类型校验，防止 raw cast
 * 绕过类型化解析。</p>
 */
final class ManagedFactory<C> implements ArtifactFactoryHandle<C> {

    final DefaultPf4jArtifactAdapter owner;
    final ManagedArtifact artifact;
    final String factoryId;
    final Class<C> configType;
    volatile ConfigDecoder<C> decoder;
    volatile ComponentFactory<C> factory;

    ManagedFactory(
            DefaultPf4jArtifactAdapter owner,
            ManagedArtifact artifact,
            String factoryId,
            Class<C> configType,
            ConfigDecoder<C> decoder,
            ComponentFactory<C> factory) {
        this.owner = owner;
        this.artifact = artifact;
        this.factoryId = factoryId;
        this.configType = configType;
        this.decoder = decoder;
        this.factory = factory;
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
    public C decodeConfig(Object rawConfig) {
        ConfigDecoder<C> current = requireUsable("decode");
        C decoded;
        try {
            decoded = current.decode(rawConfig);
        } catch (Throwable failure) {
            throw new ArtifactOperationException(
                    artifact.artifactId,
                    "decode",
                    "factory " + factoryId + " could not decode configuration: "
                            + FailureText.describe(failure));
        }
        if (decoded == null || !configType.isInstance(decoded)) {
            throw new ArtifactOperationException(
                    artifact.artifactId,
                    "decode",
                    "factory " + factoryId + " decoder must produce "
                            + configType.getName() + ", not "
                            + (decoded == null ? "null" : decoded.getClass().getName()));
        }
        return decoded;
    }

    @Override
    public ComponentHandle<C> mount(ContextHandle context, String mountId, C config) {
        ComponentFactory<C> current = requireUsable("mount");
        if (config == null) {
            throw new ArtifactOperationException(
                    artifact.artifactId,
                    "mount",
                    "factory " + factoryId + " requires non-null config type "
                            + configType.getName()
                            + "; use mount(context, mountId) when the decoder supplies defaults");
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

    private <T> T requireUsable(String phase) {
        T current = phase.equals("mount") ? (T) factory : (T) decoder;
        if (current == null
                || !artifact.acceptingMounts
                || artifact.state != ArtifactState.ACTIVE) {
            throw new ArtifactOperationException(
                    artifact.artifactId,
                    phase,
                    "factory handle is no longer usable: " + factoryId);
        }
        return current;
    }
}
