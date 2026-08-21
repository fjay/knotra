package io.knotra.pf4j;

import io.knotra.ComponentState;

/** Immutable ownership fact for one component originating from an artifact. */
public record ArtifactOwnership(
        String artifactId,
        String factoryId,
        String handleId,
        String mountId,
        String parentHandleId,
        ComponentState state) {
}
