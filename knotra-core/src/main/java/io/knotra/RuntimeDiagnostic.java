package io.knotra;

import java.util.Objects;

/** Structured runtime diagnostic with stable text and optional bounded failure detail. */
public record RuntimeDiagnostic(
        DiagnosticCode code,
        String targetId,
        String message,
        FailureInfo failure) implements Comparable<RuntimeDiagnostic> {

    public RuntimeDiagnostic {
        if (code == null || targetId == null || message == null || failure == null) {
            throw new IllegalArgumentException("diagnostic fields must not be null");
        }
    }

    public RuntimeDiagnostic(DiagnosticCode code, String targetId, String message) {
        this(code, targetId, message, FailureInfo.EMPTY);
    }

    @Override
    public int compareTo(RuntimeDiagnostic other) {
        int byCode = code().name().compareTo(other.code().name());
        if (byCode != 0) {
            return byCode;
        }
        int byTarget = targetId().compareTo(other.targetId());
        return byTarget != 0 ? byTarget : message().compareTo(other.message());
    }
}
