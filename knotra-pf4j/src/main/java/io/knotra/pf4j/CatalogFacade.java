package io.knotra.pf4j;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 目录门面。通过回调读取适配器协调后的状态，避免门面自行持有或绕过状态锁。
 */
final class CatalogFacade implements ArtifactFactoryCatalog {

    private final Supplier<List<ArtifactFactoryCatalogEntry>> entries;
    private final Function<String, ManagedFactory> activeFactory;

    CatalogFacade(
            Supplier<List<ArtifactFactoryCatalogEntry>> entries,
            Function<String, ManagedFactory> activeFactory) {
        this.entries = entries;
        this.activeFactory = activeFactory;
    }

    @Override
    public List<ArtifactFactoryCatalogEntry> list() {
        return entries.get();
    }

    @Override
    public Optional<ArtifactFactoryCatalogEntry> find(String factoryId) {
        Objects.requireNonNull(factoryId, "factoryId");
        return entries.get().stream()
                .filter(entry -> entry.factoryId().equals(factoryId))
                .findFirst();
    }

    @Override
    public Optional<ArtifactFactoryHandle> resolve(String factoryId) {
        return Optional.ofNullable(activeFactory.apply(factoryId));
    }

    @Override
    public Optional<ArtifactFactoryHandle.NoConfig> resolveNoConfig(String factoryId) {
        ManagedFactory handle = activeFactory.apply(factoryId);
        return handle instanceof ArtifactFactoryHandle.NoConfig noConfig
                ? Optional.of(noConfig)
                : Optional.empty();
    }

    @Override
    public <C> Optional<ArtifactFactoryHandle.Configured<C>> resolve(
            String factoryId,
            Class<C> configType) {
        Objects.requireNonNull(configType, "configType");
        ManagedFactory handle = activeFactory.apply(factoryId);
        if (handle == null) {
            return Optional.empty();
        }
        if (!handle.configType().equals(configType)) {
            throw new IllegalArgumentException(
                    "factory " + factoryId + " config type is "
                            + handle.configType().getName()
                            + ", not " + configType.getName());
        }
        if (!(handle instanceof ArtifactFactoryHandle.Configured<?> configured)) {
            throw new IllegalArgumentException(
                    "factory " + factoryId + " does not accept configuration");
        }
        @SuppressWarnings("unchecked")
        ArtifactFactoryHandle.Configured<C> typed =
                (ArtifactFactoryHandle.Configured<C>) configured;
        return Optional.of(typed);
    }
}
