package io.knotra.events;

import java.util.List;
import java.util.Objects;

/**
 * Stable result of one dispatch. Listener callbacks and throwable objects are
 * deliberately absent from this value.
 */
public final record EventDispatch<T>(
        T initialEvent,
        T finalEvent,
        EventMode mode,
        int listenerCount,
        int completedCount,
        boolean stoppedEarly,
        List<EventFailure> failures) {

    public EventDispatch {
        Objects.requireNonNull(mode, "mode");
        failures = List.copyOf(failures == null ? List.of() : failures);
    }

    public static <T> EventDispatch<T> sync(T event, int listenerCount, List<EventFailure> failures) {
        return new EventDispatch<>(event, event, EventMode.SYNC, listenerCount,
                listenerCount - (failures == null ? 0 : failures.size()), false,
                failures == null ? List.of() : failures);
    }

    public boolean successful() {
        return failures.isEmpty();
    }

    public int failureCount() {
        return failures.size();
    }
}
