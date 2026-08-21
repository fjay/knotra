package io.knotra.events;

import java.util.List;

public final record EventBusSnapshot(String busId, boolean closed, List<Item> subscriptions) {

    public EventBusSnapshot {
        subscriptions = List.copyOf(subscriptions == null ? List.of() : subscriptions);
    }

    public int subscriptionCount() {
        return subscriptions.size();
    }

    public record Item(
            String subscriptionId,
            String eventName,
            String eventTypeName,
            EventMode mode,
            long sequence) {
    }
}
