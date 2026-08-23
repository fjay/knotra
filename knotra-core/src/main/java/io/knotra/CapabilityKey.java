package io.knotra;

import java.util.Objects;

/**
 * 类型化 Capability 的标识，由名称与合约 Java 类型组成。
 *
 * <p>Capability 是类型化的命名值。名称与类型的身份只在仍有 live 注册或组件需求占用期间固定；
 * 全部释放后，该名称可由新 ClassLoader 加载的同名合约类型重新绑定，从而避免已发布内核状态
 * 永久持有旧插件类型。本记录不可变且具有值相等语义；自然顺序先按名称、再按类型二进制名排序，
 * 保证输出稳定。紧凑构造函数要求名称非空非空白，类型非空且不支持 primitive。</p>
 */
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

    /** 使用契约类型的二进制名称作为能力名称。 */
    public static <T> CapabilityKey<T> of(Class<T> type) {
        return new CapabilityKey<>(type.getName(), type);
    }

    /** 返回合约 Java 类型的二进制名，用于快照、诊断与稳定排序。 */
    public String typeName() {
        return type.getName();
    }

    /** 先按名称、再按类型名比较；用类型名字符串排序，保证顺序与具体 Class 实例无关。 */
    @Override
    public int compareTo(CapabilityKey<T> other) {
        int byName = name.compareTo(other.name);
        return byName != 0 ? byName : typeName().compareTo(other.typeName());
    }
}
