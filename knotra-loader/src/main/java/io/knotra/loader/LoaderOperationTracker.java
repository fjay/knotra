package io.knotra.loader;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import io.knotra.PendingOperationsSnapshot;
import io.knotra.PendingOperationsSnapshot.Kind;
import io.knotra.PendingOperationsSnapshot.WaitType;

/**
 * Loader 协调器操作的挂起追踪器。
 *
 * <p>追踪器只保存稳定文本、枚举与 {@code long} 时间戳：绝不引用 Callable、期望树、
 * 工厂、配置、Throwable、句柄或 Context。每个操作在入队时获得唯一 operation ID 与
 * startNanos，阶段推进以 {@link Operation} 的对象身份定位，完成或失败后按同一身份移除；
 * 并发入队的操作互不覆盖。快照读取只持有短锁复制不可变值，随后在锁外装配公共 DTO，
 * 不进入协调器、不改变任何 Loader 行为。</p>
 */
final class LoaderOperationTracker {

    /**
     * 显式传递的操作令牌：既是协调器操作的稳定身份，也是阶段更新的入口。
     * 令牌只承载身份与所属 tracker，不携带任何操作数据。
     */
    static final class Operation {

        private final LoaderOperationTracker tracker;
        private final String operationId;

        private Operation(LoaderOperationTracker tracker, String operationId) {
            this.tracker = tracker;
            this.operationId = operationId;
        }

        /** 协调器线程已拾取该操作。 */
        void running() {
            tracker.markRunning(this);
        }

        /**
         * 推进阶段并替换目标描述。deadline 为 {@link Duration#ZERO} 表示无时限；
         * 其余值进入 detail 的剩余毫秒计算。
         */
        void phase(
                Phase phase,
                String targetId,
                String path,
                String secondaryId,
                Duration deadline) {
            tracker.update(this, phase, targetId, path, secondaryId, deadline);
        }

        /** 操作完成或失败后按身份移除；重复调用是幂等的。 */
        void complete() {
            tracker.remove(this);
        }

        String operationId() {
            return operationId;
        }
    }

    /** Loader 操作在收敛管线中的阶段；阶段决定公共 DTO 的 WaitType。 */
    enum Phase {
        QUEUED("queued", WaitType.COORDINATOR),
        RUNNING("running", WaitType.COORDINATOR),
        PREPARE("prepare", WaitType.COORDINATOR),
        CONTEXT_SETTLEMENT("context-settlement", WaitType.CONTEXT),
        MOUNT_EXECUTION("mount-execution", WaitType.USER_CALLBACK),
        MOUNT_SETTLEMENT("mount-settlement", WaitType.COMPONENT),
        RECONFIGURE("reconfigure", WaitType.COMPONENT),
        RETRY_ACTIVATION("retry-activation", WaitType.COMPONENT),
        DISPOSE_HANDLE("dispose-handle", WaitType.COMPONENT),
        DISPOSE_CONTEXT("dispose-context", WaitType.CONTEXT),
        RUNTIME_OWNED("runtime-owned", WaitType.RUNTIME_DRAIN);

        private final String displayName;
        private final WaitType waitsFor;

        Phase(String displayName, WaitType waitsFor) {
            this.displayName = displayName;
            this.waitsFor = waitsFor;
        }

        String displayName() {
            return displayName;
        }

        WaitType waitsFor() {
            return waitsFor;
        }
    }

    /** 叶子锁内复制的纯值；字段只允许 String、枚举与 long。 */
    record Recorded(
            String operationType,
            String operationId,
            Phase phase,
            String targetId,
            String path,
            String secondaryId,
            long startNanos,
            long phaseStartNanos,
            long deadlineNanos) {
    }

    private final LongSupplier ticker;
    private final AtomicLong nextOperationId = new AtomicLong();
    private final Object lock = new Object();
    private final Map<Operation, Recorded> operations = new LinkedHashMap<>();

    LoaderOperationTracker(LongSupplier ticker) {
        this.ticker = Objects.requireNonNull(ticker, "ticker");
    }

    /** 入队即登记：分配 operation ID 与 startNanos，初始阶段为 QUEUED。 */
    Operation begin(String operationType, String targetId) {
        Objects.requireNonNull(operationType, "operationType");
        Objects.requireNonNull(targetId, "targetId");
        long now = ticker.getAsLong();
        Operation operation = new Operation(this, "op-" + nextOperationId.incrementAndGet());
        Recorded recorded = new Recorded(
                operationType,
                operation.operationId(),
                Phase.QUEUED,
                targetId,
                "",
                "",
                now,
                now,
                0L);
        synchronized (lock) {
            operations.put(operation, recorded);
        }
        return operation;
    }

    private void markRunning(Operation operation) {
        mutate(operation, Phase.RUNNING, null, null, null, null);
    }

    private void update(
            Operation operation,
            Phase phase,
            String targetId,
            String path,
            String secondaryId,
            Duration deadline) {
        mutate(operation, phase, targetId, path, secondaryId, deadline);
    }

    private void mutate(
            Operation operation,
            Phase phase,
            String targetId,
            String path,
            String secondaryId,
            Duration deadline) {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(phase, "phase");
        long now = ticker.getAsLong();
        synchronized (lock) {
            Recorded current = operations.get(operation);
            if (current == null) {
                return;
            }
            operations.put(operation, new Recorded(
                    current.operationType(),
                    current.operationId(),
                    phase,
                    targetId == null ? current.targetId() : targetId,
                    path == null ? current.path() : path,
                    secondaryId == null ? current.secondaryId() : secondaryId,
                    current.startNanos(),
                    now,
                    deadline == null ? current.deadlineNanos() : deadline.toNanos()));
        }
    }

    private void remove(Operation operation) {
        synchronized (lock) {
            operations.remove(operation);
        }
    }

    /** 短锁复制 point-in-time 值，锁外装配公共 DTO；读取本身无副作用。 */
    PendingOperationsSnapshot snapshot(long nowNanos, boolean closeRequested) {
        List<Recorded> copied;
        synchronized (lock) {
            copied = new ArrayList<>(operations.values());
        }
        List<PendingOperationsSnapshot.Operation> rendered = copied.stream()
                .map(recorded -> new PendingOperationsSnapshot.Operation(
                        Kind.LOADER_OPERATION,
                        recorded.targetId(),
                        recorded.phase().waitsFor(),
                        age(nowNanos, recorded.startNanos()),
                        detail(recorded, nowNanos)))
                .toList();
        return new PendingOperationsSnapshot(closeRequested, rendered, 0);
    }

    /** 测试钩子：暴露当前纯值，供“不保存非稳定引用”断言使用。 */
    List<Recorded> recordedForTesting() {
        synchronized (lock) {
            return List.copyOf(operations.values());
        }
    }

    private static Duration age(long nowNanos, long startNanos) {
        return Duration.ofNanos(Math.max(0L, nowNanos - startNanos));
    }

    private static String detail(Recorded recorded, long nowNanos) {
        StringBuilder result = new StringBuilder(96);
        result.append("type=").append(recorded.operationType())
                .append(" phase=").append(recorded.phase().displayName());
        if (!recorded.path().isEmpty()) {
            result.append(" path=").append(recorded.path());
        }
        if (!recorded.secondaryId().isEmpty()) {
            result.append(" id=").append(recorded.secondaryId());
        }
        if (recorded.deadlineNanos() > 0L) {
            long elapsed = Math.max(0L, nowNanos - recorded.phaseStartNanos());
            long remainingMs = Math.max(0L, recorded.deadlineNanos() - elapsed) / 1_000_000L;
            result.append(" deadline-remaining=").append(remainingMs).append("ms");
        }
        result.append(" op=").append(recorded.operationId());
        return result.toString();
    }
}
