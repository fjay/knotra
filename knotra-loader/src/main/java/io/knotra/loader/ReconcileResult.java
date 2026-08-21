package io.knotra.loader;

import java.util.List;

public record ReconcileResult(
        boolean converged,
        List<Change> changes,
        List<LoaderDiagnostic> diagnostics) {

    public ReconcileResult {
        changes = List.copyOf(changes);
        diagnostics = List.copyOf(diagnostics).stream().sorted().toList();
    }

    public enum ChangeType {
        MOUNTED,
        UPDATED,
        REPLACED,
        REMOVED,
        RETRIED,
        BLOCKED
    }

    public record Change(ChangeType type, String path) {

        public static Change of(ChangeType type, String path) {
            return new Change(type, path);
        }
    }
}
