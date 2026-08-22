package io.knotra;

/** Stable unchecked exception used by blocking settlement waits. It never retains a cause. */
public final class SettlementAwaitException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public enum Reason {
        TIMEOUT,
        INTERRUPTED,
        FAILED
    }

    private final Reason reason;

    public SettlementAwaitException(Reason reason, String message) {
        super(message, null, false, false);
        this.reason = java.util.Objects.requireNonNull(reason, "reason");
    }

    public Reason reason() {
        return reason;
    }
}
