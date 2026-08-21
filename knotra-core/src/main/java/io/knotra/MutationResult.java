package io.knotra;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

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

    public static <R> MutationResult<R> committed(
            R value,
            long generation,
            CompletionStage<Void> settlement) {
        return new MutationResult<>(true, generation, value, List.of(), settlement);
    }

    public static <R> MutationResult<R> rejected(List<RuntimeDiagnostic> diagnostics) {
        return new MutationResult<>(false, -1L, null, diagnostics, null);
    }

    public boolean committed() {
        return committed;
    }

    public long generation() {
        return generation;
    }

    public R value() {
        if (!committed) {
            throw new IllegalStateException("mutation was not committed: " + diagnostics);
        }
        return value;
    }

    public List<RuntimeDiagnostic> diagnostics() {
        return diagnostics;
    }

    public CompletionStage<Void> settlement() {
        return settlement;
    }
}
