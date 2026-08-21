package io.knotra;

import java.util.concurrent.CompletionStage;

/**
 * Context 树节点的稳定句柄。
 *
 * <p>Context 定义 Capability 可见性：每个 Context 能看到发布在自身及其祖先中的 Capability，
 * 子 Context 的注册可遮蔽父 Context，直到子注册被撤销。句柄具有标识语义，在其 Context
 * 存续期间有效；根 Context 的释放由 Runtime 关闭负责。
 */
public interface ContextHandle extends AutoCloseable {
    String contextId();
    ContextInfo contextInfo();
    RuntimeContext context();

    ContextState state();

    /**
     * 请求释放该 Context 及其整个子树：撤销宿主注册、逻辑释放子树内组件，
     * 等待所有清理收敛后移除子树。幂等；清理失败时返回的 stage 异常完成，
     * Context 状态停留在 FAILED，可修复后重试。
     */
    CompletionStage<Void> disposeAsync();

    /** 阻塞等待 {@link #disposeAsync()} 完成；清理失败时抛出异常。 */
    @Override
    default void close() throws Exception {
        disposeAsync().toCompletableFuture().get();
    }
}
