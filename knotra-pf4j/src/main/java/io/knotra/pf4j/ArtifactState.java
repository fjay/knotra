package io.knotra.pf4j;

/** Lifecycle of a managed PF4J artifact as seen by Knotra. */
public enum ArtifactState {
    ACTIVE,
    DRAINING,
    DRAIN_FAILED,
    FAILED,
    UNLOADED
}
