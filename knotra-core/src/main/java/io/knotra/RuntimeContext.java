package io.knotra;

import java.util.Optional;
public interface RuntimeContext {
    String contextId();

    <T> T require(CapabilityKey<T> key);

    <T> Optional<T> find(CapabilityKey<T> key);

    ContextInfo info();
}
