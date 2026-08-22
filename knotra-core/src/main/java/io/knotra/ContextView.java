package io.knotra;

import java.util.Optional;

/** 单个 Context 的只读能力视图。 */
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
