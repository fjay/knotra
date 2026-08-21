package io.knotra.pf4j;

/** 包内不可变投影：把受管工厂压缩成目录可见的安全元数据。 */
record ArtifactFactoryCatalogView(
        String artifactId,
        String artifactVersion,
        String artifactPath,
        String factoryId,
        String configTypeName) implements ArtifactFactoryCatalogEntry {
}
