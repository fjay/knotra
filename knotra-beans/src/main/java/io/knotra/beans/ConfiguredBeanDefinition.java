package io.knotra.beans;

import io.knotra.CapabilityKey;
import io.knotra.ComponentDescriptor;
import io.knotra.ComponentFactory;
import io.knotra.ConfiguredMountHandle;
import io.knotra.KnotraRuntime;

import io.knotra.MountOptions;

import java.util.List;
import java.util.Objects;

/** 带类型化配置的 Activation 托管 Bean 的不可变可复用定义。 */
public final class ConfiguredBeanDefinition<C, T> {

    private final BeanDefinitionSupport<C, T> support;

    ConfiguredBeanDefinition(BeanDefinitionSupport<C, T> support) {
        this.support = Objects.requireNonNull(support, "support");
    }

    public String componentId() {
        return support.componentId();
    }

    public Class<C> configType() {
        return support.configType();
    }

    public List<BeanDependency<?>> dependencies() {
        return support.dependencies();
    }

    public List<String> outputNames() {
        return support.outputNames();
    }

    public List<CapabilityKey<?>> outputKeys() {
        return support.outputKeys();
    }

    public ComponentDescriptor descriptor() {
        return support.descriptor();
    }

    public String factoryId() {
        return support.factoryId();
    }

    public ConfiguredMountHandle<C> mount(KnotraRuntime runtime, C config) {
        return mount(runtime, componentId(), config);
    }

    public ConfiguredMountHandle<C> mount(
            KnotraRuntime runtime,
            String mountId,
            C config) {
        return Beans.mount(runtime, this, mountId, config);
    }

    public ConfiguredMountHandle<C> mount(KnotraRuntime runtime, C config, MountOptions options) {
        return mount(runtime, componentId(), config, options);
    }

    public ConfiguredMountHandle<C> mount(
            KnotraRuntime runtime,
            String mountId,
            C config,
            MountOptions options) {
        return Beans.mount(runtime, this, mountId, config, options);
    }


    ComponentFactory<C> asFactory() {
        return support;
    }

    @Override
    public String toString() {
        return support.description();
    }
}
