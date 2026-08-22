package io.knotra;

/**
 * A typed token for a registration staged inside a host transaction.
 *
 * <p>It is not a committed {@link Registration}: it exposes no replacement or settlement. After a
 * successful commit it remains a valid opaque {@link RegistrationHandle} for revoke, but it never
 * upgrades to a typed registration. A failed transaction invalidates the token.</p>
 */
public interface StagedRegistration<T> extends RegistrationHandle {
    CapabilityKey<T> key();

    ContextHandle context();
}
