package io.knotra.pf4j;

/**
 * Read-only metadata for one active artifact factory.
 *
 * <p>The tokenless catalog deliberately exposes stable text only. It cannot mount,
 * normalize configuration, or retain an executable factory or configuration schema.</p>
 */
public interface ArtifactFactoryCatalogEntry {

    String artifactId();

    String artifactVersion();

    String artifactPath();

    String factoryId();

    /** Binary-independent configuration token name used for catalog display and diagnostics. */
    String configTypeName();
}
