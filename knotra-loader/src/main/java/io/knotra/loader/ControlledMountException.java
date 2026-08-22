package io.knotra.loader;

import java.util.List;

import io.knotra.RuntimeDiagnostic;

/**
 * 受控挂载边界上的结构化拒绝异常，携带 Core 事务产生的诊断列表。
 *
 * <p>受控挂载策略用它把内核或宿主的拒绝原因传回 Loader；Loader 会把其中的
 * RuntimeDiagnostic 映射为 Loader 诊断（通常为 STRUCTURE_REJECTED），
 * 保留结构化原因而不是只留下异常消息。
 */
public final class ControlledMountException extends RuntimeException {

    private final List<RuntimeDiagnostic> diagnostics;

    /** 诊断列表会被不可变拷贝；消息取第一条诊断，无诊断时使用通用文案。 */
    public ControlledMountException(List<RuntimeDiagnostic> diagnostics) {
        super(firstMessage(diagnostics));
        this.diagnostics = List.copyOf(diagnostics);
    }

    /** 不可变的核心诊断列表。 */
    public List<RuntimeDiagnostic> diagnostics() {
        return diagnostics;
    }

    private static String firstMessage(List<RuntimeDiagnostic> diagnostics) {
        if (diagnostics == null || diagnostics.isEmpty()) {
            return "controlled mount was rejected";
        }
        RuntimeDiagnostic first = diagnostics.getFirst();
        String detail = first.failure().summary();
        return detail.isBlank() ? first.message() : first.message() + " (" + detail + ")";
    }
}
