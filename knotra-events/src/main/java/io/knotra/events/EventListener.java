package io.knotra.events;

@FunctionalInterface
public interface EventListener<T> {
    void listen(T event) throws Exception;
}
