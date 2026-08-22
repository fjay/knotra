package io.knotra.beans;

import io.knotra.CapabilityKey;
import io.knotra.CapabilityRequirement;
import io.knotra.Component;
import io.knotra.ComponentDescriptor;
import io.knotra.ComponentFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 冻结后的 Bean 定义：一个显式稳定 ID 的 {@link ComponentFactory}。
 *
 * <p>定义本身不可变；每次挂载由 {@link #create()} 返回一个无状态 Component 外壳，
 * 真正的业务对象在每次 Activation 的 start 中新建，绝不缓存在外壳里。
 * descriptor 由依赖列表生成，factoryId 与 componentId 均使用显式声明的稳定 ID。</p>
 *
 * @param <C> 组件配置类型
 * @param <T> Bean 类型
 */
public final class BeanDefinition<C, T> implements ComponentFactory<C> {

    private final String componentId;
    private final Class<C> configType;
    private final List<BeanDependency<?>> dependencies;
    private final ExpertBeanCreator<C, T> creator;
    private final List<BeanOutputStage.Output<T, ?>> outputs;
    private final BeanInitializer<? super T> initializer;
    private final ConfigNormalizer<C> normalizer;
    private final BeanOutputStage.Disposal<T> disposal;
    private final ComponentDescriptor descriptor;

    BeanDefinition(
            String componentId,
            Class<C> configType,
            List<BeanDependency<?>> dependencies,
            ExpertBeanCreator<C, T> creator,
            List<BeanOutputStage.Output<T, ?>> outputs,
            BeanInitializer<? super T> initializer,
            ConfigNormalizer<C> normalizer,
            BeanOutputStage.Disposal<T> disposal) {
        this.componentId = Beans.requireComponentId(componentId);
        this.configType = Objects.requireNonNull(configType, "configType");
        this.dependencies = validateDependencies(dependencies);
        this.creator = Objects.requireNonNull(creator, "creator");
        this.outputs = validateOutputs(outputs);
        this.initializer = initializer;
        this.normalizer = normalizer;
        this.disposal = Objects.requireNonNull(disposal, "disposal");
        this.descriptor = ComponentDescriptor.named(
                this.componentId,
                this.dependencies.stream()
                        .map(BeanDependency::requirement)
                        .toArray(CapabilityRequirement[]::new));
    }

    /**
     * Expert 入口：直接基于配置类型、依赖列表与 {@link io.knotra.ActivationContext} creator 构建定义，
     * 供未来的注解处理器或框架集成使用；普通业务代码应使用 {@code Beans.component(...)}。
     */
    public static <C, T> BeanOutputStage<C, T> expert(
            String componentId,
            Class<C> configType,
            List<BeanDependency<?>> dependencies,
            ExpertBeanCreator<C, T> creator) {
        return BeanOutputStage.of(
                Beans.requireComponentId(componentId),
                configType,
                dependencies,
                creator);
    }

    /** 显式声明的稳定组件 ID。 */
    public String componentId() {
        return componentId;
    }

    /** 本定义接受的精确配置运行时类型；NoConfig 路径固定为 {@code NoConfig.class}。 */
    public Class<C> configType() {
        return configType;
    }
    /** 依赖声明列表（不可变），供 processor 与框架集成读取。 */
    public List<BeanDependency<?>> dependencies() {
        return dependencies;
    }

    /** 输出 Capability 名称列表（不可变），按声明顺序排列。 */
    public List<String> outputNames() {
        return outputs.stream().map(output -> output.key().name()).toList();
    }

    /** 由依赖生成的静态组件声明。 */
    public ComponentDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public String factoryId() {
        return componentId;
    }

    @Override
    public Component<C> create() {
        return new BeanComponent<>(this);
    }

    @Override
    public C normalizeConfig(C config) throws Exception {
        Objects.requireNonNull(config, "config");
        if (!configType.isInstance(config)) {
            throw new IllegalStateException(
                    "config type mismatch for component " + componentId
                            + ": expected " + configType.getName()
                            + ", got " + config.getClass().getName());
        }
        C typed = configType.cast(config);
        if (normalizer == null) {
            return typed;
        }
        C normalized = normalizer.normalize(typed);
        if (normalized == null) {
            throw new IllegalStateException(
                    "config normalizer returned null for component " + componentId);
        }
        if (!configType.isInstance(normalized)) {
            throw new IllegalStateException(
                    "config normalizer returned invalid config type for component " + componentId
                            + ": expected " + configType.getName()
                            + ", got " + normalized.getClass().getName());
        }
        return configType.cast(normalized);
    }

    /** 输出 Capability Key 列表（不可变），按声明顺序排列。 */
    public List<CapabilityKey<?>> outputKeys() {
        List<CapabilityKey<?>> keys = new ArrayList<>(outputs.size());
        for (BeanOutputStage.Output<T, ?> output : outputs) {
            keys.add(output.key());
        }
        return List.copyOf(keys);
    }

    public String toString() {
        return "BeanDefinition[componentId=" + componentId
                + ", configType=" + configType.getName()
                + ", outputs=" + outputNames()
                + ", dependencies=" + dependencies.size() + "]";
    }

    ExpertBeanCreator<C, T> creator() {
        return creator;
    }

    List<BeanOutputStage.Output<T, ?>> outputs() {
        return outputs;
    }

    BeanInitializer<? super T> initializer() {
        return initializer;
    }

    BeanOutputStage.Disposal<T> disposal() {
        return disposal;
    }

    private static List<BeanDependency<?>> validateDependencies(List<BeanDependency<?>> dependencies) {
        Objects.requireNonNull(dependencies, "dependencies");
        for (BeanDependency<?> dependency : dependencies) {
            Objects.requireNonNull(dependency, "dependency");
        }
        return List.copyOf(dependencies);
    }

    private static <T> List<BeanOutputStage.Output<T, ?>> validateOutputs(
            List<BeanOutputStage.Output<T, ?>> outputs) {
        Objects.requireNonNull(outputs, "outputs");
        for (int index = 0; index < outputs.size(); index++) {
            BeanOutputStage.Output<T, ?> output =
                    Objects.requireNonNull(outputs.get(index), "output");
            for (int previous = 0; previous < index; previous++) {
                if (outputs.get(previous).key().name().equals(output.key().name())) {
                    throw new IllegalArgumentException(
                            "duplicate output name: " + output.key().name());
                }
            }
        }
        return List.copyOf(outputs);
    }
}
