package io.knotra;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public final record ComponentDescriptor(
        String componentId,
        Set<CapabilityRequirement> requirements) {

    public ComponentDescriptor {
        if (componentId == null || componentId.isBlank()) {
            throw new IllegalArgumentException("componentId must not be blank");
        }
        requirements = CapabilityRequirement.freeze(requirements);
    }

    public static ComponentDescriptor of(String componentId, CapabilityRequirement... requirements) {
        return new ComponentDescriptor(componentId, requirements == null ? Set.of() : Set.of(requirements));
    }

    public List<CapabilityRequirement> sortedRequirements() {
        return requirements.stream().sorted().toList();
    }

    public Optional<CapabilityRequirement> requirement(CapabilityKey<?> key) {
        return requirements.stream()
                .filter(item -> item.key().name().equals(key.name()))
                .findFirst();
    }

    Map<String, CapabilityRequirement> byName() {
        return requirements.stream().collect(Collectors.toMap(
                item -> item.key().name(),
                item -> item,
                (left, right) -> left,
                java.util.TreeMap::new));
    }

    public List<String> validate() {
        return requirements.stream()
                .map(CapabilityRequirement::key)
                .filter(key -> key.type().isPrimitive())
                .map(key -> "primitive capability type is not supported: " + key.name())
                .sorted(Comparator.naturalOrder())
                .toList();
    }
}
