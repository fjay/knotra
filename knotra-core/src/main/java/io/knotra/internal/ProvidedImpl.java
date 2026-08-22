package io.knotra.internal;

import io.knotra.CapabilityKey;
import io.knotra.DiagnosticCode;
import io.knotra.Provided;
import io.knotra.RegistrationHandle;
import io.knotra.RuntimeDiagnostic;
import io.knotra.TransactionRejectedException;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/**
 * 宿主 Capability 注册的应用侧类型化句柄。
 *
 * <p>注册身份仍由 {@link RegistrationHandleImpl} 提供；本包装额外保留 Capability 合约和创建事务
 * settlement。替换或撤销提交成功后标记 stale，之后的变异调用立即复用结构事务拒绝语义。</p>
 */
final class ProvidedImpl<T> implements Provided<T> {
    private final RegistrationHandleImpl registration;
    private final CapabilityKey<T> key;
    private final CompletionStage<Void> settlement;
    private volatile boolean stale;

    ProvidedImpl(
            RegistrationHandle registration,
            CapabilityKey<T> key,
            CompletionStage<Void> settlement) {
        Objects.requireNonNull(registration, "registration");
        if (!(registration instanceof RegistrationHandleImpl handle)) {
            throw new IllegalArgumentException(
                    "registration handle does not belong to this runtime");
        }
        this.registration = handle;
        this.key = Objects.requireNonNull(key, "key");
        this.settlement = Objects.requireNonNull(settlement, "settlement");
    }

    RegistrationHandleImpl registration() {
        return registration;
    }

    DefaultKnotraRuntime runtime() {
        return registration.runtime;
    }

    CapabilityKey<T> capabilityKey() {
        return key;
    }

    @Override
    public String registrationId() {
        return registration.registrationId();
    }

    @Override
    public CapabilityKey<T> key() {
        return key;
    }

    @Override
    public CompletionStage<Void> whenSettled() {
        return settlement;
    }

    @Override
    public Provided<T> replace(T value) {
        return runtime().replace(this, value);
    }

    @Override
    public void revoke() {
        runtime().revoke(this);
    }

    void requireFresh(String operation) {
        if (!stale && registration.runtime.hasLiveRegistration(registration.registrationId())) {
            return;
        }
        throw new TransactionRejectedException(List.of(new RuntimeDiagnostic(
                DiagnosticCode.INVALID_LIFECYCLE_OPERATION,
                registration.registrationId(),
                "stale registration handle cannot " + operation
                        + "; replacement has a new registration identity")));
    }

    void markStale() {
        stale = true;
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || other instanceof ProvidedImpl<?> handle
                && registration.runtime == handle.registration.runtime
                && registration.registrationId().equals(handle.registration.registrationId());
    }

    @Override
    public int hashCode() {
        return registration.hashCode();
    }

    @Override
    public String toString() {
        return "Provided[" + key.name() + ", " + registration.registrationId() + "]";
    }
}
