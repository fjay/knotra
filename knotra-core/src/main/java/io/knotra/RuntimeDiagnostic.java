package io.knotra;

/**
 * 结构化运行时诊断：诊断码、目标 ID 与稳定错误文本。
 *
 * <p>记录不可变且具有值相等语义；错误文本有界，不携带 Throwable、Class 或 ClassLoader，
 * 因此持有诊断不会阻止已卸载 artifact 的 ClassLoader 回收。
 */
public record RuntimeDiagnostic(
        DiagnosticCode code,
        String targetId,
        String message) implements Comparable<RuntimeDiagnostic> {

    public RuntimeDiagnostic {
        if (code == null || targetId == null || message == null) {
            throw new IllegalArgumentException("diagnostic fields must not be null");
        }
    }

    /** 先按诊断码、目标 ID，再按消息排序，保证诊断列表稳定有序。 */
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
