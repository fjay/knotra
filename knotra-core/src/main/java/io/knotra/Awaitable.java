package io.knotra;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

import io.knotra.internal.AwaitSupport;

/** 支持异步观察与有界同步阻塞等待的结算结果契约。 */
public interface Awaitable<T> {

    /** 观察结算完成的异步 CompletionStage。 */
    CompletionStage<T> whenSettled();

    /** 同步等待结算完成（无超时上限）。 */
    default T awaitSettled() {
        return awaitResult(null);
    }

    /** 有界同步等待结算完成（超时将抛出 SettlementAwaitException）。 */
    default T awaitSettled(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        return awaitResult(timeout);
    }

    private T awaitResult(Duration timeout) {
        if (timeout != null && (timeout.isZero() || timeout.isNegative())) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        AwaitSupport.Outcome<T> outcome = AwaitSupport.await(whenSettled(), timeout);
        if (outcome.interrupted()) {
            throw new SettlementAwaitException(
                    SettlementAwaitException.Reason.INTERRUPTED,
                    "settlement wait was interrupted");
        }
        if (outcome.timedOut()) {
            throw new SettlementAwaitException(
                    SettlementAwaitException.Reason.TIMEOUT,
                    "settlement wait timed out after " + timeout);
        }
        if (outcome.failure() != null) {
            throw new SettlementAwaitException(
                    SettlementAwaitException.Reason.FAILED,
                    "settlement failed: " + AwaitSupport.stableError(outcome.failure()));
        }
        return outcome.result();
    }
}
