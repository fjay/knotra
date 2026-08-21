package io.knotra.loader;

import java.util.List;

/** Loader 收敛未完成时由 {@link ReconcileResult#requireConverged()} 抛出。 */
public final class ReconcileException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final List<LoaderDiagnostic> diagnostics;

    public ReconcileException(List<LoaderDiagnostic> diagnostics) {
        super(message(diagnostics));
        this.diagnostics = List.copyOf(diagnostics);
    }

    /** 稳定排序后的结构化诊断；可能为空，例如状态尚未完成收敛。 */
    public List<LoaderDiagnostic> diagnostics() {
        return diagnostics;
    }

    private static String message(java.util.List<LoaderDiagnostic> diagnostics) {
        if (diagnostics == null || diagnostics.isEmpty()) {
            return "reconcile did not converge";
        }
        LoaderDiagnostic first = diagnostics.getFirst();
        return first.code() + ": " + first.message();
    }
}
