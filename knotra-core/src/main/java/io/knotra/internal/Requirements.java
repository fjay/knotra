package io.knotra.internal;

import io.knotra.CapabilityRequirement;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Capability 需求声明的内部冻结工具。 */
public final class Requirements {
    private Requirements() {
    }

    /** 按 Capability 名称去重并冻结为不可变集合，重复声明立即拒绝。 */
    public static Set<CapabilityRequirement> freeze(Collection<CapabilityRequirement> requirements) {
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
}
