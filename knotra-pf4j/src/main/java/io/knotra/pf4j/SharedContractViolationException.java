package io.knotra.pf4j;

/** Raised when an artifact violates the configured shared contract identity. */
public final class SharedContractViolationException extends RuntimeException {

    public SharedContractViolationException(String message) {
        super(message);
    }
}
