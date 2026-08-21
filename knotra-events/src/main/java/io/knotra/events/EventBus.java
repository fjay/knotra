package io.knotra.events;

import java.util.concurrent.CompletionStage;

/**
 * A typed event bus. All close methods wait for callbacks that were already accepted
 * before the close was observed; new subscriptions and dispatches are rejected after close.
 */
public interface EventBus extends AutoCloseable {
    String busId();

    <T> EventSubscription on(EventDefinition<T> definition, EventListener<? super T> listener);

    <T> EventSubscription onParallel(
            EventDefinition<T> definition, ParallelEventListener<? super T> listener);

    <T> EventSubscription onSerial(
            EventDefinition<T> definition, SerialEventListener<? super T> listener);

    <T> EventSubscription onBail(
            EventDefinition<T> definition, BailEventListener<? super T> listener);

    <T> EventSubscription onWaterfall(
            EventDefinition<T> definition, WaterfallEventListener<T> listener);

    <T> EventDispatch<T> emit(EventDefinition<T> definition, T event);

    <T> CompletionStage<EventDispatch<T>> parallel(EventDefinition<T> definition, T event);

    <T> CompletionStage<EventDispatch<T>> serial(EventDefinition<T> definition, T event);

    <T> CompletionStage<EventDispatch<T>> bail(EventDefinition<T> definition, T event);

    <T> CompletionStage<EventDispatch<T>> waterfall(EventDefinition<T> definition, T event);

    EventBusSnapshot snapshot();

    /** Returns a stage that completes when all currently accepted dispatches have settled. */
    CompletionStage<Void> whenIdle();

    /**
     * Closes future work, waits for accepted dispatches, and stops an executor owned by
     * this bus. Repeated calls return the same completion stage.
     */
    CompletionStage<Void> closeAsync();

    /** Joins {@link #closeAsync()}. */
    @Override
    void close();
}
