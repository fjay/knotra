package io.knotra.loader;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

import io.knotra.DiagnosticCode;
import io.knotra.RuntimeDiagnostic;
import io.knotra.SettlementReport;
import io.knotra.TransactionRejectedException;

/**
 * Core 诊断到 Loader 诊断的映射器：展开 async 包装、保留结构化事务诊断，
 * 并把 Core 诊断码稳定映射到 Loader 诊断码。
 */
final class LoaderDiagnosticMapper {

    private LoaderDiagnosticMapper() {
    }

    /** 把 settlement 报告中的失败结果展开为 Loader 诊断。 */
    static void addSettlementDiagnostics(
            LoaderDiagnosticCode fallback,
            String path,
            List<LoaderDiagnostic> diagnostics,
            SettlementReport report) {
        for (SettlementReport.MountOutcome outcome : report.failedMounts()) {
            if (outcome.diagnostics().isEmpty()) {
                diagnostics.add(LoaderDiagnostic.of(
                        fallback,
                        path,
                        "mount " + outcome.mountId() + " settled as " + outcome.state()));
            } else {
                addCoreDiagnostics(fallback, path, diagnostics, outcome.diagnostics());
            }
        }
        if (!report.diagnostics().isEmpty()) {
            addCoreDiagnostics(fallback, path, diagnostics, report.diagnostics());
        }
        if (report.failedMounts().isEmpty() && report.diagnostics().isEmpty()) {
            diagnostics.add(LoaderDiagnostic.of(
                    fallback,
                    path,
                    "settlement completed with an unsuccessful outcome"));
        }
    }

    /** 展开 async 包装；Core 事务拒绝保留结构化诊断，其余失败使用根因消息。 */
    static void addAsyncDiagnostics(
            LoaderDiagnosticCode fallback,
            String path,
            List<LoaderDiagnostic> diagnostics,
            Throwable error) {
        Throwable cause = error;
        while ((cause instanceof CompletionException
                || cause instanceof ExecutionException) && cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause instanceof TransactionRejectedException rejection) {
            addCoreDiagnostics(fallback, path, diagnostics, rejection.diagnostics());
            return;
        }
        diagnostics.add(LoaderDiagnostic.of(
                fallback,
                path,
                LoaderErrors.safe(cause)));
    }

    /** 把 Core 事务诊断映射进 Loader 结果；核心未给出诊断时提供兜底文案。 */
    static void addCoreDiagnostics(
            LoaderDiagnosticCode fallback,
            String path,
            List<LoaderDiagnostic> diagnostics,
            List<RuntimeDiagnostic> values) {
        if (values.isEmpty()) {
            diagnostics.add(LoaderDiagnostic.of(fallback, path, "runtime transaction was rejected"));
            return;
        }
        for (RuntimeDiagnostic value : values) {
            diagnostics.add(LoaderDiagnostic.of(
                    mapCode(value.code(), fallback),
                    path,
                    diagnosticMessage(value)));
        }
    }

    private static String diagnosticMessage(RuntimeDiagnostic diagnostic) {
        RuntimeDiagnostic stable = Objects.requireNonNull(diagnostic, "diagnostic");
        String summary = stable.failure().summary();
        return summary.isBlank() ? stable.message() : stable.message() + " (" + summary + ")";
    }

    /** Core 诊断码到 Loader 诊断码的稳定映射；无法识别时回退到调用方指定的码。 */
    private static LoaderDiagnosticCode mapCode(
            DiagnosticCode code,
            LoaderDiagnosticCode fallback) {
        return switch (code) {
            case ACTIVATION_FAILED -> LoaderDiagnosticCode.ACTIVATION_FAILED;
            case CLEANUP_FAILED, ROLLBACK_FAILED -> LoaderDiagnosticCode.TEARDOWN_FAILED;
            case INVALID_CONFIG -> LoaderDiagnosticCode.CONFIG_INVALID;
            case MISSING_CAPABILITY, CAPABILITY_SLOT_OCCUPIED, CAPABILITY_TYPE_CONFLICT,
                    BINDING_CYCLE, NON_CONVERGENT_RECONCILE, INVALID_LIFECYCLE_OPERATION,
                    INVALID_MOUNT_ID -> LoaderDiagnosticCode.STRUCTURE_REJECTED;
        };
    }
}
