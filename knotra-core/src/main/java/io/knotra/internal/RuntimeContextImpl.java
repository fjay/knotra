package io.knotra.internal;

import io.knotra.CapabilityKey;
import io.knotra.ContextInfo;
import io.knotra.RuntimeContext;

import java.util.Optional;


/**
 * 宿主读取指定 Context 可见 Capability 的边界。
 *
 * <p>该对象只是 {@link DefaultKnotraRuntime} 的无状态视图，每次读取解析当时已发布的
 * {@link RuntimeView}；它不缓存 Capability 值，也不暴露组件实例或 LifecycleScope。</p>
 */
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
