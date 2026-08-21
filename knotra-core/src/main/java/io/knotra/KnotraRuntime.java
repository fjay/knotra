package io.knotra;

import java.util.concurrent.CompletionStage;

/**
 * Knotra 运行时入口，拥有 Context 树、Capability 注册与组件挂载。
 *
 * <p>宿主不通过共享可变对象修改运行时：所有结构调整（发布、撤销、挂载、重配置、释放）
 * 都通过 {@link #mutate} 的短事务完成，事务被拒绝时不发布任何内容。读取通过
 * {@link RuntimeContext} 与 {@link RuntimeSnapshot} 完成，二者都不暴露存活的组件实例、
 * 资源、释放器、Throwable、Class 或 ClassLoader。
 *
 * <p>实现线程安全；{@link #closeAsync()} 幂等，等待整个 Context 树清理收敛后完成。
 */
public interface KnotraRuntime extends AutoCloseable {
    String runtimeId();

    ContextHandle rootContext();

    RuntimeContext context();

    /** 返回运行时的不可变快照，用于观察代际、结构、激活、注册与诊断。 */
    RuntimeSnapshot snapshot();

    /**
     * 在短事务中执行结构变更。
     *
     * <p>回调在协调器锁外执行，其中的操作被记录为意图；全部意图通过结构校验后才原子发布，
     * 生成新的视图代际。任何意图失败都会拒绝整个事务，不发布任何内容；回调抛出的异常
     * 同样导致拒绝，而不会传播给调用方。
     *
     * @param action 事务体，入参是只能记录意图的 {@link RuntimeMutation}
     * @return 提交结果；settlement 只等待本次事务直接触发的组件与 Context 过渡，
     *         不代表运行时全局静止
     */
    <R> MutationResult<R> mutate(java.util.function.Function<RuntimeMutation, R> action);

    /**
     * 异步关闭运行时：释放根 Context 子树并等待所有清理收敛。幂等，重复调用返回同一结果；
     * 清理失败时返回的 stage 异常完成。
     */
    CompletionStage<Void> closeAsync();

    /** 以指定配置创建运行时。 */
    static KnotraRuntime create(KnotraConfig config) {
        return new io.knotra.internal.DefaultKnotraRuntime(config);
    }

    /** 以默认配置创建运行时。 */
    static KnotraRuntime create() {
        return create(KnotraConfig.defaults());
    }

    /** 阻塞等待 {@link #closeAsync()} 完成；关闭失败时抛出异常。 */
    @Override
    default void close() throws Exception {
        closeAsync().toCompletableFuture().get();
    }
}
