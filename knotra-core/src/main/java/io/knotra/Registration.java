package io.knotra;

/** A typed host registration for one committed generation. Replacing it produces a new Registration. */
public interface Registration<T> extends RegistrationHandle, Settlement {
    CapabilityKey<T> key();

    ContextHandle context();

    Registration<T> replace(T value);

    Settlement revoke();
}
