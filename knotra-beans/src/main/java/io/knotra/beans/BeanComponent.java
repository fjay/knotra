package io.knotra.beans;

import io.knotra.ActivationContext;
import io.knotra.Component;
import io.knotra.ComponentDescriptor;

final class BeanComponent<C, T> implements Component<C> {

    private final BeanDefinitionSupport<C, T> definition;

    BeanComponent(BeanDefinitionSupport<C, T> definition) {
        this.definition = definition;
    }

    @Override
    public ComponentDescriptor descriptor() {
        return definition.descriptor();
    }

    @Override
    public void start(ActivationContext context, C config) throws Exception {
        definition.start(context, config);
    }
}
