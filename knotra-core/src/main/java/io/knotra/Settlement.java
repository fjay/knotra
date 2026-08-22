package io.knotra;

/** The committed structural generation and asynchronous propagation result of one change. */
public interface Settlement extends Awaitable<SettlementReport> {
    long generation();
}
