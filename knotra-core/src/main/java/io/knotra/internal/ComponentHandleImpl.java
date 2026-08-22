package io.knotra.internal;

import io.knotra.ComponentGoal;
import io.knotra.ComponentHandle;
import io.knotra.ComponentState;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** ComponentHandle 的内核实现。 */
final class ComponentHandleImpl<C> implements ComponentHandle<C> {
    final DefaultKnotraRuntime runtime;
    final String id;
    // 挂载身份在创建时确定且终生不变；组件从视图移除后句柄仍可报告稳定逻辑标识。
    private final Identity identity;

    ComponentHandleImpl(DefaultKnotraRuntime runtime, String id, Identity identity) {
        this.runtime = runtime;
        this.id = id;
        this.identity = Objects.requireNonNull(identity, "identity");
    }

    @Override
    public String handleId() {
        return id;
    }

    @Override
    public String mountId() {
        return identity.mountId();
    }

    @Override
    public String componentId() {
        return identity.componentId();
    }

    @Override
    public String factoryId() {
        return identity.factoryId();
    }

    @Override
    public String contextId() {
        return identity.contextId();
    }

    @Override
    public ComponentState state() {
        return runtime.componentState(id);
    }

    @Override
    public ComponentGoal goal() {
        return runtime.componentGoal(id);
    }

    @Override
    public long configRevision() {
        return runtime.componentConfigRevision(id);
    }

    @Override
    public CompletionStage<ComponentState> whenSettled() {
        return runtime.whenSettled(id);
    }

    @Override
    public ComponentHandle<C> requireActive() {
        return runtime.requireActive(this, null);
    }

    @Override
    public ComponentHandle<C> requireActive(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        return runtime.requireActive(this, timeout);
    }

    @Override
    public CompletionStage<ComponentState> reconfigureAsync(C config) {
        return runtime.reconfigure(this, config);
    }

    @Override
    public CompletionStage<ComponentState> retryAsync() {
        return runtime.retry(this);
    }

    @Override
    public CompletionStage<ComponentState> disposeAsync() {
        return runtime.dispose(this);
    }

    CompletionStage<ComponentState> rejected(String message) {
        return CompletableFuture.failedFuture(new IllegalStateException(message));
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof ComponentHandleImpl<?> handle
                && runtime == handle.runtime && id.equals(handle.id);
    }

    @Override
    public int hashCode() {
        return 31 * System.identityHashCode(runtime) + id.hashCode();
    }

    @Override
    public String toString() {
        return "ComponentHandle[" + id + "]";
    }

    /** 只保存字符串的稳定逻辑身份，不引用 Class、ClassLoader 或组件实例。 */
    record Identity(
            String mountId,
            String componentId,
            String factoryId,
            String contextId) {

        Identity {
            Objects.requireNonNull(mountId, "mountId");
            Objects.requireNonNull(componentId, "componentId");
            Objects.requireNonNull(factoryId, "factoryId");
            Objects.requireNonNull(contextId, "contextId");
        }
    }
}
