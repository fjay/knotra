package io.knotra.events;

/** Returning {@code true} claims the bail result and stops later listeners. */
@FunctionalInterface
public interface BailEventListener<T> {
    boolean bail(T event) throws Exception;
}
