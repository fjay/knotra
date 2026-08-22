package io.knotra;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 支持异步观察与有界同步阻塞等待的结算结果契约。
 *
 * @param <T> 结算产出的结果类型
 */
public interface Awaitable<T> {

    /** 观察结算完成的异步 CompletionStage。 */
    CompletionStage<T> whenSettled();

    /** 同步等待结算完成（无超时上限）。 */
    default T awaitSettled() {
        return awaitSettled(null);
    }

    /** 有界同步等待结算完成（超时将抛出 SettlementAwaitException）。 */
    default T awaitSettled(Duration timeout) {
        if (timeout != null) {
            Objects.requireNonNull(timeout, "timeout");
            if (timeout.isZero() || timeout.isNegative()) {
                throw new IllegalArgumentException("timeout must be positive");
            }
        }
        CompletableFuture<T> waiting = whenSettled().toCompletableFuture();
        T result = null;
        boolean interrupted = false;
        boolean timedOut = false;
        Throwable failure = null;
        try {
            result = timeout == null
                    ? waiting.get()
                    : waiting.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            interrupted = true;
        } catch (TimeoutException error) {
            timedOut = true;
        } catch (ExecutionException | CompletionException error) {
            failure = error;
        }
        if (interrupted) {
            throw new SettlementAwaitException(SettlementAwaitException.Reason.INTERRUPTED,
                    "settlement wait was interrupted");
        }
        if (timedOut) {
            throw new SettlementAwaitException(SettlementAwaitException.Reason.TIMEOUT,
                    "settlement wait timed out after " + timeout);
        }
        if (failure != null) {
            throw new SettlementAwaitException(SettlementAwaitException.Reason.FAILED,
                    "settlement failed: " + stableError(failure));
        }
        return result;
    }

    private static String stableError(Throwable error) {
        try {
            Throwable cause = error instanceof CompletionException || error instanceof ExecutionException
                    ? error.getCause()
                    : error;
            if (cause == null) {
                cause = error;
            }
            String type = cause.getClass().getName();
            String message = cause.getMessage();
            String text = message == null || message.isBlank()
                    ? type
                    : type + ": " + message;
            return text.length() <= 500 ? text : text.substring(0, 500);
        } catch (Throwable ignored) {
            return "<invalid settlement failure>";
        }
    }
}
