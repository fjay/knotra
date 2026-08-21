package io.knotra;

public interface Component<C> {
    ComponentDescriptor descriptor();

    void start(ActivationContext context, C config) throws Exception;
}
