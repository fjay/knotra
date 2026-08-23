package io.knotra;

import java.util.Optional;
import java.util.function.Function;
import java.util.concurrent.CompletionStage;

/**
 * Knotra 运行时核心门面（Simple API）。
 *
 * <p>提供日常业务最常用的能力发布（Publication）、能力查找（require / find）、根上下文组件挂载（Mount）与优雅停机入口。
 * 高级事务、代际快照、子上下文管理与显式注销等底层能力请通过 {@link #advanced()} 访问。</p>
 */
public interface KnotraRuntime extends AutoCloseable {

    /** 获取运行时的唯一实例标识。 */
    String runtimeId();

    /** 获取运行时的根上下文（Root Context）句柄。 */
    ContextHandle root();

    /** 访问高级运行时接口，用于结构化事务、精确注销与快照查询。 */
    AdvancedRuntime advanced();

    /** 在根上下文中获取必须存在的能力实例。 */
    default <T> T require(CapabilityKey<T> key) {
        return root().view().require(key);
    }

    /** 基于类型在根上下文中获取必须存在的能力实例。 */
    default <T> T require(Class<T> type) {
        return root().view().require(type);
    }

    /** 在根上下文中查询可选能力实例。 */
    default <T> Optional<T> find(CapabilityKey<T> key) {
        return root().view().find(key);
    }

    /** 基于类型在根上下文中查询可选能力实例。 */
    default <T> Optional<T> find(Class<T> type) {
        return root().view().find(type);
    }

    /** 在根上下文中发布指定键的能力，返回本次操作的变更对象与结算观察器。 */
    default <T> PublicationChange<T> publish(CapabilityKey<T> key, T value) {

        return publish(root(), key, value);
    }

    /**
     * 在指定上下文中发布能力。同一 {@code (context, key)} 坐标命中活跃槽位时是
     * get-or-update 语义：原子更新该槽位并返回指向同一逻辑槽位的句柄（句柄对象
     * identity 不保证相同）；坐标无活跃槽位时创建新槽位。终态槽位不复活，同坐标
     * 重新 publish 会创建全新槽位。
     */
    default <T> PublicationChange<T> publish(ContextHandle context, CapabilityKey<T> key, T value) {
        return advanced().publication(context, key, value);
    }

    /** 基于类型在根上下文中便捷发布能力。 */
    default <T> PublicationChange<T> publish(Class<T> type, T value) {
        return publish(CapabilityKey.of(type), value);
    }

    /** 基于类型在指定上下文中便捷发布能力。 */
    default <T> PublicationChange<T> publish(ContextHandle context, Class<T> type, T value) {
        return publish(context, CapabilityKey.of(type), value);
    }

    /** 在根上下文中挂载无配置组件。 */
    default MountHandle mount(
            String mountId,
            MountFactory factory) {
        return transactForHandle(tx -> tx.mount(root(), mountId, factory));
    }

    /** 在根上下文中带挂载选项挂载无配置组件。 */
    default MountHandle mount(
            String mountId,
            MountFactory factory,
            MountOptions options) {
        return transactForHandle(tx -> tx.mount(root(), mountId, factory, options));
    }

    /** 在根上下文中挂载带配置的类型化组件。 */
    default <C> ConfiguredMountHandle<C> mount(
            String mountId,
            ComponentFactory<C> factory,
            C config) {
        return mount(mountId, factory, config, MountOptions.DEFAULT);
    }

    /** 在根上下文中带挂载选项挂载带配置的类型化组件。 */
    default <C> ConfiguredMountHandle<C> mount(
            String mountId,
            ComponentFactory<C> factory,
            C config,
            MountOptions options) {
        return transactForHandle(tx -> tx.mount(root(), mountId, factory, config, options));
    }

    /** 异步关闭运行时并释放所有上下文与组件资源。 */
    CompletionStage<Void> closeAsync();

    /** 使用指定配置创建 Knotra 运行时实例。 */
    static KnotraRuntime create(KnotraConfig config) {
        return io.knotra.internal.RuntimeBootstrap.create(config);
    }

    /** 使用默认配置创建 Knotra 运行时实例。 */
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
