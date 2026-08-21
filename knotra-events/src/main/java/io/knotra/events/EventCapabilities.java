package io.knotra.events;

import io.knotra.CapabilityKey;

/**
 * Capability keys published by the Knotra event component.
 */
public final class EventCapabilities {
    public static final CapabilityKey<EventBus> EVENT_BUS =
            CapabilityKey.of("knotra.event-bus", EventBus.class);

    private EventCapabilities() {
    }
}
