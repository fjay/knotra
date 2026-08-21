package io.knotra.events;

import java.util.Objects;

/**
 * A typed event and its dispatch policy.
 */
public final record EventDefinition<T>(EventKey<T> key, EventMode mode) {

    public EventDefinition {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(mode, "mode");
    }

    public static <T> EventDefinition<T> sync(EventKey<T> key) {
        return new EventDefinition<>(key, EventMode.SYNC);
    }

    public static <T> EventDefinition<T> parallel(EventKey<T> key) {
        return new EventDefinition<>(key, EventMode.PARALLEL);
    }

    public static <T> EventDefinition<T> serial(EventKey<T> key) {
        return new EventDefinition<>(key, EventMode.SERIAL);
    }

    public static <T> EventDefinition<T> bail(EventKey<T> key) {
        return new EventDefinition<>(key, EventMode.BAIL);
    }

    public static <T> EventDefinition<T> waterfall(EventKey<T> key) {
        return new EventDefinition<>(key, EventMode.WATERFALL);
    }

    public String name() {
        return key.name();
    }

    public Class<T> eventType() {
        return key.eventType();
    }
}
