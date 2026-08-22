package io.knotra;

import java.util.concurrent.CompletionStage;

/**
 * Activation 拥有的动态 Capability 调用入口。
 *
 * <p>call 会在单个已提交 provider 上固定执行多方法操作；proxy 的每个方法单独持有租约。
 * available 只是 advisory 结果，真正调用仍在协调器内原子获取 consumer 与 provider 租约。</p>
 */
public interface DynamicCapability<T> {

    boolean available();

    <R> R call(DynamicOperation<? super T, ? extends R> operation);

    <R> CompletionStage<R> callAsync(AsyncDynamicOperation<? super T, ? extends R> operation);

    <P extends T> P proxy(Class<P> interfaceType);

    T proxy();
}
