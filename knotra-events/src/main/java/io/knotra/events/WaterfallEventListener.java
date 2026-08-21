package io.knotra.events;

import java.util.concurrent.CompletionStage;

/** Transforms the current value and passes the returned value to the next listener. */
@FunctionalInterface
public interface WaterfallEventListener<T> {
    CompletionStage<T> transform(T event) throws Exception;
}
