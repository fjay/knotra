package io.knotra.internal;

import io.knotra.CapabilityKey;
import io.knotra.ContextHandle;
import io.knotra.DiagnosticCode;
import io.knotra.Registration;
import io.knotra.RegistrationHandle;
import io.knotra.RuntimeDiagnostic;
import io.knotra.Settlement;
import io.knotra.TransactionRejectedException;

import java.util.List;
import java.util.Objects;

/** 单个已提交代际的类型化宿主注册。 */
final class RegistrationImpl<T> implements Registration<T> {
    private final RegistrationHandleImpl registration;
    private final CapabilityKey<T> key;
    private final ContextHandle context;
    private final Settlement settlement;
    private volatile boolean stale;

    RegistrationImpl(
            RegistrationHandle registration,
            CapabilityKey<T> key,
            ContextHandle context,
            Settlement settlement) {
        if (!(registration instanceof RegistrationHandleImpl handle)) {
            throw new IllegalArgumentException("registration handle does not belong to this runtime");
        }
        this.registration = handle;
        this.key = Objects.requireNonNull(key, "key");
        this.context = Objects.requireNonNull(context, "context");
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
    public ContextHandle context() {
        return context;
    }

    @Override
    public long generation() {
        return settlement.generation();
    }

    @Override
    public java.util.concurrent.CompletionStage<io.knotra.SettlementReport> whenSettled() {
        return settlement.whenSettled();
    }

    @Override
    public Registration<T> replace(T value) {
        return runtime().replace(this, value);
    }

    @Override
    public Settlement revoke() {
        return runtime().revoke(this);
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
                || other instanceof RegistrationImpl<?> handle
                && registration.runtime == handle.registration.runtime
                && registration.registrationId().equals(handle.registration.registrationId());
    }

    @Override
    public int hashCode() {
        return registration.hashCode();
    }

    @Override
    public String toString() {
        return "Registration[" + key.name() + ", " + registration.registrationId() + "]";
    }
}
