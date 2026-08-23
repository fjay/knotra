package io.knotra.internal;

/** Immutable desired configuration tuple for one component handle. */
record DesiredComponentState(Object config, long revision) {
    static final DesiredComponentState INITIAL = new DesiredComponentState(null, 1);
}
