package io.knotra.internal;

import java.util.concurrent.RejectedExecutionException;
/** Internal marker for lifecycle work that could not be submitted to the runtime executor. */
final class TransitionRejectedStateException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    TransitionRejectedStateException(RejectedExecutionException cause) {
        super("runtime transition executor rejected the task", cause);
    }
}
