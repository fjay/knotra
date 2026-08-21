package io.knotra;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final record CapabilityRequirement(CapabilityKey<?> key, Mode mode) implements Comparable<CapabilityRequirement> {

    public enum Mode { REQUIRED, OPTIONAL }

    public CapabilityRequirement {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(mode, "mode");
    }

    public static CapabilityRequirement required(CapabilityKey<?> key) {
        return new CapabilityRequirement(key, Mode.REQUIRED);
    }

    public static CapabilityRequirement optional(CapabilityKey<?> key) {
        return new CapabilityRequirement(key, Mode.OPTIONAL);
    }

    static Set<CapabilityRequirement> freeze(Collection<CapabilityRequirement> requirements) {
        Objects.requireNonNull(requirements, "requirements");
        Map<String, CapabilityRequirement> byName = new LinkedHashMap<>();
        for (CapabilityRequirement requirement : requirements) {
            Objects.requireNonNull(requirement, "requirement");
            CapabilityRequirement previous = byName.putIfAbsent(requirement.key().name(), requirement);
            if (previous != null) {
                throw new IllegalArgumentException("duplicate requirement key: " + requirement.key().name());
            }
        }
        return Set.copyOf(new LinkedHashSet<>(byName.values()));
    }

    @Override
    public int compareTo(CapabilityRequirement other) {
        int byName = key().name().compareTo(other.key().name());
        if (byName != 0) {
            return byName;
        }
        int byType = key().typeName().compareTo(other.key().typeName());
        return byType != 0 ? byType : mode().compareTo(other.mode());
    }
}
