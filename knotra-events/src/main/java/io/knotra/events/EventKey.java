package io.knotra.events;

import java.util.Objects;

/**
 * Stable identity of an event. The identity is always the JVM type name and is
 * intentionally independent of the dispatch mode.
 */
public final record EventKey<T>(Class<T> eventType) implements Comparable<EventKey<T>> {

    public EventKey {
        Objects.requireNonNull(eventType, "eventType");
        if (eventType.isPrimitive()) {
            throw new IllegalArgumentException("primitive event types are not supported");
        }
    }

    public static <T> EventKey<T> of(Class<T> eventType) {
        return new EventKey<>(eventType);
    }

    public String name() {
        return eventType.getName();
    }

    @Override
    public int compareTo(EventKey<T> other) {
        return name().compareTo(other.name());
    }
}
