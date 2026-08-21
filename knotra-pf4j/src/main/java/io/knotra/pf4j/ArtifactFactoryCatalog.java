package io.knotra.pf4j;

import java.util.List;
import java.util.Optional;

/**
 * 活跃 artifact 工厂的唯一目录入口。
 *
 * <p>{@link #find(String)} 只暴露稳定目录元数据；{@code resolve} 返回可执行句柄。
 * 无 token 的 wildcard 解析用于动态工具与诊断，类型化宿主代码应优先使用
 * {@link #resolve(String, Class)}。目录读取是同步协调操作，可能等待正在执行的 load/drain 状态发布。</p>
 */
public interface ArtifactFactoryCatalog {

    /** 返回所有活跃工厂的目录元数据。 */
    List<ArtifactFactoryCatalogEntry> list();

    /** 按工厂 ID 查找目录元数据；结果不能挂载组件。 */
    Optional<ArtifactFactoryCatalogEntry> find(String factoryId);

    /** 返回 wildcard 可执行句柄；找不到工厂时返回空。 */
    Optional<ArtifactFactoryHandle<?>> resolve(String factoryId);

    /** 返回类型化句柄；token 不匹配会立即失败，而不是延迟到挂载。 */
    <C> Optional<ArtifactFactoryHandle<C>> resolve(String factoryId, Class<C> configType);
}
