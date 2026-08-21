package io.knotra.loader;

import java.util.Objects;

public record LoaderDiagnostic(
        LoaderDiagnosticCode code,
        String path,
        String message) implements Comparable<LoaderDiagnostic> {

    public LoaderDiagnostic {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(message, "message");
    }

    static LoaderDiagnostic of(
            LoaderDiagnosticCode code,
            String path,
            String message) {
        return new LoaderDiagnostic(code, path.isEmpty() ? "<root>" : path, message);
    }

    @Override
    public int compareTo(LoaderDiagnostic other) {
        int byCode = code.name().compareTo(other.code.name());
        if (byCode != 0) {
            return byCode;
        }
        int byPath = path.compareTo(other.path);
        return byPath != 0 ? byPath : message.compareTo(other.message);
    }
}
