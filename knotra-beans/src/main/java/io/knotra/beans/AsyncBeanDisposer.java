package io.knotra.beans;

import java.util.concurrent.CompletionStage;

/** 自定义异步清理动作；正常完成表示清理收敛，异常完成表示条目可重试。 */
@FunctionalInterface
public interface AsyncBeanDisposer<T> {
    CompletionStage<Void> disposeAsync(T bean) throws Exception;
}
