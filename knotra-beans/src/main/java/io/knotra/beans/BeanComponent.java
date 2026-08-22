package io.knotra.beans;

import io.knotra.ActivationContext;
import io.knotra.Component;
import io.knotra.ComponentDescriptor;

/**
 * Bean Component 外壳：跨 Activation 复用，但不保存任何激活期状态。
 *
 * <p>每次 start 都按固定顺序执行：解析依赖并创建新 Bean（null 直接失败）→
 * 立即登记 cleanup → initializer → 逐个发布输出（null 直接失败）。因此 initializer
 * 或输出失败时，已创建的 Bean 会随本次 Activation 回滚被清理，输出不会部分提交。</p>
 */
final class BeanComponent<C, T> implements Component<C> {

    private final BeanDefinition<C, T> definition;

    BeanComponent(BeanDefinition<C, T> definition) {
        this.definition = definition;
    }

    @Override
    public ComponentDescriptor descriptor() {
        return definition.descriptor();
    }

    @Override
    public void start(ActivationContext context, C config) throws Exception {
        T bean = definition.creator().create(context, config);
        if (bean == null) {
            throw new IllegalStateException(
                    "bean creator returned null for component " + definition.componentId());
        }
        registerCleanup(context, bean);
        BeanInitializer<? super T> initializer = definition.initializer();
        if (initializer != null) {
            initializer.initialize(bean);
        }
        for (BeanOutputStage.Output<T, ?> output : definition.outputs()) {
            publish(context, output, bean);
        }
    }

    private void registerCleanup(ActivationContext context, T bean) {
        String description = "bean:" + definition.componentId();
        BeanOutputStage.Disposal<T> disposal = definition.disposal();
        switch (disposal.mode()) {
            case AUTO -> BeanLifecycles.autoManage(context.lifecycle(), description, bean);
            case UNMANAGED -> {
                // Bean 生命周期由创建方自行管理。
            }
            case CUSTOM_SYNC -> BeanLifecycles.manageSync(
                    context.lifecycle(), description, bean, disposal.syncDisposer());
            case CUSTOM_ASYNC -> BeanLifecycles.manageAsync(
                    context.lifecycle(), description, bean, disposal.asyncDisposer());
        }
    }

    private <B, P> void publish(
            ActivationContext context,
            BeanOutputStage.Output<B, P> output,
            B bean) throws Exception {
        P value = output.mapper() == null
                ? output.key().type().cast(bean)
                : output.mapper().map(bean);
        if (value == null) {
            throw new IllegalStateException(
                    "output '" + output.key().name()
                            + "' produced null for component " + definition.componentId());
        }
        context.provide(output.key(), value);
    }
}
