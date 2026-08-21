package io.knotra.events;

public final record EventFailure(
        String subscriptionId,
        String eventName,
        String eventTypeName,
        EventMode mode,
        String message) {
}
