package io.knotra;

import java.util.function.Function;

/**
 * Knotra 高级结构与事务操作接口。
 *
 * <p>提供多操作原子事务（{@link #transact}）、代际快照读取（{@link #snapshot}）、
 * 显式代际注册（{@link Registration}）与子上下文（Child Context）树构建能力。</p>
 */
public interface AdvancedRuntime {

    /** 获取当前运行时代际的不可变全局快照（包含所有上下文、挂载与能力注册）。 */
    RuntimeSnapshot snapshot();

    /**
     * 在单个原子事务中执行一组结构变更（挂载、提供、注销等）。
     *
     * @param transaction 包含变更意图的事务闭包
     * @param <R> 事务返回值类型
     * @return 包含返回值与本次事务异步结算（Settlement）的凭证
     */
    <R> TransactionReceipt<R> transact(Function<RuntimeTransaction, R> transaction);

    /** 在指定上下文中获取或创建能力发布槽位并提交初始值。 */
    <T> PublicationChange<T> publication(ContextHandle context, CapabilityKey<T> key, T value);

    /** 基于类型在指定上下文中获取或创建能力发布槽位。 */
    default <T> PublicationChange<T> publication(ContextHandle context, Class<T> type, T value) {
        return publication(context, CapabilityKey.of(type), value);
    }

    /** 在根上下文中创建单代能力注册。 */
    <T> Registration<T> register(CapabilityKey<T> key, T value);

    /** 在指定上下文中创建单代能力注册。 */
    <T> Registration<T> register(ContextHandle context, CapabilityKey<T> key, T value);

    /** 基于类型在根上下文中创建单代能力注册。 */
    default <T> Registration<T> register(Class<T> type, T value) {
        return register(CapabilityKey.of(type), value);
    }

    /** 基于类型在指定上下文中创建单代能力注册。 */
    default <T> Registration<T> register(ContextHandle context, Class<T> type, T value) {
        return register(context, CapabilityKey.of(type), value);
    }

    /** 显式撤销指定注册代际，并触发依赖方级联更新与排空结算。 */
    Settlement revoke(RegistrationHandle registration);

    /** 在指定父上下文下创建命名的子上下文节点。 */
    ContextHandle childContext(ContextHandle parent, String name);
}
