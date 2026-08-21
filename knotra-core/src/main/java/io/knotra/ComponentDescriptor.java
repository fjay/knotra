package io.knotra;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** 组件的静态依赖声明；显示 ID 可省略并由 Runtime 使用 factory ID 固化。 */
public record ComponentDescriptor(
        String componentId,
        Set<CapabilityRequirement> requirements) {

    public ComponentDescriptor {
        componentId = componentId == null ? "" : componentId.trim();
        requirements = CapabilityRequirement.freeze(requirements);
    }

    /** 创建使用默认组件 ID 的声明。 */
    public static ComponentDescriptor of(CapabilityRequirement... requirements) {
        return new ComponentDescriptor("", requirements == null ? Set.of() : Set.of(requirements));
    }

    /** 创建带显式显示 ID 的声明。 */
    public static ComponentDescriptor named(
            String componentId,
            CapabilityRequirement... requirements) {
        if (componentId == null || componentId.isBlank()) {
            throw new IllegalArgumentException("componentId must not be blank");
        }
        return new ComponentDescriptor(
                componentId,
                requirements == null ? Set.of() : Set.of(requirements));
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
