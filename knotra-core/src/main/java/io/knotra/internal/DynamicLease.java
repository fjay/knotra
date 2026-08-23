package io.knotra.internal;

/** 动态调用同时持有的 provider lease 与 consumer 调用准入。 */
record DynamicLease<T>(
        T provider,
        ProviderLeaseRuntime providerLease,
        DynamicCallGate consumerGate)
        implements AutoCloseable {

    @Override
    public void close() {
        providerLease.release();
        consumerGate.release();
    }
}
