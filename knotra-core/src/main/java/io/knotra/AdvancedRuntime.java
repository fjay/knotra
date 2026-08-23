package io.knotra;

import java.util.function.Function;

/**
 * Knotra 高级结构与事务操作接口。
 *
 * <p>提供多操作原子事务（{@link #transact}）、代际快照读取（{@link #snapshot}）、
 * 能力发布槽位（{@link #publication}）与子上下文树构建能力。</p>
 */
public interface AdvancedRuntime {

    /** 获取当前运行时代际的不可变全局快照（包含所有上下文、挂载与能力注册）。 */
    RuntimeSnapshot snapshot();

    /**
     * 获取当前排空过程中的挂起操作 point-in-time 快照。
     *
     * <p>任意运行时生命周期阶段（ACTIVE、CLOSING、CLOSED）均可调用。该方法不获取
     * 内核协调器锁、不等待任何 Future，也不执行用户代码；调用它不会改变 close 的
     * 收敛语义，也不会触发失败组件或清理条目的重试。各操作来自对应叶子状态的短锁采样，
     * 因此不承诺全局原子性。</p>
     */
    PendingOperationsSnapshot pendingOperations();

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

    /** 在指定父上下文下创建命名的子上下文节点。 */
    ContextHandle childContext(ContextHandle parent, String name);
}
