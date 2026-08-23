package io.knotra;

import java.util.Objects;

/**
 * 无公开配置契约组件的挂载工厂 SPI 适配类型。
 *
 * <p>Simple API 的挂载重载直接接收该类型，使日常业务开发免受 {@link NoConfig} 占位泛型的干扰。
 * 底层或高级工厂实现可通过 {@link #adapt(ComponentFactory)} 便捷桥接。</p>
 */
public interface MountFactory extends ComponentFactory<NoConfig> {

    /**
     * 以函数式启动回调创建无配置挂载工厂。
     *
     * <p>这是 SPI 与测试的可视入口：每次 {@code create()} 都返回独立的组件适配器，
     * 但不会推断来源、登记清理动作或包装启动异常。业务组件应优先使用 Beans 或 Spring
     * 声明生命周期；这里保持最小适配语义，便于直接验证 Core 挂载契约。</p>
     *
     * @param factoryId 非空白工厂标识，前后空白会被去除
     * @param descriptor 组件静态声明，原样透传给 Runtime
     * @param start 每个组件 Activation 的启动回调
     */
    static MountFactory of(
            String factoryId,
            ComponentDescriptor descriptor,
            Start start) {
        String identity = requireFactoryId(factoryId);
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(start, "start");
        return new MountFactory() {
            @Override
            public String factoryId() {
                return identity;
            }

            @Override
            public Component<NoConfig> create() {
                return new Component<>() {
                    @Override
                    public ComponentDescriptor descriptor() {
                        return descriptor;
                    }

                    @Override
                    public void start(ActivationContext context, NoConfig config) throws Exception {
                        start.start(context);
                    }
                };
            }
        };
    }

    /** 将常规的 ComponentFactory&lt;NoConfig&gt; 包装适配为 MountFactory。 */
    static MountFactory adapt(ComponentFactory<NoConfig> factory) {
        Objects.requireNonNull(factory, "factory");
        return new MountFactory() {
            @Override
            public String factoryId() {
                return factory.factoryId();
            }

            @Override
            public Component<NoConfig> create() {
                return factory.create();
            }

            @Override
            public NoConfig normalizeConfig(NoConfig config) throws Exception {
                return factory.normalizeConfig(config);
            }
        };
    }

    /** 无配置组件的启动回调。 */
    @FunctionalInterface
    interface Start {
        /** 执行一次 Activation 启动；抛出异常会使该 Activation 失败。 */
        void start(ActivationContext context) throws Exception;
    }

    private static String requireFactoryId(String factoryId) {
        if (factoryId == null || factoryId.isBlank()) {
            throw new IllegalArgumentException("factoryId must not be blank");
        }
        return factoryId.trim();
    }
}
