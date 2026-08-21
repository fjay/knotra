package io.knotra.internal;

import io.knotra.CapabilityKey;
import io.knotra.ContextInfo;
import io.knotra.RuntimeContext;

import java.util.Optional;

final class RuntimeContextImpl implements RuntimeContext {
    private final DefaultKnotraRuntime runtime;
    private final String contextId;

    RuntimeContextImpl(DefaultKnotraRuntime runtime, String contextId) {
        this.runtime = runtime;
        this.contextId = contextId;
    }

    @Override
    public String contextId() {
        return contextId;
    }

    @Override
    public <T> T require(CapabilityKey<T> key) {
        return runtime.requireInContext(contextId, key);
    }

    @Override
    public <T> Optional<T> find(CapabilityKey<T> key) {
        return runtime.findInContext(contextId, key);
    }

    @Override
    public ContextInfo info() {
        return runtime.contextInfo(contextId);
    }
}
