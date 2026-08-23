package io.knotra.internal;

/** Internal marker for an unpublished transition reservation that was rolled back. */
final class TransitionCancelledStateException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    TransitionCancelledStateException() {
        super("transition was cancelled before its view was published");
    }
}
