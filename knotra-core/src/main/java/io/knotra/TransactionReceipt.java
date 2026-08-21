package io.knotra;

import java.util.Objects;
import java.util.concurrent.CompletionStage;

/** 一次已提交结构事务的收据。事务拒绝通过 TransactionRejectedException 抛出。 */
public record TransactionReceipt<R>(
        R value,
        long generation,
        CompletionStage<Void> settlement) {

    public TransactionReceipt {
        if (generation < 0) {
            throw new IllegalArgumentException("generation must not be negative");
        }
        Objects.requireNonNull(settlement, "settlement");
    }
}
