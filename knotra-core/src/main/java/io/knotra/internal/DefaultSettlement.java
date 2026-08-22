package io.knotra.internal;

import io.knotra.Settlement;
import io.knotra.SettlementReport;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
/** Immutable default Settlement implementation shared by kernel changes. */
final class DefaultSettlement implements Settlement {
    private final long generation;
    private final CompletionStage<SettlementReport> settlement;

    public DefaultSettlement(long generation, CompletionStage<SettlementReport> settlement) {
        if (generation < 0) {
            throw new IllegalArgumentException("generation must not be negative");
        }
        this.generation = generation;
        this.settlement = Objects.requireNonNull(settlement, "settlement");
    }

    public static Settlement empty(long generation) {
        return new DefaultSettlement(
                generation,
                CompletableFuture.completedFuture(new SettlementReport(generation, List.of(), List.of())));
    }

    @Override
    public long generation() {
        return generation;
    }

    @Override
    public CompletionStage<SettlementReport> whenSettled() {
        return settlement;
    }
}
