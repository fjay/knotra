package io.knotra.events;

import java.util.concurrent.CompletionStage;

/** Returning {@code false} stops the serial dispatch without reporting an error. */
@FunctionalInterface
public interface SerialEventListener<T> {
    CompletionStage<Boolean> listen(T event) throws Exception;
}
