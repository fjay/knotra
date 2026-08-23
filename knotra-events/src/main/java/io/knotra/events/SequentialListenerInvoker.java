package io.knotra.events;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** 串行类分发模式的监听调用策略；分发链构造时一次性选定，之后逐监听复用。 */
enum SequentialListenerInvoker {
    SERIAL {
        @Override
        <T> CompletionStage<DispatchState<T>> invoke(
                RegisteredSubscription subscription,
                DispatchState<T> state) {
            SerialEventListener<? super T> listener = subscription.serialListener();
            return ListenerInvocations
                    .invoke(subscription, () -> listener.listen(state.event()),
                            continueDispatch -> new ListenerOutcome<>(continueDispatch, null, null))
                    .thenApply(outcome ->
                            state.afterListener(outcome.continueDispatch(), outcome.failure()));
        }
    },
    BAIL {
        @Override
        <T> CompletionStage<DispatchState<T>> invoke(
                RegisteredSubscription subscription,
                DispatchState<T> state) {
            BailEventListener<? super T> listener = subscription.bailListener();
            return ListenerInvocations
                    .invoke(subscription,
                            () -> CompletableFuture.completedFuture(listener.bail(state.event())),
                            claimed -> new ListenerOutcome<>(claimed, null, null))
                    .thenApply(outcome ->
                            // bail 的返回值是“是否认领”，状态机需要反转为“是否继续”，认领即停止。
                            state.afterListener(!outcome.continueDispatch(), outcome.failure()));
        }
    },
    WATERFALL {
        @Override
        <T> CompletionStage<DispatchState<T>> invoke(
                RegisteredSubscription subscription,
                DispatchState<T> state) {
            WaterfallEventListener<T> listener = subscription.waterfallListener();
            return ListenerInvocations
                    .invoke(subscription, () -> listener.transform(state.event()),
                            event -> new ListenerOutcome<>(true, event, null))
                    .thenApply(outcome ->
                            state.afterTransform(outcome.event(), outcome.failure()));
        }
    };

    abstract <T> CompletionStage<DispatchState<T>> invoke(
            RegisteredSubscription subscription,
            DispatchState<T> state);

    static SequentialListenerInvoker forMode(EventMode mode) {
        return switch (mode) {
            case SERIAL -> SERIAL;
            case BAIL -> BAIL;
            case WATERFALL -> WATERFALL;
            default -> throw new IllegalArgumentException(
                    "unsupported sequential mode: " + mode);
        };
    }
}
