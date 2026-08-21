package io.knotra.loader;

import java.util.Objects;

/**
 * Loader 发布的结构化诊断：诊断码、条目路径与消息。
 *
 * <p>路径为空表示 Loader 级问题，对外显示为 {@code <root>}。诊断按码、
 * 路径、消息排序，使 reconcile 结果与快照输出保持稳定，适合直接用于
 * 告警与协调逻辑。
 *
 * @param code 结构化诊断码
 * @param path 关联条目的归一化路径，或空串表示根级问题
 * @param message 简洁的问题描述
 */
public record LoaderDiagnostic(
        LoaderDiagnosticCode code,
        String path,
        String message) implements Comparable<LoaderDiagnostic> {

    public LoaderDiagnostic {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(message, "message");
    }

    /** 包内工厂方法：把空路径规范化为 {@code <root>}。 */
    static LoaderDiagnostic of(
            LoaderDiagnosticCode code,
            String path,
            String message) {
        return new LoaderDiagnostic(code, path.isEmpty() ? "<root>" : path, message);
    }

    /** 按诊断码、路径、消息的字典序稳定排序。 */
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
