package io.knotra.events;

import java.util.concurrent.CompletableFuture;

/**
 * 公开观察 stage 的取消隔离边界。
 *
 * <p>内部驱动 future 永不直接返回给调用方：每次观察获得一个独立 mirror，调用方
 * {@code cancel} 只取消自己的观察，不传播到内部 future，也不影响同一内部工作的其他
 * 观察者。mirror 按原语义转发 source 的结果或异常，{@code join/get} 的解包行为与直接
 * 持有 source 完全一致。</p>
 *
 * <p>source 已完成时 {@code whenComplete} 同步触发且不注册常驻依赖节点；未完成时注册的
 * 节点在 source 完成后触发并出栈，mirror 与节点之后均可被正常回收，不会按调用次数泄漏。</p>
 */
final class CompletionMirrors {
    private CompletionMirrors() {}

    static <T> CompletableFuture<T> of(CompletableFuture<T> source) {
        CompletableFuture<T> mirror = new CompletableFuture<>();
        source.whenComplete((value, error) -> {
            if (error != null) {
                mirror.completeExceptionally(error);
            } else {
                mirror.complete(value);
            }
        });
        return mirror;
    }
}
