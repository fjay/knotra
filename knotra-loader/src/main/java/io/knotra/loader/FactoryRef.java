package io.knotra.loader;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Stable reference used by a resolver to locate a component implementation.
 */
public record FactoryRef(
        String factoryId,
        String version,
        Map<String, String> metadata) {

    public FactoryRef {
        factoryId = requireText(factoryId, "factoryId");
        version = normalize(version);
        Map<String, String> safeMetadata = new LinkedHashMap<>();
        if (metadata != null) {
            metadata.forEach((name, value) -> {
                if (name != null && !name.isBlank() && value != null) {
                    safeMetadata.put(name.trim(), value.trim());
                }
            });
        }
        metadata = Map.copyOf(safeMetadata);
    }

    public static FactoryRef of(String factoryId) {
        return new FactoryRef(factoryId, "", Map.of());
    }

    public static FactoryRef of(String factoryId, String version) {
        return new FactoryRef(factoryId, version, Map.of());
    }

    public String metadata(String name) {
        Objects.requireNonNull(name, "name");
        return metadata.get(name);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
