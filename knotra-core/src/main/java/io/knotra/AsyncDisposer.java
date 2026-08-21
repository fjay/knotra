package io.knotra;

import java.util.concurrent.CompletionStage;

/**
 * 异步释放器契约，由 {@link LifecycleScope#manageAsync(String, AsyncDisposer)} 登记。
 *
 * <p>返回的 stage 正常完成表示资源释放已收敛；异常完成表示清理失败，失败条目保持可重试。
 * 实现应只等待关闭请求之前已接受的工作，关闭之后的新工作应被拒绝。
 */
@FunctionalInterface
public interface AsyncDisposer {
    CompletionStage<Void> dispose();
}
