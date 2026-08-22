package io.knotra;

import java.util.function.Function;

/** Advanced structural operations that sit above the simple runtime facade. */
public interface AdvancedRuntime {
    RuntimeSnapshot snapshot();

    <R> TransactionReceipt<R> transact(Function<RuntimeTransaction, R> transaction);

    <T> PublicationChange<T> publication(ContextHandle context, CapabilityKey<T> key, T value);

    default <T> PublicationChange<T> publication(ContextHandle context, Class<T> type, T value) {
        return publication(context, CapabilityKey.of(type), value);
    }

    <T> Registration<T> register(CapabilityKey<T> key, T value);
    <T> Registration<T> register(ContextHandle context, CapabilityKey<T> key, T value);

    default <T> Registration<T> register(Class<T> type, T value) {
        return register(CapabilityKey.of(type), value);
    }

    default <T> Registration<T> register(ContextHandle context, Class<T> type, T value) {
        return register(context, CapabilityKey.of(type), value);
    }

    Settlement revoke(RegistrationHandle registration);

    ContextHandle childContext(ContextHandle parent, String name);
}
