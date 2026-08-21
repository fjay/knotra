package io.knotra.events;

import java.util.concurrent.CompletionStage;

/**
 * A registered event listener.
 *
 * <p>Callbacks may cancel their own subscription with {@link #unsubscribe()}. A
 * callback must not block on {@link #closeAsync()} or {@link #close()}: those methods
 * wait for the callback to return first.</p>
 */
public interface EventSubscription extends AutoCloseable {
    String subscriptionId();

    String eventName();

    EventMode mode();

    long sequence();

    boolean active();

    /** Removes this subscription from future dispatches without waiting for accepted work. */
    void unsubscribe();

    /**
     * Cancels future dispatches and waits for every dispatch already accepted by this
     * subscription. Repeated calls return the same completion stage.
     */
    CompletionStage<Void> closeAsync();

    /** Cancels future dispatches and waits for accepted dispatches to finish. */
    @Override
    void close();
}
