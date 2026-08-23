package io.knotra.events;

/** 事件名当前锁定的规范 JVM Class，以及仍引用该身份的订阅和分发计数。 */
final class EventBinding {
    private final Class<?> eventType;
    private int subscriptions;
    private int dispatches;

    EventBinding(Class<?> eventType) {
        this.eventType = eventType;
    }

    Class<?> eventType() {
        return eventType;
    }

    void subscriptionRegistered() {
        subscriptions++;
    }

    void subscriptionRemoved() {
        subscriptions--;
    }

    void dispatchAccepted() {
        dispatches++;
    }

    boolean dispatchFinished() {
        return --dispatches == 0;
    }

    boolean isIdle() {
        return subscriptions == 0 && dispatches == 0;
    }
}
