package io.knotra.loader;

import java.util.Objects;

/**
 * Identity of the executable implementation selected by a resolver.
 *
 * <p>Two definitions with different identities require a settled replacement. A reference is
 * what the desired tree asks for; an identity includes the resolver's implementation fingerprint.
 */
public record FactoryIdentity(
        String factoryId,
        String version,
        String fingerprint) {

    public FactoryIdentity {
        factoryId = requireText(factoryId, "factoryId");
        version = version == null ? "" : version.trim();
        fingerprint = requireText(fingerprint, "fingerprint");
    }

    public static FactoryIdentity of(
            String factoryId,
            String version,
            String fingerprint) {
        return new FactoryIdentity(factoryId, version, fingerprint);
    }

    public static FactoryIdentity fromRef(FactoryRef ref, String fingerprint) {
        Objects.requireNonNull(ref, "ref");
        return new FactoryIdentity(ref.factoryId(), ref.version(), fingerprint);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
