package io.knotra.beans;

import io.knotra.ActivationContext;
import io.knotra.CapabilityKey;
import io.knotra.CapabilityRequirement;
import io.knotra.Component;
import io.knotra.ComponentDescriptor;
import io.knotra.ComponentFactory;
import io.knotra.LifecycleScope;
import io.knotra.NoConfig;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** Bean 定义的唯一构造校验点和运行时实现支撑类。 */
class BeanDefinitionSupport<C, T> implements ComponentFactory<C> {

    private final String componentId;
    private final Class<C> configType;
    private final List<BeanDependency<?>> dependencies;
    private final Beans.ConfigExpertCreator<C, T> creator;
    private final List<BeanOutput<T, ?>> outputs;
    private final Beans.Initializer<? super T> initializer;
    private final Beans.Normalizer<C> normalizer;
    private final BeanDisposal<T> disposal;
    private final ComponentDescriptor descriptor;

    BeanDefinitionSupport(
            String componentId,
            Class<C> configType,
            List<BeanDependency<?>> dependencies,
            Beans.ConfigExpertCreator<C, T> creator,
            List<BeanOutput<T, ?>> outputs,
            Beans.Initializer<? super T> initializer,
            Beans.Normalizer<C> normalizer,
            BeanDisposal<T> disposal) {
        this.componentId = Beans.requireComponentId(componentId);
        this.configType = Objects.requireNonNull(configType, "configType");
        this.creator = Objects.requireNonNull(creator, "creator");
        this.disposal = Objects.requireNonNull(disposal, "disposal");

        LinkedHashSet<String> dependencyNames = new LinkedHashSet<>();
        this.dependencies = copyDependencies(componentId, dependencies, dependencyNames);
        LinkedHashSet<String> outputNames = new LinkedHashSet<>();
        this.outputs = copyOutputs(componentId, outputs, outputNames);
        rejectNameOverlap(componentId, dependencyNames, outputNames);
        this.initializer = initializer;
        this.normalizer = normalizer;
        this.descriptor = ComponentDescriptor.named(
                this.componentId,
                this.dependencies.stream()
                        .map(BeanDependency::requirement)
                        .toArray(CapabilityRequirement[]::new));
    }

    String componentId() {
        return componentId;
    }

    Class<C> configType() {
        return configType;
    }

    List<BeanDependency<?>> dependencies() {
        return dependencies;
    }

    List<String> outputNames() {
        return outputs.stream().map(output -> output.key().name()).toList();
    }

    List<CapabilityKey<?>> outputKeys() {
        List<CapabilityKey<?>> keys = new java.util.ArrayList<>(outputs.size());
        for (BeanOutput<T, ?> output : outputs) {
            keys.add(output.key());
        }
        return List.copyOf(keys);
    }

    ComponentDescriptor descriptor() {
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

    void start(ActivationContext context, C config) throws Exception {
        T bean = creator.create(context, config);
        if (bean == null) {
            throw new IllegalStateException(
                    "bean creator returned null for component " + componentId);
        }
        registerCleanup(context, bean);
        if (initializer != null) {
            initializer.initialize(bean);
        }
        for (BeanOutput<T, ?> output : outputs) {
            publish(context, output, bean);
        }
    }

    private <P> void publish(
            ActivationContext context,
            BeanOutput<T, P> output,
            T bean) throws Exception {
        context.provide(output.key(), output.value(bean));
    }

    private void registerCleanup(ActivationContext context, T bean) {
        String description = "bean:" + componentId;
        switch (disposal.mode()) {
            case AUTO -> autoManage(context.lifecycle(), description, bean);
            case UNMANAGED -> {
            }
            case CUSTOM_SYNC -> context.lifecycle().onClose(description, () -> {
                try {
                    disposal.syncDisposer().dispose(bean);
                } catch (Exception error) {
                    throw new IllegalStateException("bean disposer failed: " + error, error);
                }
            });
            case CUSTOM_ASYNC -> context.lifecycle().onCloseAsync(description, () -> {
                try {
                    return disposal.asyncDisposer().disposeAsync(bean);
                } catch (Exception error) {
                    CompletableFuture<Void> failed = new CompletableFuture<>();
                    failed.completeExceptionally(error);
                    return failed;
                }
            });
        }
    }

    private static <T> void autoManage(
            LifecycleScope scope,
            String description,
            T bean) {
        if (bean instanceof io.knotra.AsyncCloseable async) {
            scope.manageAsync(description, async);
        } else if (bean instanceof AutoCloseable closeable) {
            scope.manage(description, closeable);
        }
    }

    String description() {
        return "BeanDefinition[componentId=" + componentId
                + ", configType=" + configType.getName()
                + ", outputs=" + outputNames()
                + ", dependencies=" + dependencies.size() + "]";
    }

    private static List<BeanDependency<?>> copyDependencies(
            String componentId,
            List<BeanDependency<?>> dependencies,
            Set<String> names) {
        Objects.requireNonNull(dependencies, "dependencies");
        List<BeanDependency<?>> result = List.copyOf(dependencies);
        for (BeanDependency<?> dependency : result) {
            Objects.requireNonNull(dependency, "dependency");
            if (!names.add(dependency.name())) {
                throw new IllegalArgumentException("duplicate dependency name '" + dependency.name()
                        + "' for component " + componentId);
            }
        }
        return result;
    }

    private static <T> List<BeanOutput<T, ?>> copyOutputs(
            String componentId,
            List<BeanOutput<T, ?>> outputs,
            Set<String> names) {
        Objects.requireNonNull(outputs, "outputs");
        List<BeanOutput<T, ?>> result = List.copyOf(outputs);
        for (BeanOutput<T, ?> output : result) {
            Objects.requireNonNull(output, "output");
            String name = output.key().name();
            if (!names.add(name)) {
                throw new IllegalArgumentException("duplicate output name '" + name
                        + "' for component " + componentId);
            }
        }
        return result;
    }

    private static void rejectNameOverlap(
            String componentId,
            Set<String> dependencyNames,
            Set<String> outputNames) {
        Set<String> overlap = new LinkedHashSet<>(dependencyNames);
        overlap.retainAll(outputNames);
        if (!overlap.isEmpty()) {
            throw new IllegalArgumentException("dependency and output names conflict on "
                    + overlap + " for component " + componentId);
        }
    }
}
