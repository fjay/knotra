package io.knotra.pf4j;

import java.util.List;

/**
 * 单个受管 PF4J artifact 的不可变结构快照。
 *
 * <p>快照只描述状态、来源、工厂与归属标识，不引用 PF4J 管理器、工厂实例或
 * ClassLoader 本体；持有快照不会阻止已卸载插件变弱可达。</p>
 */
public record ArtifactSnapshot(
        String artifactId,
        String version,
        String path,
        ArtifactState state,
        String pf4jState,
        String classLoaderDescription,
        List<String> factoryIds,
        List<String> ownedHandleIds,
        List<String> dependencyArtifactIds) {

    public ArtifactSnapshot {
        factoryIds = List.copyOf(factoryIds);
        ownedHandleIds = List.copyOf(ownedHandleIds);
        dependencyArtifactIds = List.copyOf(dependencyArtifactIds);
    }
}
