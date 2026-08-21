package io.knotra;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 组件实例的静态自描述：组件标识与依赖需求集合。
 *
 * <p>记录不可变；紧凑构造函数要求 componentId 非空白，并将需求集合冻结为不可变副本，
 * 同一 Capability 名称只能出现一次。合约类型不支持 primitive。
 */
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

    /** 返回按稳定顺序排序的需求列表，用于激活处理与快照输出。 */
    public List<CapabilityRequirement> sortedRequirements() {
        return requirements.stream().sorted().toList();
    }

    /** 按 Capability 名称查找需求声明；未声明时返回空。 */
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

    /** 校验描述符并返回错误信息列表；空列表表示通过。 */
    public List<String> validate() {
        return requirements.stream()
                .map(CapabilityRequirement::key)
                .filter(key -> key.type().isPrimitive())
                .map(key -> "primitive capability type is not supported: " + key.name())
                .sorted(Comparator.naturalOrder())
                .toList();
    }
}
