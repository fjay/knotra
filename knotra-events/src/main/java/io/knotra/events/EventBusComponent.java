package io.knotra.events;

import io.knotra.ActivationContext;
import io.knotra.Component;
import io.knotra.ComponentDescriptor;
import io.knotra.NoConfig;
/**
 * Publishes an {@link EventBus} as a normal runtime capability.
 */
public final class EventBusComponent implements Component<NoConfig> {
    public static final EventBusComponent INSTANCE = new EventBusComponent();

    private static final ComponentDescriptor DESCRIPTOR =
            ComponentDescriptor.of("knotra-event-bus");

    private EventBusComponent() {
    }

    @Override
    public ComponentDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public void start(ActivationContext context, NoConfig config) {
        EventBus bus = new DefaultEventBus();
        context.lifecycle().manageAsync("event-bus", bus::closeAsync);
        context.provide(EventCapabilities.EVENT_BUS, bus);
    }
}
