package io.knotra.internal;

import java.util.concurrent.atomic.AtomicLong;

final class Sequences {
    private static final AtomicLong REGISTRATIONS = new AtomicLong();
    private static final AtomicLong ACTIVATIONS = new AtomicLong();
    private static final AtomicLong HANDLES = new AtomicLong();
    private static final AtomicLong CONTEXTS = new AtomicLong();

    private Sequences() {
    }

    static String registration() {
        return "registration-" + REGISTRATIONS.incrementAndGet();
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
