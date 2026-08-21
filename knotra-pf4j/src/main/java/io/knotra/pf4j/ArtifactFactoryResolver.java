package io.knotra.pf4j;

import java.util.List;
import java.util.Optional;

/**
 * 只读工厂目录解析器。
 *
 * <p>无 token 解析只返回目录元数据；只有携带正确配置 token 的类型化解析才能取得
 * 可挂载句柄。这样 Loader 和诊断调用方不会意外获得可执行工厂。</p>
 */
public interface ArtifactFactoryResolver {

    /** 只返回目录元数据；无 token 结果不能挂载。 */
    Optional<ArtifactFactoryCatalogEntry> resolve(String factoryId);

    /** 返回类型化句柄；token 不匹配会立即失败，而不是延迟到挂载。 */
    <C> Optional<ArtifactFactoryHandle<C>> resolve(String factoryId, Class<C> configType);

    /** 返回所有活跃工厂的目录元数据。 */
    List<ArtifactFactoryCatalogEntry> handles();
}
