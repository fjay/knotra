package io.knotra.internal;

import io.knotra.CapabilityKey;
import io.knotra.ContextHandle;

/** Typed pre-commit token returned inside a host transaction. */
final class StagedRegistrationImpl<T> implements io.knotra.StagedRegistration<T> {
    private final RegistrationHandleImpl registration;
    private final CapabilityKey<T> key;
    private final ContextHandle context;

    StagedRegistrationImpl(
            RegistrationHandleImpl registration,
            CapabilityKey<T> key,
            ContextHandle context) {
        this.registration = java.util.Objects.requireNonNull(registration, "registration");
        this.key = java.util.Objects.requireNonNull(key, "key");
        this.context = java.util.Objects.requireNonNull(context, "context");
    }

    RegistrationHandleImpl registration() {
        return registration;
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
}
