package io.knotra;

import java.util.function.Function;
import java.util.concurrent.CompletionStage;

/** Knotra 运行时入口。 */
public interface KnotraRuntime extends AutoCloseable {
    String runtimeId();

    /** 返回根 Context 的稳定结构句柄。 */
    ContextHandle root();

    /** 返回运行时的不可变观测快照。 */
    RuntimeSnapshot snapshot();

    /**
     * 原子执行结构事务。拒绝时抛出 TransactionRejectedException，绝不返回可忽略的失败值。
     */
    <R> TransactionReceipt<R> transact(Function<RuntimeTransaction, R> transaction);

    /** 在根 Context 发布单个宿主 Capability。多操作原子替换应使用 transact。 */
    default <T> RegistrationHandle provide(CapabilityKey<T> key, T value) {
        return transact(tx -> tx.provide(root(), key, value)).value();
    }

    /** 撤销单个宿主注册。多操作原子替换应使用 transact。 */
    default void revoke(RegistrationHandle registration) {
        transact(tx -> {
            tx.revoke(registration);
            return null;
        });
    }

    /** 在根 Context 挂载配置型组件。 */
    default <C> ComponentHandle<C> mount(
            String mountId,
            ComponentFactory<C> factory,
            C config) {
        return transact(tx -> tx.mount(root(), mountId, factory, config)).value();
    }

    /** 在根 Context 挂载无配置组件。 */
    default ComponentHandle<NoConfig> mount(
            String mountId,
            ComponentFactory<NoConfig> factory) {
        return transact(tx -> tx.mount(root(), mountId, factory)).value();
    }

    /** 异步关闭运行时并等待根 Context 子树清理收敛。 */
    CompletionStage<Void> closeAsync();

    static KnotraRuntime create(KnotraConfig config) {
        return new io.knotra.internal.DefaultKnotraRuntime(config);
    }

    static KnotraRuntime create() {
        return create(KnotraConfig.defaults());
    }

    @Override
    default void close() {
        closeAsync().toCompletableFuture().join();
    }
}
