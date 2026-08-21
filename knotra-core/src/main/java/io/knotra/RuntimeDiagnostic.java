package io.knotra;

public record RuntimeDiagnostic(
        DiagnosticCode code,
        String targetId,
        String message) implements Comparable<RuntimeDiagnostic> {

    public RuntimeDiagnostic {
        if (code == null || targetId == null || message == null) {
            throw new IllegalArgumentException("diagnostic fields must not be null");
        }
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
