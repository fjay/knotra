package io.knotra;

import java.util.concurrent.CompletionStage;

/** 组件的稳定逻辑挂载点句柄。 */
public interface ComponentHandle<C> extends AutoCloseable {
    String handleId();

    String mountId();

    String componentId();

    String factoryId();

    String contextId();

    ComponentState state();

    ComponentGoal goal();

    long configRevision();

    /** 等待组件离开当前 STARTING/STOPPING 过渡并返回结算状态。 */
    CompletionStage<ComponentState> whenSettled();

    /** 请求类型化配置变更；事务拒绝时 stage 异常完成。 */
    CompletionStage<ComponentState> reconfigureAsync(C config);

    /** 重试 FAILED 组件的启动或未完成清理。 */
    CompletionStage<ComponentState> retryAsync();

    /** 逻辑释放组件及其拥有的子挂载与注册。 */
    CompletionStage<ComponentState> disposeAsync();

    /**
     * 阻塞释放组件。清理未收敛到 DISPOSED 时抛出异常，FAILED 状态仍保留供 retryAsync 使用。
     */
    @Override
    default void close() {
        ComponentState settled = disposeAsync().toCompletableFuture().join();
        if (settled != ComponentState.DISPOSED) {
            throw new IllegalStateException(
                    "component cleanup did not converge: " + handleId() + " is " + settled);
        }
    }
}
