package io.knotra;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 组件对单个 Capability 的依赖需求声明，由 {@link CapabilityKey} 与绑定模式组成。
 *
 * <p>REQUIRED 与 OPTIONAL 需求同属一个 BindingSet：REQUIRED 绑定的注册变化会重新激活消费方，
 * OPTIONAL 提供方的出现或消失同样产生新的 Activation。记录不可变且具有值相等语义；
 * 同一组件描述符内 Capability 名称不可重复（由 {@link ComponentDescriptor} 冻结需求集合时保证）。
 */
public final record CapabilityRequirement(CapabilityKey<?> key, Mode mode) implements Comparable<CapabilityRequirement> {

    /**
     * 绑定模式：REQUIRED 为必需绑定，缺失时组件保持 WAITING，不进入启动，
     * 绑定变化必然触发重新激活；OPTIONAL 为可选绑定，缺失时仍可启动
     * （find 返回空），出现或消失同样产生新的 Activation。
     */
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

    /** 先按 Capability 名称、类型名，再按模式排序，保证稳定顺序。 */
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
