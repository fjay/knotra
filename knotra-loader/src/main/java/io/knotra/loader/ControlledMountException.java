package io.knotra.loader;

import java.util.List;

import io.knotra.RuntimeDiagnostic;

/**
 * Carries structured Core rejection diagnostics across a controlled mount boundary.
 */
public final class ControlledMountException extends RuntimeException {

    private final List<RuntimeDiagnostic> diagnostics;

    public ControlledMountException(List<RuntimeDiagnostic> diagnostics) {
        super(firstMessage(diagnostics));
        this.diagnostics = List.copyOf(diagnostics);
    }

    public List<RuntimeDiagnostic> diagnostics() {
        return diagnostics;
    }

    private static String firstMessage(List<RuntimeDiagnostic> diagnostics) {
        if (diagnostics == null || diagnostics.isEmpty()) {
            return "controlled mount was rejected";
        }
        return diagnostics.getFirst().message();
    }
}
