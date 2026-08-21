package io.knotra;

import java.util.concurrent.CompletionStage;

/**
 * 组件的稳定逻辑挂载点句柄。
 *
 * <p>句柄拥有挂载 ID（contextId + mountId），在同一挂载的多次重新激活之间保持不变；
 * 每次启动都会创建新的 Activation。句柄具有标识语义：同一 Runtime 内 handleId 唯一，
 * 实现按运行时实例与 ID 判等，与对象相等性无关。状态查询读取的是瞬时视图，
 * 状态迁移异步收敛，应通过 {@link #whenSettled()} 等待。
 *
 * <p>操作语义：
 * <ul>
 *   <li>{@link #reconfigure} 通过短事务更新期望配置，产生新的配置代际并触发重新激活；</li>
 *   <li>{@link #retry} 仅对 FAILED 组件有效，用于重试启动或失败的清理；</li>
 *   <li>{@link #dispose} 逻辑释放组件及其拥有的子挂载与注册，释放后句柄不可再用于结构性操作。</li>
 * </ul>
 */
public interface ComponentHandle<C> extends AutoCloseable {
    String handleId();

    String mountId();

    String componentId();

    String factoryId();

    String contextId();

    ComponentState state();

    ComponentGoal goal();

    long configRevision();

    /**
     * 等待组件离开当前过渡态（STARTING / STOPPING）并完成结算。
     *
     * <p>返回的是结算后的状态，而非瞬时状态；组件可能随后因新的结构变化再次进入过渡态。
     */
    CompletionStage<ComponentState> whenSettled();

    /**
     * 请求新的期望配置。
     *
     * <p>配置经工厂 schema 归一化后进入短事务；与当前期望等价的配置不产生新代际。
     * 提交后当前激活被标记过期并基于新配置重新激活。事务被拒绝时返回的 stage 异常完成。
     *
     * @return 结算后的组件状态
     */
    CompletionStage<ComponentState> reconfigure(C config);

    /**
     * 重试失败的组件。仅当组件处于 FAILED 时有效，否则返回的 stage 异常完成；
     * 启动失败会重新尝试激活，清理失败会重新尝试释放。
     */
    CompletionStage<ComponentState> retry();

    /**
     * 逻辑释放组件：目标置为 DISPOSED，停止当前激活，并递归释放其拥有的子挂载与注册。
     * 清理失败时组件停留在 FAILED 且可通过 {@link #retry()} 重试。幂等：对已释放组件
     * 再次调用直接返回 DISPOSED。
     */
    CompletionStage<ComponentState> dispose();

    /** 阻塞等待 {@link #dispose()} 完成；清理失败时抛出异常。 */
    @Override
    default void close() throws Exception {
        dispose().toCompletableFuture().get();
    }
}
