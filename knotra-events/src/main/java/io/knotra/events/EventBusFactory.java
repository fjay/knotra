package io.knotra.events;

import io.knotra.ComponentFactory;
import io.knotra.NoConfig;

public final class EventBusFactory implements ComponentFactory<NoConfig> {
    public static final String FACTORY_ID = "knotra-event-bus";

    @Override
    public String factoryId() {
        return FACTORY_ID;
    }

    @Override
    public EventBusComponent create() {
        return EventBusComponent.INSTANCE;
    }
}
