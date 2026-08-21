package io.knotra;

import java.util.Objects;

public final record CapabilityKey<T>(String name, Class<T> type) implements Comparable<CapabilityKey<T>> {

    public CapabilityKey {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        if (name.isBlank()) {
            throw new IllegalArgumentException("capability name must not be blank");
        }
        if (type.isPrimitive()) {
            throw new IllegalArgumentException("primitive capability types are not supported");
        }
    }

    public static <T> CapabilityKey<T> of(String name, Class<T> type) {
        return new CapabilityKey<>(name, type);
    }

    public String typeName() {
        return type.getName();
    }

    @Override
    public int compareTo(CapabilityKey<T> other) {
        int byName = name.compareTo(other.name);
        return byName != 0 ? byName : typeName().compareTo(other.typeName());
    }
}
