package io.knotra.events;

import java.util.HashMap;
import java.util.Map;

/**
 * 事件名到规范 JVM Class 的绑定表。所有方法都要求调用方已持有总线写锁或处于安全的只读路径；
 * 绑定只要仍被订阅或已接受分发引用，就不会被移除。
 */
final class EventBindingRegistry {
    private final Map<String, EventBinding> bindings = new HashMap<>();

    EventBinding acquire(EventDefinition<?> definition) {
        Class<?> candidate = definition.eventType();
        EventBinding binding = bindings.get(definition.name());
        if (binding == null) {
            binding = new EventBinding(candidate);
            bindings.put(definition.name(), binding);
            return binding;
        }
        // 必须用对象身份比较 Class：不同 artifact ClassLoader 中的同名 Class 不是同一事件身份。
        if (binding.eventType() != candidate) {
            throw new IllegalArgumentException("event name is already bound to a different Class: "
                    + definition.name() + " ["
                    + identity(binding.eventType()) + " != " + identity(candidate) + "]");
        }
        return binding;
    }

    EventBinding find(String eventName) {
        return bindings.get(eventName);
    }

    void releaseAccepted(EventBinding binding) {
        // 分发计数归零且无订阅才移除绑定，避免在途分发期间允许同名不同 Class 重绑。
        if (binding.dispatchFinished()) {
            bindings.values().removeIf(current -> current == binding && current.isIdle());
        }
    }

    void removeIfIdle(String eventName, EventBinding binding) {
        if (binding.isIdle()) {
            bindings.remove(eventName, binding);
        }
    }

    void pruneIdle() {
        bindings.values().removeIf(EventBinding::isIdle);
    }

    void clear() {
        bindings.clear();
    }

    private static String identity(Class<?> type) {
        return type.getName() + "@" + System.identityHashCode(type);
    }
}
