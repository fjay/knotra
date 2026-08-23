package io.knotra.internal;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** 同步等待 CompletionStage 的共享实现，统一中断、超时、异常与错误文本处理。 */
public final class AwaitSupport {
    /** 诊断错误文本的统一截断上限，避免异常消息拖垮日志或快照。 */
    private static final int MAX_ERROR_LENGTH = 500;

    private AwaitSupport() {
    }

    /** 同步等待结果；{@code timeout == null} 表示无界等待。 */
    public static <T> Outcome<T> await(CompletionStage<T> stage, Duration timeout) {
        Objects.requireNonNull(stage, "stage");
        if (timeout != null && (timeout.isZero() || timeout.isNegative())) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        CompletableFuture<T> future = stage.toCompletableFuture();
        T result = null;
        boolean interrupted = false;
        boolean timedOut = false;
        Throwable failure = null;
        try {
            result = timeout == null
                    ? future.get()
                    : future.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            interrupted = true;
        } catch (TimeoutException error) {
            timedOut = true;
        } catch (ExecutionException | CompletionException error) {
            failure = error;
        }
        return new Outcome<>(result, interrupted, timedOut, failure);
    }

    /** 归一化 CompletionException/ExecutionException 并截断稳定错误文本。 */
    public static String stableError(Throwable error) {
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
            return text.length() <= MAX_ERROR_LENGTH
                    ? text
                    : text.substring(0, MAX_ERROR_LENGTH);
        } catch (Throwable ignored) {
            return "<invalid settlement failure>";
        }
    }

    /** 单次等待的终态快照，调用方按自身契约转换异常。 */
    public record Outcome<T>(
            T result,
            boolean interrupted,
            boolean timedOut,
            Throwable failure) {

        public boolean settledNormally() {
            return !interrupted && !timedOut && failure == null;
        }
    }
}
