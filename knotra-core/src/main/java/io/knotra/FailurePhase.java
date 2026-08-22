package io.knotra;

/** The lifecycle phase in which a bounded failure detail was captured. */
public enum FailurePhase {
    CONFIGURATION,
    ACTIVATION,
    CLEANUP,
    SETTLEMENT
}
