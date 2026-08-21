package io.knotra.pf4j;

/**
 * 一个活跃 artifact 工厂的只读目录元数据。
 *
 * <p>无 token 的目录刻意只暴露稳定文本，不能挂载组件、归一化配置，也不能保留
 * 可执行工厂或配置 schema。</p>
 */
public interface ArtifactFactoryCatalogEntry {

    String artifactId();

    String artifactVersion();

    String artifactPath();

    String factoryId();

    /** 与二进制无关的配置 token 名称，仅用于目录展示与诊断。 */
    String configTypeName();
}
