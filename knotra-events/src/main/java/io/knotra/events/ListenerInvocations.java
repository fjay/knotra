package io.knotra.events;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

/**
 * 监听调用脚手架。这里统一完成监听结果 stage 的空值校验、ClassLoader 上下文包裹、
 * 异步失败归一和同步抛错兜底；各分发模式只保留自己的调用签名和结果解释。
 */
final class ListenerInvocations {
    private ListenerInvocations() {
    }

    static <T> CompletableFuture<ListenerOutcome<T>> parallel(
            RegisteredSubscription subscription,
            T event,
            Executor executor) {
        try {
            return CompletableFuture
                    .supplyAsync(() -> invoke(
                            subscription,
                            () -> {
                                ParallelEventListener<? super T> listener =
                                        subscription.parallelListener();
                                return listener.listen(event);
                            },
                            ignored -> ListenerOutcome.<T>success()),
                            executor)
                    .thenCompose(stage -> stage)
                    .exceptionally(error -> ListenerOutcome.failure(
                            failure(subscription, asException(error))));
        } catch (Throwable error) {
            return CompletableFuture.completedFuture(
                    ListenerOutcome.failure(failure(subscription, asException(error))));
        }
    }

    static EventFailure invokeSync(
            RegisteredSubscription subscription,
            SyncCall call) {
        try {
            withListenerContext(subscription, call);
            return null;
        } catch (Throwable error) {
            return failure(subscription, error);
        }
    }

    static <T, R> CompletionStage<ListenerOutcome<T>> invoke(
            RegisteredSubscription subscription,
            ListenerCall<R> call,
            ListenerResult<R, T> success) {
        try {
            CompletionStage<R> stage = withListenerContext(subscription, call);
            Objects.requireNonNull(stage, "listener returned a null completion stage");
            return stage.thenApply(success::apply)
                    .exceptionally(error -> ListenerOutcome.<T>failure(
                            failure(subscription, asException(error))));
        } catch (Throwable error) {
            return CompletableFuture.completedFuture(
                    ListenerOutcome.failure(failure(subscription, error)));
        }
    }

    static EventFailure failure(RegisteredSubscription subscription, Throwable error) {
        return new EventFailure(
                subscription.subscriptionId(),
                subscription.eventName(),
                subscription.eventTypeName(),
                subscription.mode(),
                EventFailureText.describe(error));
    }

    static Exception asException(Throwable error) {
        Throwable cause = error;
        // 层层组合 stage 会引入 Completion/Execution 包装，诊断和异常完成都应面向原始原因。
        while ((cause instanceof java.util.concurrent.CompletionException
                || cause instanceof java.util.concurrent.ExecutionException)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause instanceof Exception exception ? exception : new IllegalStateException(cause);
    }

    private static void withListenerContext(
            RegisteredSubscription subscription,
            SyncCall call) throws Throwable {
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(subscription.listenerClassLoader());
        try {
            call.invoke();
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    private static <R> CompletionStage<R> withListenerContext(
            RegisteredSubscription subscription,
            ListenerCall<R> call) throws Throwable {
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        // 回调期间切换到监听实现的 ClassLoader，finally 恢复调用线程原状态，避免污染执行器线程。
        Thread.currentThread().setContextClassLoader(subscription.listenerClassLoader());
        try {
            return call.invoke();
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    interface SyncCall {
        void invoke() throws Throwable;
    }

    interface ListenerCall<R> {
        CompletionStage<R> invoke() throws Throwable;
    }

    interface ListenerResult<R, T> {
        ListenerOutcome<T> apply(R value);
    }
}
