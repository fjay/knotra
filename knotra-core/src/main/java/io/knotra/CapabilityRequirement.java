package io.knotra;

import java.util.Objects;
/**
 * 组件对单个 Capability 的依赖需求声明，由 {@link CapabilityKey}、启动模式和绑定语义组成。
 *
 * <p>REQUIRED 与 OPTIONAL 控制首次启动条件；PINNED 绑定的注册变化会重新激活消费方。
 * DYNAMIC 绑定不属于固定 BindingSet 身份，消费方通过 {@link DynamicCapability} 在调用时解析
 * 已提交 provider。记录不可变且具有值相等语义；同一组件描述符内 Capability 名称不可重复。
 */
public final record CapabilityRequirement(
        CapabilityKey<?> key,
        Mode mode,
        CapabilityBinding binding) implements Comparable<CapabilityRequirement> {

    /**
     * 启动模式：REQUIRED 为必需依赖，缺失时组件保持 WAITING；OPTIONAL 为可选依赖。
     */
    public enum Mode { REQUIRED, OPTIONAL }

    /** Capability 在 Activation 中的绑定语义。 */
    public enum CapabilityBinding { PINNED, DYNAMIC }

    public CapabilityRequirement {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(binding, "binding");
    }

    public static CapabilityRequirement required(CapabilityKey<?> key) {
        return new CapabilityRequirement(key, Mode.REQUIRED, CapabilityBinding.PINNED);
    }

    public static CapabilityRequirement optional(CapabilityKey<?> key) {
        return new CapabilityRequirement(key, Mode.OPTIONAL, CapabilityBinding.PINNED);
    }

    public static CapabilityRequirement dynamicRequired(CapabilityKey<?> key) {
        return new CapabilityRequirement(key, Mode.REQUIRED, CapabilityBinding.DYNAMIC);
    }

    public static CapabilityRequirement dynamicOptional(CapabilityKey<?> key) {
        return new CapabilityRequirement(key, Mode.OPTIONAL, CapabilityBinding.DYNAMIC);
    }

    /** 先按 Capability 名称、类型名、模式和绑定语义排序，保证稳定顺序。 */
    @Override
    public int compareTo(CapabilityRequirement other) {
        int byName = key().name().compareTo(other.key().name());
        if (byName != 0) {
            return byName;
        }
        int byType = key().typeName().compareTo(other.key().typeName());
        if (byType != 0) {
            return byType;
        }
        int byMode = mode().compareTo(other.mode());
        return byMode != 0 ? byMode : binding().compareTo(other.binding());
    }
}
