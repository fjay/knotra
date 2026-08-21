package io.knotra;

import java.util.concurrent.CompletionStage;

/**
 * 由 {@link LifecycleScope#onCloseAsync(String, AsyncDisposer)} 登记的异步清理动作。
 * 正常完成表示清理收敛；异常完成表示该条目保持可重试。
 */
@FunctionalInterface
public interface AsyncDisposer {
    CompletionStage<Void> disposeAsync();
}
