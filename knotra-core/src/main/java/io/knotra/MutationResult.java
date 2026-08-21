package io.knotra;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * 宿主事务的结果：已提交的代际与值，或被拒绝时的诊断。
 *
 * <p>提交意味着新视图已原子发布并调度了受影响组件的过渡；被拒绝时不会发布任何内容，
 * generation 为 -1 且 value 不可用。不可变；settlement 只等待本次事务直接触发的过渡，
 * 不代表运行时全局静止，观察特定挂载应使用 {@link ComponentHandle#whenSettled()}。
 */
public final class MutationResult<R> {
    private final boolean committed;
    private final long generation;
    private final R value;
    private final List<RuntimeDiagnostic> diagnostics;
    private final CompletionStage<Void> settlement;

    private MutationResult(
            boolean committed,
            long generation,
            R value,
            List<RuntimeDiagnostic> diagnostics,
            CompletionStage<Void> settlement) {
        this.committed = committed;
        this.generation = generation;
        this.value = value;
        this.diagnostics = List.copyOf(diagnostics);
        this.settlement = settlement == null ? CompletableFuture.completedFuture(null) : settlement;
    }

    /** 创建已提交结果，携带提交代际与过渡结算 stage。 */
    public static <R> MutationResult<R> committed(
            R value,
            long generation,
            CompletionStage<Void> settlement) {
        return new MutationResult<>(true, generation, value, List.of(), settlement);
    }

    /** 创建被拒绝结果；generation 为 -1，无结算。 */
    public static <R> MutationResult<R> rejected(List<RuntimeDiagnostic> diagnostics) {
        return new MutationResult<>(false, -1L, null, diagnostics, null);
    }

    public boolean committed() {
        return committed;
    }

    /** 返回提交事务所在的视图代际；被拒绝时为 -1。 */
    public long generation() {
        return generation;
    }

    /**
     * 返回事务回调的值。
     *
     * @throws IllegalStateException 事务未提交
     */
    public R value() {
        if (!committed) {
            throw new IllegalStateException("mutation was not committed: " + diagnostics);
        }
        return value;
    }

    /** 返回诊断列表；已提交时为空。 */
    public List<RuntimeDiagnostic> diagnostics() {
        return diagnostics;
    }

    /** 返回本次事务直接触发的过渡结算；被拒绝时返回已完成的空 stage。 */
    public CompletionStage<Void> settlement() {
        return settlement;
    }
}
