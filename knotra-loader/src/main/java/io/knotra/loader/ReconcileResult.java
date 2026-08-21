package io.knotra.loader;

import java.util.List;

/**
 * 一次 reconcile 的结果：收敛状态、发生的变更与诊断。
 *
 * <p>{@code converged} 为 true 表示期望树已完整落地且没有诊断；为 false 时
 * 未必毫无变更：已提交的变更记录在 changes 中，剩余差异由后续 reconcile
 * 或显式 retry 继续收敛。诊断按稳定顺序排序。
 *
 * @param converged 期望树与运行时状态是否已无差异
 * @param changes 本次已提交的变更，按执行顺序记录
 * @param diagnostics 结构化诊断，已排序
 */
public record ReconcileResult(
        boolean converged,
        List<Change> changes,
        List<LoaderDiagnostic> diagnostics) {

    public ReconcileResult {
        changes = List.copyOf(changes);
        diagnostics = List.copyOf(diagnostics).stream().sorted().toList();
    }

    /** reconcile 或显式 retry 产生的变更类型。 */
    public enum ChangeType {
        /** 新条目完成挂载。 */
        MOUNTED,
        /** 实现身份不变，配置已完成重配置。 */
        UPDATED,
        /** 实现身份变化，旧句柄已释放并挂载新实现。 */
        REPLACED,
        /** 条目不再属于期望树，对应子树已完成释放。 */
        REMOVED,
        /** FAILED 条目经显式 retry 重新激活。 */
        RETRIED,
        /** 变更被阻塞（典型为清理失败），等待后续收敛。 */
        BLOCKED
    }

    /**
     * 单条变更记录。
     *
     * @param type 变更类型
     * @param path 受影响条目的归一化路径
     */
    public record Change(ChangeType type, String path) {

        public static Change of(ChangeType type, String path) {
            return new Change(type, path);
        }
    }
}
