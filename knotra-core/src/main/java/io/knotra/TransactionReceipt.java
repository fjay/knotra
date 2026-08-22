package io.knotra;

import java.util.Objects;

/** Receipt for a synchronously committed structural transaction. */
public record TransactionReceipt<R>(R value, Settlement settlement) implements Settlement {

    public TransactionReceipt {
        Objects.requireNonNull(settlement, "settlement");
    }

    @Override
    public long generation() {
        return settlement.generation();
    }

    @Override
    public java.util.concurrent.CompletionStage<SettlementReport> whenSettled() {
        return settlement.whenSettled();
    }
}
