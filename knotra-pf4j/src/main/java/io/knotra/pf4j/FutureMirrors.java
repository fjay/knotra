package io.knotra.pf4j;

import java.util.concurrent.CompletableFuture;

/**
 * 把内部驱动 future 的终态投影为调用方可见的观察 future。
 *
 * <p>观察 future 与驱动 future 完全分离：调用方 {@code cancel} 只完成自己的观察
 * future（随后 {@code join} 得到 {@link java.util.concurrent.CancellationException}），
 * 不会取消内部驱动、{@code ManagedArtifactStore.drainFuture}、PendingTracker 记录
 * 或 close 尝试，也不影响其他观察者。内部 future 以原始异常完成时，观察 future
 * 以同一异常完成，{@code join} 的异常形状与直接持有内部 future 时一致。</p>
 *
 * <p>回调只捕获观察 future 本身，注册在内部 future 上；内部完成后
 * CompletableFuture 会弹出依赖栈，镜像链路不保留 adapter、插件或 ClassLoader
 * 引用。</p>
 */
final class FutureMirrors {

    private FutureMirrors() {
    }

    static <T> CompletableFuture<T> mirror(CompletableFuture<T> internal) {
        CompletableFuture<T> observer = new CompletableFuture<>();
        internal.whenComplete((value, error) -> {
            if (error == null) {
                observer.complete(value);
            } else {
                observer.completeExceptionally(error);
            }
        });
        return observer;
    }
}
