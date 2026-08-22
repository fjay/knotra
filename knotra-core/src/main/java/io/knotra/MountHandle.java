package io.knotra;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Stable logical mount handle. Configuration changes are available only on ConfiguredMountHandle. */
public interface MountHandle extends AutoCloseable {
    String handleId();

    String mountId();

    String componentId();

    String factoryId();

    String contextId();

    ComponentState state();

    ComponentGoal goal();

    long configRevision();

    CompletionStage<ComponentState> whenSettled();

    default MountHandle requireActive() {
        return awaitActive(null);
    }

    default MountHandle requireActive(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        return awaitActive(timeout);
    }

    private MountHandle awaitActive(Duration timeout) {
        CompletableFuture<ComponentState> future = whenSettled().toCompletableFuture();
        ComponentState settled = null;
        boolean interrupted = false;
        boolean timedOut = false;
        Throwable settlementError = null;
        try {
            settled = timeout == null
                    ? future.get()
                    : future.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            interrupted = true;
        } catch (TimeoutException error) {
            timedOut = true;
        } catch (ExecutionException | CompletionException error) {
            settlementError = error;
        }
        boolean settledNormally = !interrupted && !timedOut && settlementError == null;
        if (settledNormally && settled == ComponentState.ACTIVE) {
            return this;
        }
        ComponentState observed = state();
        if (observed == ComponentState.ACTIVE) {
            return this;
        }
        ComponentState failureState = settledNormally ? settled : observed;
        RuntimeDiagnostic detail = failureDetail(interrupted, settlementError);
        throw new MountNotActiveException(
                failureState,
                handleId(),
                mountId(),
                componentId(),
                factoryId(),
                contextId(),
                timeout,
                detail == null ? List.of() : List.of(detail));
    }

    private RuntimeDiagnostic failureDetail(boolean interrupted, Throwable settlementError) {
        if (interrupted) {
            return new RuntimeDiagnostic(
                    DiagnosticCode.INVALID_LIFECYCLE_OPERATION,
                    handleId(),
                    "wait interrupted before settlement");
        }
        if (settlementError != null) {
            return new RuntimeDiagnostic(
                    DiagnosticCode.ROLLBACK_FAILED,
                    handleId(),
                    "settlement failed: " + stableError(settlementError));
        }
        return null;
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
            String text = message == null || message.isBlank() ? type : type + ": " + message;
            return text.length() <= 160 ? text : text.substring(0, 160);
        } catch (Throwable ignored) {
            return "<invalid description>";
        }
    }

    /** Retry a failed mount's activation or unfinished cleanup. */
    CompletionStage<ComponentState> retryAsync();

    /** Logically dispose this mount and everything it owns. */
    CompletionStage<ComponentState> disposeAsync();

    @Override
    default void close() {
        ComponentState settled = disposeAsync().toCompletableFuture().join();
        if (settled != ComponentState.DISPOSED) {
            throw new IllegalStateException(
                    "mount cleanup did not converge: " + handleId() + " is " + settled);
        }
    }
}
