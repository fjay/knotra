package io.knotra.pf4j;

/**
 * 一个活跃 artifact 工厂的只读目录元数据。
 *
 * <p>{@code list/find} 返回的目录视图只暴露稳定文本，不持有可执行 factory、decoder、
 * config Class 或 ClassLoader。Executable handle 通过 {@link ArtifactFactoryCatalog#resolve(String)} 获取。</p>
 */
public interface ArtifactFactoryCatalogEntry {

    String artifactId();

    String artifactVersion();

    String artifactPath();

    String factoryId();

    /** 与二进制无关的配置 token 名称，仅用于目录展示与诊断。 */
    String configTypeName();
}
