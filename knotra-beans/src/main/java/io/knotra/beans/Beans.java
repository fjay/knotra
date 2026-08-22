package io.knotra.beans;

import io.knotra.CapabilityKey;
import io.knotra.CapabilityRequirement;
import io.knotra.ComponentHandle;
import io.knotra.DynamicCapability;
import io.knotra.KnotraRuntime;
import io.knotra.NoConfig;

import java.util.Objects;
import java.util.Optional;

/**
 * knotra-beans 的入口：把普通 POJO 构造适配为 Activation 拥有的 Bean。
 *
 * <p>业务类不依赖任何 Knotra 类型；本模块只在装配层声明 Capability 依赖、
 * creator、输出与清理策略。所有 builder 均为不可变值对象，每次调用返回新实例，
 * {@link BeanDefinition#build()} 产生的定义同样不可变。</p>
 */
public final class Beans {

    private Beans() {
    }

    /** 声明必需依赖：creator 收到 {@code T} 本身。 */
    public static <T> BeanDependency<T> required(CapabilityKey<T> key) {
        Objects.requireNonNull(key, "key");
        return BeanDependency.of(CapabilityRequirement.required(key), context -> context.require(key));
    }

    /** 声明可选依赖：creator 收到 {@code Optional<T>}，缺失时为空而不是 null。 */
    public static <T> BeanDependency<Optional<T>> optional(CapabilityKey<T> key) {
        Objects.requireNonNull(key, "key");
        return BeanDependency.of(CapabilityRequirement.optional(key), context -> context.find(key));
    }

    /** 声明动态必需依赖：creator 收到显式调用的 {@code DynamicCapability<T>}。 */
    public static <T> BeanDependency<DynamicCapability<T>> dynamicRequired(CapabilityKey<T> key) {
        Objects.requireNonNull(key, "key");
        return BeanDependency.of(
                CapabilityRequirement.dynamicRequired(key),
                context -> context.subscribe(key));
    }

    /** 声明动态可选依赖：creator 收到显式调用的 {@code DynamicCapability<T>}。 */
    public static <T> BeanDependency<DynamicCapability<T>> dynamicOptional(CapabilityKey<T> key) {
        Objects.requireNonNull(key, "key");
        return BeanDependency.of(
                CapabilityRequirement.dynamicOptional(key),
                context -> context.subscribe(key));
    }

    /** 声明动态必需依赖：creator 收到方法级持有调用租约的 interface proxy。 */
    public static <T> BeanDependency<T> dynamicProxyRequired(CapabilityKey<T> key) {
        requireProxyInterface(key);
        return BeanDependency.of(
                CapabilityRequirement.dynamicRequired(key),
                context -> context.subscribe(key).proxy(key.type()));
    }

    /** 声明动态可选依赖：creator 收到方法级持有调用租约的 interface proxy。 */
    public static <T> BeanDependency<T> dynamicProxyOptional(CapabilityKey<T> key) {
        requireProxyInterface(key);
        return BeanDependency.of(
                CapabilityRequirement.dynamicOptional(key),
                context -> context.subscribe(key).proxy(key.type()));
    }

    public static NoConfigBeanBuilder0 component(String componentId) {
        return new NoConfigBeanBuilder0(requireComponentId(componentId));
    }

    /** 开始定义类型化配置 Bean；configType 作为不可变 spec 贯穿整个定义。 */
    public static <C> ConfiguredBeanBuilder0<C> component(String componentId, Class<C> configType) {
        return new ConfiguredBeanBuilder0<>(
                requireComponentId(componentId),
                Objects.requireNonNull(configType, "configType"));
    }

    /** 挂载无配置 Bean；mountId 默认使用 definition 的 componentId。 */
    public static <T> ComponentHandle<NoConfig> mount(
            KnotraRuntime runtime,
            BeanDefinition<NoConfig, T> definition) {
        return mount(runtime, definition, definition.componentId());
    }

    /** 使用显式 mountId 挂载无配置 Bean。 */
    public static <T> ComponentHandle<NoConfig> mount(
            KnotraRuntime runtime,
            BeanDefinition<NoConfig, T> definition,
            String mountId) {
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(mountId, "mountId");
        return runtime.mount(mountId, definition);
    }

    /** 挂载配置 Bean；mountId 默认使用 definition 的 componentId。 */
    public static <C, T> ComponentHandle<C> mount(
            KnotraRuntime runtime,
            BeanDefinition<C, T> definition,
            C config) {
        return mount(runtime, definition, definition.componentId(), config);
    }

    /** 使用显式 mountId 挂载配置 Bean。 */
    public static <C, T> ComponentHandle<C> mount(
            KnotraRuntime runtime,
            BeanDefinition<C, T> definition,
            String mountId,
            C config) {
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(mountId, "mountId");
        return runtime.mount(mountId, definition, config);
    }

    static String requireComponentId(String componentId) {
        Objects.requireNonNull(componentId, "componentId");
        String trimmed = componentId.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("componentId must not be blank");
        }
        return trimmed;
    }

    private static void requireProxyInterface(CapabilityKey<?> key) {
        Objects.requireNonNull(key, "key");
        if (!key.type().isInterface()) {
            throw new IllegalArgumentException(
                    "dynamic proxy capability must be an interface: " + key.typeName());
        }
    }
}
