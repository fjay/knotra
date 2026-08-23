package io.knotra.internal;

import java.util.concurrent.atomic.AtomicLong;


/**
 * 进程级 ID 序列，用于保证 Runtime 内部实体 ID 不依赖对象相等性。
 *
 * <p>序列跨多个 Runtime 实例全局单调递增；Context 名称会被规范化后拼入 ID，
 * 以便诊断可读，但唯一性仍来自序列值。</p>
 */
final class Sequences {
    private static final AtomicLong REGISTRATIONS = new AtomicLong();
    private static final AtomicLong ACTIVATIONS = new AtomicLong();
    private static final AtomicLong HANDLES = new AtomicLong();
    private static final AtomicLong PUBLICATION_SLOTS = new AtomicLong();
    private static final AtomicLong CONTEXTS = new AtomicLong();

    private Sequences() {
    }

    static String registration() {
        return "registration-" + REGISTRATIONS.incrementAndGet();
    }

    static String publicationSlot() {
        return "publication-slot-" + PUBLICATION_SLOTS.incrementAndGet();
    }
    static String activation() {
        return "activation-" + ACTIVATIONS.incrementAndGet();
    }

    static String handle() {
        return "handle-" + HANDLES.incrementAndGet();
    }

    static String context(String name) {
        return "ctx-" + normalize(name) + "-" + CONTEXTS.incrementAndGet();
    }

    private static String normalize(String name) {
        String safe = name == null ? "context" : name.trim().replaceAll("[^A-Za-z0-9_-]", "-");
        return safe.isBlank() ? "context" : safe;
    }
}
