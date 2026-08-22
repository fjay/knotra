package io.knotra.pf4j;

import io.knotra.ContextHandle;

/**
 * Read-only executable view of one factory published by an active artifact.
 *
 * <p>The root type intentionally exposes identity only. Mount operations are available on the
 * no-config and configured subtypes so callers never supply a placeholder configuration.</p>
 */
public interface ArtifactFactoryHandle extends ArtifactFactoryCatalogEntry {

    /** Artifact discovery time configuration contract; never retained by stable snapshot views. */
    Class<?> configType();

    default boolean noConfig() {
        return configType() == io.knotra.NoConfig.class;
    }

    /** Mount a factory that declares no host-visible configuration. */
    interface NoConfig extends ArtifactFactoryHandle {

        io.knotra.MountHandle mount(ContextHandle context, String mountId);
    }

    /** Mount a factory whose configuration contract is shared with the host. */
    interface Configured<C> extends ArtifactFactoryHandle {

        /**
         * Converts host-owned raw configuration to the factory's declared type.
         *
         * <p>The decoder must return a non-null instance of {@link #configType()}. This is an
         * active artifact view and becomes unusable after drain or unload.</p>
         */
        C decodeConfig(Object rawConfig);

        io.knotra.ConfiguredMountHandle<C> mount(ContextHandle context, String mountId, C config);
    }
}
