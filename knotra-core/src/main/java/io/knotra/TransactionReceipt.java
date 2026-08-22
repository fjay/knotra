package io.knotra;

import java.util.Objects;

/** 同步提交的结构化事务收据凭证。 */
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
