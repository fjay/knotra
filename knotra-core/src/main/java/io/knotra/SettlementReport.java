package io.knotra;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 单次已提交结构变更操作作用域内的不可变结算报告。
 *
 * <p>报告正常完成代表本次变更的依赖级联、子组件启动与旧代际排空已经平稳收敛。
 * 报告仅涵盖受本次操作影响的挂载点及其相关诊断，不掺杂全局无关状态。</p>
 */
public record SettlementReport(
        long generation,
        List<MountOutcome> mountOutcomes,
        List<RuntimeDiagnostic> diagnostics) {

    public SettlementReport {
        if (generation < 0) {
            throw new IllegalArgumentException("generation must not be negative");
        }
        mountOutcomes = mountOutcomes.stream()
                .sorted(Comparator.comparing(MountOutcome::handleId))
                .toList();
        diagnostics = diagnostics.stream().sorted().toList();
    }

    /**
     * 判断本次操作是否有受影响的挂载点。
     */
    public boolean hasAffectedMounts() {
        return !mountOutcomes.isEmpty();
    }

    /**
     * 判断本次受影响的挂载点中是否存在处于 {@code FAILED} 状态的挂载。
     * 空影响集返回 false。
     */
    public boolean hasFailedMounts() {
        return !failedMounts().isEmpty();
    }

    /**
     * 判断本次受影响的挂载点是否存在且全部处于 {@code ACTIVE} 活跃状态。
     * 若受影响挂载集为空，或者存在处于 WAITING、FAILED 或 DISPOSED 状态的挂载，则返回 false。
     */
    public boolean allAffectedActive() {
        return hasAffectedMounts()
                && mountOutcomes.stream().allMatch(outcome -> outcome.state() == ComponentState.ACTIVE);
    }


    /** 获取所有处于 FAILED 失败状态的挂载结果列表。 */
    public List<MountOutcome> failedMounts() {
        return mountOutcomes.stream()
                .filter(outcome -> outcome.state() == ComponentState.FAILED)
                .toList();
    }

    /** 根据挂载句柄 ID 获取具体的挂载结算结果。 */
    public Optional<MountOutcome> outcome(String handleId) {
        Objects.requireNonNull(handleId, "handleId");
        return mountOutcomes.stream()
                .filter(outcome -> outcome.handleId().equals(handleId))
                .findFirst();
    }


    public record MountOutcome(
            String handleId,
            String mountId,
            ComponentState state,
            List<RuntimeDiagnostic> diagnostics) {

        public MountOutcome {
            Objects.requireNonNull(handleId, "handleId");
            Objects.requireNonNull(mountId, "mountId");
            Objects.requireNonNull(state, "state");
            diagnostics = List.copyOf(diagnostics).stream().sorted().toList();
        }
    }
}
