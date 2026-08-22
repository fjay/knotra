package io.knotra.pf4j;

import java.util.Objects;

import io.knotra.ComponentFactory;
import io.knotra.ConfigDecoder;
import io.knotra.ContextHandle;
import io.knotra.NoConfig;

/**
 * Internal active factory view. Mounting is split into the two leaf classes so the public type
 * never presents a no-config mount as a configured mount.
 */
abstract class ManagedFactory implements ArtifactFactoryHandle {

    final DefaultPf4jArtifactAdapter owner;
    final ManagedArtifact artifact;
    final String factoryId;
    final Class<?> configType;
    volatile ConfigDecoder<?> decoder;
    volatile ComponentFactory<?> factory;

    ManagedFactory(
            DefaultPf4jArtifactAdapter owner,
            ManagedArtifact artifact,
            String factoryId,
            Class<?> configType,
            ConfigDecoder<?> decoder,
            ComponentFactory<?> factory) {
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
    public Class<?> configType() {
        return configType;
    }

    final <T> T requireUsable(String phase) {
        Object current = "mount".equals(phase) ? factory : decoder;
        if (current == null
                || !artifact.acceptingMounts
                || artifact.state != ArtifactState.ACTIVE) {
            throw new ArtifactOperationException(
                    artifact.artifactId,
                    phase,
                    "factory handle is no longer usable: " + factoryId);
        }
        return cast(current);
    }

    @SuppressWarnings("unchecked")
    private static <T> T cast(Object value) {
        return (T) value;
    }

    static final class NoConfigFactory extends ManagedFactory
            implements ArtifactFactoryHandle.NoConfig {

        NoConfigFactory(
                DefaultPf4jArtifactAdapter owner,
                ManagedArtifact artifact,
                String factoryId,
                ConfigDecoder<io.knotra.NoConfig> decoder,
                ComponentFactory<io.knotra.NoConfig> factory) {
            super(owner, artifact, factoryId, io.knotra.NoConfig.class, decoder, factory);
        }

        @Override
        public io.knotra.MountHandle mount(ContextHandle context, String mountId) {
            ComponentFactory<io.knotra.NoConfig> current = requireUsable("mount");
            return owner.mount(this, context, mountId, current);
        }
    }

    static final class ConfiguredFactory<C> extends ManagedFactory
            implements ArtifactFactoryHandle.Configured<C> {

        ConfiguredFactory(
                DefaultPf4jArtifactAdapter owner,
                ManagedArtifact artifact,
                String factoryId,
                Class<C> configType,
                ConfigDecoder<C> decoder,
                ComponentFactory<C> factory) {
            super(owner, artifact, factoryId, configType, decoder, factory);
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
        public io.knotra.ConfiguredMountHandle<C> mount(
                ContextHandle context,
                String mountId,
                C config) {
            ComponentFactory<C> current = requireUsable("mount");
            if (config == null) {
                throw new ArtifactOperationException(
                        artifact.artifactId,
                        "mount",
                        "factory " + factoryId + " requires non-null config type "
                                + configType.getName());
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
    }

    static <C> ManagedFactory create(
            DefaultPf4jArtifactAdapter owner,
            ManagedArtifact artifact,
            String factoryId,
            Class<C> configType,
            ConfigDecoder<C> decoder,
            ComponentFactory<C> factory) {
        Objects.requireNonNull(configType, "configType");
        if (configType == io.knotra.NoConfig.class) {
            @SuppressWarnings("unchecked")
            ConfigDecoder<io.knotra.NoConfig> noConfigDecoder =
                    (ConfigDecoder<io.knotra.NoConfig>) decoder;
            @SuppressWarnings("unchecked")
            ComponentFactory<io.knotra.NoConfig> noConfigFactory =
                    (ComponentFactory<io.knotra.NoConfig>) factory;
            return new NoConfigFactory(
                    owner,
                    artifact,
                    factoryId,
                    noConfigDecoder,
                    noConfigFactory);
        }
        return new ConfiguredFactory<>(
                owner,
                artifact,
                factoryId,
                configType,
                decoder,
                factory);
    }
}
