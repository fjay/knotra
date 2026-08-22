package io.knotra.beans;

import io.knotra.CapabilityKey;
import io.knotra.ComponentDescriptor;
import io.knotra.KnotraRuntime;
import io.knotra.MountFactory;
import io.knotra.MountHandle;

import io.knotra.MountOptions;

import java.util.List;
import java.util.Objects;

/** 无运行时配置的 Activation 托管 Bean 的不可变可复用定义。 */
public final class BeanDefinition<T> {

    private final NoConfigBeanDefinitionSupport<T> support;

    BeanDefinition(NoConfigBeanDefinitionSupport<T> support) {
        this.support = Objects.requireNonNull(support, "support");
    }

    public String componentId() {
        return support.componentId();
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

    public MountHandle mount(KnotraRuntime runtime) {
        return mount(runtime, componentId());
    }

    public MountHandle mount(KnotraRuntime runtime, String mountId) {
        return Beans.mount(runtime, this, mountId);
    }

    public MountHandle mount(KnotraRuntime runtime, MountOptions options) {
        return mount(runtime, componentId(), options);
    }

    public MountHandle mount(KnotraRuntime runtime, String mountId, MountOptions options) {
        return Beans.mount(runtime, this, mountId, options);
    }


    MountFactory asFactory() {
        return support;
    }

    @Override
    public String toString() {
        return support.description();
    }
}
