package io.knotra;

import java.util.Optional;

/** 指定 Context 的只读 Capability 视图。 */
public interface ContextView {
    String contextId();

    <T> T require(CapabilityKey<T> key);

    <T> Optional<T> find(CapabilityKey<T> key);

    ContextInfo info();
}
