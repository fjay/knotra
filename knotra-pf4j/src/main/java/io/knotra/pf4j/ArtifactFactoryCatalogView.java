package io.knotra.pf4j;

/** Package-private immutable projection of a managed factory into catalog metadata. */
record ArtifactFactoryCatalogView(
        String artifactId,
        String artifactVersion,
        String artifactPath,
        String factoryId,
        String configTypeName) implements ArtifactFactoryCatalogEntry {
}
