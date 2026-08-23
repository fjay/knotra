package io.knotra;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Runtime 排空过程中的挂起操作快照。
 *
 * <p>快照只包含稳定文本、枚举和非负时长，不引用组件、工厂、释放器、异常或 ClassLoader。
 * 各操作来自不同叶子锁的 point-in-time 采样，不承诺全局原子性。</p>
 *
 * <p>构造器会对传入操作做确定性排序并最多保留 {@value MAX_OPERATIONS} 个，超出部分被截断，
 * {@code omitted} 由构造器按截断结果重新计算（传入值会被忽略）。每个操作的 targetId 截断到
 * {@value TARGET_CODE_POINTS} 个 code point、detail 截断到 {@value DETAIL_CODE_POINTS} 个 code point，
 * 均按 code point 边界截断，不会拆分代理对。租约类操作（PROVIDER_LEASE、CONSUMER_LEASE）的
 * age 从最早一次仍持有的 acquire 起算，而不是从 drain/retire 开始起算。</p>
 */
public record PendingOperationsSnapshot(
        boolean closeRequested,
        List<Operation> operations,
        int omitted) {

    private static final int MAX_OPERATIONS = 128;
    private static final int TARGET_CODE_POINTS = 128;
    private static final int DETAIL_CODE_POINTS = 512;

    public PendingOperationsSnapshot {
        Objects.requireNonNull(operations, "operations");
        List<Operation> validated = new ArrayList<>(operations.size());
        for (Operation operation : operations) {
            validated.add(Objects.requireNonNull(operation, "operation"));
        }
        validated.sort(Comparator
                .comparing((Operation operation) -> operation.kind().name())
                .thenComparing(operation -> operation.targetId().toString())
                .thenComparing(operation -> operation.waitsFor().name())
                .thenComparing(operation -> operation.detail().toString()));
        int extra = Math.max(0, validated.size() - MAX_OPERATIONS);
        operations = List.copyOf(validated.size() <= MAX_OPERATIONS
                ? validated
                : validated.subList(0, MAX_OPERATIONS));
        omitted = extra;
    }

    /** 以确定性、有界的多行文本渲染快照；换行会被转义。 */
    public String render() {
        StringBuilder result = new StringBuilder(operations.size() * 64 + 64);
        result.append(String.format(Locale.ROOT, "closeRequested=%b\n", closeRequested));
        for (Operation operation : operations) {
            result.append(operation.kind().name())
                    .append('|')
                    .append(escape(operation.targetId()))
                    .append('|')
                    .append(operation.waitsFor().name())
                    .append('|')
                    .append(operation.age())
                    .append('|')
                    .append(escape(operation.detail()))
                    .append('\n');
        }
        result.append(String.format(Locale.ROOT, "omitted=%d", omitted));
        return result.toString();
    }

    private static String escape(String value) {
        return value.replace("\r", "\\r").replace("\n", "\\n");
    }

    /** 单个挂起操作的稳定描述。 */
    public record Operation(
            Kind kind,
            String targetId,
            WaitType waitsFor,
            Duration age,
            String detail) {

        public Operation {
            Objects.requireNonNull(kind, "kind");
            targetId = requireText(targetId, "targetId");
            Objects.requireNonNull(waitsFor, "waitsFor");
            Objects.requireNonNull(age, "age");
            if (age.isNegative()) {
                throw new IllegalArgumentException("age must not be negative");
            }
            detail = requireText(detail, "detail");
            targetId = truncate(targetId, TARGET_CODE_POINTS);
            detail = truncate(detail, DETAIL_CODE_POINTS);
        }
    }

    /**
     * v1 识别的挂起操作类别。
     *
     * <p>跨模块常量只是共享的纯诊断分类：core 只负责校验、排序和渲染，
     * 不解释对应模块的状态机，也不因此引入模块间依赖。</p>
     */
    public enum Kind {
        RUNTIME_CLOSE,
        CONTEXT_DISPOSAL,
        COMPONENT_TRANSITION,
        LIFECYCLE_CLEANUP,
        PROVIDER_LEASE,
        CONSUMER_LEASE,
        EVENT_DISPATCH,
        EVENT_SUBSCRIPTION_DRAIN,
        ARTIFACT_MOUNT,
        ARTIFACT_DRAIN,
        LOADER_OPERATION
    }

    /**
     * 操作当前等待的收敛边界。
     *
     * <p>跨模块常量只是共享的纯诊断分类：core 不定义也不驱动相应边界，
     * 仅以稳定文本参与快照排序和渲染。</p>
     */
    public enum WaitType {
        COMPONENT,
        CONTEXT,
        LIFECYCLE_ENTRY,
        LEASE_RELEASE,
        RUNTIME_DRAIN,
        LISTENER,
        DISPATCH,
        MOUNTS_IN_FLIGHT,
        PF4J_STOP_UNLOAD,
        COORDINATOR,
        EXECUTOR_TERMINATION
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        return value;
    }

    private static String truncate(String value, int codePointLimit) {
        if (value.codePointCount(0, value.length()) <= codePointLimit) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, codePointLimit));
    }
}
