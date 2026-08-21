package io.knotra.events;

import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface ParallelEventListener<T> {
    CompletionStage<Void> listen(T event) throws Exception;
}
