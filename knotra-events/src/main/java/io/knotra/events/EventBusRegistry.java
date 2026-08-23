package io.knotra.events;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 总线订阅注册表。除保存固定顺序外，也负责把订阅生命周期事件同步到规范事件绑定；
 * 除 snapshot 外，所有方法都要求调用方已经持有对应的总线锁。
 */
final class EventBusRegistry {
    private final Map<SubscriptionIndex, List<RegisteredSubscription>> subscriptions =
            new HashMap<>();
    private final EventBindingRegistry eventBindings = new EventBindingRegistry();
    private final AtomicLong sequences = new AtomicLong();
    private final ReentrantReadWriteLock lock;

    EventBusRegistry(ReentrantReadWriteLock lock) {
        this.lock = lock;
    }

    RegisteredSubscription register(String busId, EventDefinition<?> definition, Object listener) {
        // 注册前获取规范绑定，防止同名事件在存活期间被另一个 ClassLoader 的同名 Class 重绑。
        EventBinding binding = eventBindings.acquire(definition);
        long sequence = sequences.incrementAndGet();
        RegisteredSubscription subscription = new RegisteredSubscription(
                "event-subscription-" + busId + "-" + sequence,
                sequence,
                definition,
                listener,
                lock,
                this::remove);
        subscriptions.computeIfAbsent(
                        index(definition, binding.eventType()),
                        ignored -> new ArrayList<>())
                .add(subscription);
        binding.subscriptionRegistered();
        return subscription;
    }

    void remove(RegisteredSubscription subscription) {
        SubscriptionIndex index = new SubscriptionIndex(
                subscription.eventName(),
                subscription.definitionEventType(),
                subscription.mode());
        List<RegisteredSubscription> listeners = subscriptions.get(index);
        if (listeners == null || !listeners.remove(subscription)) {
            return;
        }
        EventBinding binding = eventBindings.find(subscription.eventName());
        if (binding != null) {
            binding.subscriptionRemoved();
            eventBindings.removeIfIdle(subscription.eventName(), binding);
        }
        if (listeners.isEmpty()) {
            subscriptions.remove(index);
        }
    }

    List<RegisteredSubscription> matching(
            String eventName,
            Class<?> eventType,
            EventMode mode) {
        return subscriptions.getOrDefault(new SubscriptionIndex(eventName, eventType, mode), List.of())
                .stream()
                .toList();
    }

    List<EventBusSnapshot.Item> snapshotItems() {
        List<EventBusSnapshot.Item> items = new ArrayList<>();
        for (List<RegisteredSubscription> listeners : subscriptions.values()) {
            for (RegisteredSubscription subscription : listeners) {
                if (subscription.active()) {
                    items.add(subscription.toSnapshotItem());
                }
            }
        }
        items.sort(Comparator
                .comparing(EventBusSnapshot.Item::eventName)
                .thenComparing(item -> item.mode().ordinal())
                .thenComparingLong(EventBusSnapshot.Item::sequence));
        return items;
    }

    EventBinding acquireBinding(EventDefinition<?> definition) {
        return eventBindings.acquire(definition);
    }

    void releaseAcceptedBinding(EventBinding binding) {
        eventBindings.releaseAccepted(binding);
    }

    void pruneIdleBindings() {
        eventBindings.pruneIdle();
    }

    void markClosedAndClear() {
        // 只清空注册表；已接受分发仍直接持有 EventBinding，等待结束后自行释放规范身份。
        for (List<RegisteredSubscription> listeners : subscriptions.values()) {
            for (RegisteredSubscription subscription : listeners) {
                subscription.markClosed();
            }
        }
        subscriptions.clear();
        eventBindings.clear();
    }

    private static SubscriptionIndex index(
            EventDefinition<?> definition,
            Class<?> canonicalEventType) {
        return new SubscriptionIndex(definition.name(), canonicalEventType, definition.mode());
    }

    private record SubscriptionIndex(String name, Class<?> eventType, EventMode mode) {
    }
}
