package io.knotra;

import java.util.Optional;

/** Read-only capability view for one Context. */
public interface ContextView {
    String contextId();

    <T> T require(CapabilityKey<T> key);

    default <T> T require(Class<T> type) {
        return require(CapabilityKey.of(type));
    }

    <T> Optional<T> find(CapabilityKey<T> key);

    default <T> Optional<T> find(Class<T> type) {
        return find(CapabilityKey.of(type));
    }

    ContextInfo info();
}
