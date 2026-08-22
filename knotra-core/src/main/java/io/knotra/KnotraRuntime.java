package io.knotra;

import java.util.function.Function;
import java.util.concurrent.CompletionStage;

/** Simple Knotra runtime facade for publication, root mounting, and shutdown. */
public interface KnotraRuntime extends AutoCloseable {
    String runtimeId();

    ContextHandle root();

    AdvancedRuntime advanced();

    default <T> PublicationChange<T> publish(CapabilityKey<T> key, T value) {
        return publish(root(), key, value);
    }

    default <T> PublicationChange<T> publish(ContextHandle context, CapabilityKey<T> key, T value) {
        return advanced().publication(context, key, value);
    }

    default <T> PublicationChange<T> publish(Class<T> type, T value) {
        return publish(CapabilityKey.of(type), value);
    }

    default <T> PublicationChange<T> publish(ContextHandle context, Class<T> type, T value) {
        return publish(context, CapabilityKey.of(type), value);
    }

    default MountHandle mount(
            String mountId,
            MountFactory factory) {
        return advanced().transact(tx -> tx.mount(root(), mountId, factory)).value();
    }

    default MountHandle mount(
            String mountId,
            MountFactory factory,
            MountOptions options) {
        return advanced().transact(tx -> tx.mount(root(), mountId, factory, options)).value();
    }

    default <C> ConfiguredMountHandle<C> mount(
            String mountId,
            ComponentFactory<C> factory,
            C config) {
        return mount(mountId, factory, config, null);
    }

    default <C> ConfiguredMountHandle<C> mount(
            String mountId,
            ComponentFactory<C> factory,
            C config,
            MountOptions options) {
        return transactForHandle(tx -> tx.mount(root(), mountId, factory, config, options));
    }

    CompletionStage<Void> closeAsync();

    static KnotraRuntime create(KnotraConfig config) {
        return io.knotra.internal.RuntimeBootstrap.create(config);
    }

    static KnotraRuntime create() {
        return create(KnotraConfig.defaults());
    }

    @Override
    default void close() {
        closeAsync().toCompletableFuture().join();
    }

    private <H extends MountHandle> H transactForHandle(Function<RuntimeTransaction, H> mount) {
        return advanced().transact(mount::apply).value();
    }
}
