package io.knotra;

import java.util.List;

/** 结构事务被拒绝；异常始终携带稳定、可编程消费的诊断列表。 */
public final class TransactionRejectedException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final List<RuntimeDiagnostic> diagnostics;

    public TransactionRejectedException(List<RuntimeDiagnostic> diagnostics) {
        super(message(diagnostics));
        this.diagnostics = List.copyOf(diagnostics);
    }

    public List<RuntimeDiagnostic> diagnostics() {
        return diagnostics;
    }

    private static String message(List<RuntimeDiagnostic> diagnostics) {
        if (diagnostics == null || diagnostics.isEmpty()) {
            return "transaction was rejected";
        }
        RuntimeDiagnostic first = diagnostics.getFirst();
        return first.code() + ": " + first.message();
    }
}
