package io.knotra.pf4j;

import java.util.List;
import java.util.Optional;

/**
 * 活跃 artifact 所发布的工厂目录。
 *
 * <p>{@link #list()} 和 {@link #find(String)} 仅公开稳定元数据。根可执行视图不能直接挂载；
 * 请使用无配置或类型化配置解析方法。</p>
 */
public interface ArtifactFactoryCatalog {

    /** 返回所有活跃工厂的目录元数据。 */
    List<ArtifactFactoryCatalogEntry> list();

    /** 根据工厂 ID 查找目录元数据；返回结果不能直接挂载组件。 */
    Optional<ArtifactFactoryCatalogEntry> find(String factoryId);

    /** 返回供诊断和动态工具使用的非挂载可执行视图。 */
    Optional<ArtifactFactoryHandle> resolve(String factoryId);

    /** 返回挂载调用不暴露 {@code NoConfig} 的无配置工厂。 */
    Optional<ArtifactFactoryHandle.NoConfig> resolveNoConfig(String factoryId);

    /** 返回类型化的已配置工厂；Token 不匹配时将立即失败。 */
    <C> Optional<ArtifactFactoryHandle.Configured<C>> resolve(String factoryId, Class<C> configType);
}
