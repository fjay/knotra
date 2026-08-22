package io.knotra;

import java.util.Objects;

/**
 * 无公开配置契约组件的挂载工厂 SPI 适配类型。
 *
 * <p>Simple API 的挂载重载直接接收该类型，使日常业务开发免受 {@link NoConfig} 占位泛型的干扰。
 * 底层或高级工厂实现可通过 {@link #adapt(ComponentFactory)} 便捷桥接。</p>
 */
public interface MountFactory extends ComponentFactory<NoConfig> {

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
}
