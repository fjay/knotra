package io.knotra;

import java.util.concurrent.CompletionStage;

/** Context 树节点的稳定结构句柄。 */
public interface ContextHandle extends AutoCloseable {
    String contextId();

    ContextInfo info();

    ContextView view();

    ContextState state();

    /** 释放该 Context 子树并返回最终状态；清理失败正常结算为 FAILED。 */
    CompletionStage<ContextState> disposeAsync();

    @Override
    default void close() {
        ContextState settled = disposeAsync().toCompletableFuture().join();
        if (settled != ContextState.DISPOSED) {
            throw new IllegalStateException(
                    "context cleanup did not converge: " + contextId() + " is " + settled);
        }
    }
}
