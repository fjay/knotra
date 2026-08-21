package io.knotra.pf4j;

import java.util.List;

import io.knotra.RuntimeDiagnostic;

/** 结构化 artifact 操作失败，携带稳定标识、阶段、文本和可选 Core 诊断。 */
public final class ArtifactOperationException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final String artifactId;
    private final String phase;
    private final List<RuntimeDiagnostic> diagnostics;

    public ArtifactOperationException(String artifactId, String phase, String message) {
        this(artifactId, phase, message, List.of());
    }

    public ArtifactOperationException(
            String artifactId,
            String phase,
            String message,
            List<RuntimeDiagnostic> diagnostics) {
        super(message);
        this.artifactId = artifactId == null ? "unknown" : artifactId;
        this.phase = phase == null ? "unknown" : phase;
        this.diagnostics = List.copyOf(diagnostics == null ? List.of() : diagnostics);
    }

    public String artifactId() {
        return artifactId;
    }

    public String phase() {
        return phase;
    }

    /** Core 拒绝产生的稳定诊断；非 Core 操作失败时为空。 */
    public List<RuntimeDiagnostic> diagnostics() {
        return diagnostics;
    }
}
