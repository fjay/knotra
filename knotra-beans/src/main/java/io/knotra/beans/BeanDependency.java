package io.knotra.beans;

import io.knotra.ActivationContext;
import io.knotra.CapabilityRequirement;

import java.util.Objects;

/**
 * 一个类型安全的依赖声明：需求模式 + 从当前 ActivationContext 解析值的方式。
 *
 * <p>实例只能通过 {@link Beans} 的工厂方法创建。需求与解析规则同时冻结，
 * 避免调用方拼装出可绕过 Runtime 校验的依赖对象。</p>
 *
 * @param <T> 传给 creator 的依赖值类型；可选依赖为 {@code Optional<提供方类型>}
 */
public final class BeanDependency<T> {

    private final CapabilityRequirement requirement;
    private final Resolver<T> resolver;

    private BeanDependency(CapabilityRequirement requirement, Resolver<T> resolver) {
        this.requirement = Objects.requireNonNull(requirement, "requirement");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
    }

    static <T> BeanDependency<T> of(CapabilityRequirement requirement, Resolver<T> resolver) {
        return new BeanDependency<>(requirement, resolver);
    }

    /** 从激活上下文解析该依赖的当前绑定值。 */
    @FunctionalInterface
    interface Resolver<T> {
        T resolve(ActivationContext context);
    }

    /** 依赖声明；实例不可变，返回同一个对象。 */
    public CapabilityRequirement requirement() {
        return requirement;
    }

    /** 依赖的 Capability 名称。 */
    public String name() {
        return requirement.key().name();
    }

    Resolver<T> resolver() {
        return resolver;
    }

    T resolve(ActivationContext context) {
        return resolver.resolve(Objects.requireNonNull(context, "context"));
    }
}
