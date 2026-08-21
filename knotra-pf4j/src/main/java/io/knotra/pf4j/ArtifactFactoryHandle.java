package io.knotra.pf4j;

import io.knotra.ComponentHandle;
import io.knotra.ConfigSchema;
import io.knotra.ContextHandle;

import java.util.Optional;

/**
 * 受管 artifact 中一个工厂的类型化受控挂载句柄。
 *
 * <p>句柄代表活跃 artifact 视图而不是离线快照；drain 或卸载后即失效。宿主必须先
 * 通过类型化解析提供正确配置 token，挂载时还会再次校验配置实例，防止 raw cast
 * 绕过边界。</p>
 */
public interface ArtifactFactoryHandle<C> extends ArtifactFactoryCatalogEntry {

    /** artifact 发现时已通过共享合约校验的宿主/共享配置 token。 */
    Class<C> configType();

    /**
     * 返回活跃工厂的配置 schema；工厂未声明配置时返回空。
     *
     * <p>这是活跃 artifact 视图，不是可脱离生命周期的 schema 快照；artifact 卸载后
     * 必须重新解析新的类型化句柄。</p>
     */
    Optional<ConfigSchema<C>> configSchema();

    /** 挂载新的逻辑组件，并返回 Knotra 的稳定 ComponentHandle。 */
    ComponentHandle<C> mount(ContextHandle context, String mountId, C config);
}
