package io.knotra;

import java.util.Objects;

/**
 * SPI adapter type for a component mount that has no public configuration contract.
 *
 * <p>The simple runtime mount overloads use this marker type so NoConfig remains an advanced/SPI
 * concern. Advanced transaction callers may still use {@code ComponentFactory<NoConfig>} directly;
 * use {@link #adapt(ComponentFactory)} when bridging such a factory into the simple facade.</p>
 */
public interface MountFactory extends ComponentFactory<NoConfig> {

    static MountFactory adapt(ComponentFactory<NoConfig> factory) {
        Objects.requireNonNull(factory, "factory");
        return new MountFactory() {
            @Override
            public String factoryId() {
                return factory.factoryId();
            }

            @Override
            public Component<NoConfig> create() {
                return factory.create();
            }

            @Override
            public NoConfig normalizeConfig(NoConfig config) throws Exception {
                return factory.normalizeConfig(config);
            }
        };
    }
}
