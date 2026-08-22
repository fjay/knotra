package io.knotra.pf4j;

import io.knotra.ContextHandle;

/**
 * 活跃 artifact 所发布的单个工厂的只读可执行视图。
 *
 * <p>根类型有意仅公开身份标识。挂载操作在无配置和有配置子类型上可用，确保调用方无需提供占位配置。</p>
 */
public interface ArtifactFactoryHandle extends ArtifactFactoryCatalogEntry {

    /** Artifact 发现期的配置契约；绝不被稳定快照视图持有。 */
    Class<?> configType();

    default boolean noConfig() {
        return configType() == io.knotra.NoConfig.class;
    }

    /** 挂载未声明宿主可见配置的工厂。 */
    interface NoConfig extends ArtifactFactoryHandle {

        io.knotra.MountHandle mount(ContextHandle context, String mountId);
    }

    /** 挂载与宿主共享配置契约的工厂。 */
    interface Configured<C> extends ArtifactFactoryHandle {

        /**
         * 将宿主持有的原始配置转换为工厂声明的目标类型。
         *
         * <p>解码器必须返回 {@link #configType()} 的非空实例。此为活跃 artifact 视图，在排空或卸载后不可用。</p>
         */
        C decodeConfig(Object rawConfig);

        io.knotra.ConfiguredMountHandle<C> mount(ContextHandle context, String mountId, C config);
    }
}
