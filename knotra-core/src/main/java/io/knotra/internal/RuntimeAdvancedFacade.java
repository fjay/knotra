package io.knotra.internal;

import io.knotra.AdvancedRuntime;
import io.knotra.CapabilityKey;
import io.knotra.ContextHandle;
import io.knotra.PublicationChange;
import io.knotra.RuntimeSnapshot;
import io.knotra.RuntimeTransaction;
import io.knotra.TransactionReceipt;

import java.util.function.Function;

/** AdvancedRuntime 的包内门面，只委托默认运行时实现。 */
final class RuntimeAdvancedFacade implements AdvancedRuntime {
    private final DefaultKnotraRuntime runtime;

    RuntimeAdvancedFacade(DefaultKnotraRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public RuntimeSnapshot snapshot() {
        return runtime.snapshot();
    }

    @Override
    public <R> TransactionReceipt<R> transact(
            Function<RuntimeTransaction, R> transaction) {
        return runtime.transact(transaction);
    }

    @Override
    public <T> PublicationChange<T> publication(
            ContextHandle context,
            CapabilityKey<T> key,
            T value) {
        return PublicationImpl.publish(runtime, context, key, value);
    }

    @Override
    public ContextHandle childContext(ContextHandle parent, String name) {
        return runtime.transact(transaction -> transaction.childContext(parent, name))
                .value();
    }
}
