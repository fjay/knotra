package io.knotra.pf4j;

import java.util.List;
import java.util.Optional;

/**
 * 单个受管 artifact 的不可变诊断视图。
 *
 * <p>诊断只包含稳定文本与标识，不保留 artifact 对象、Throwable 或 ClassLoader，
 * 因此持有诊断不会阻止已卸载插件的 ClassLoader 回收。</p>
 */
public record ArtifactDiagnostic(
        String artifactId,
        ArtifactState state,
        String transition,
        List<String> factoryIds,
        List<String> ownedHandleIds,
        Optional<String> lastError,
        List<String> classLoaderDiagnostics) {

    public ArtifactDiagnostic {
        factoryIds = List.copyOf(factoryIds);
        ownedHandleIds = List.copyOf(ownedHandleIds);
        lastError = lastError == null ? Optional.empty() : lastError;
        classLoaderDiagnostics = List.copyOf(classLoaderDiagnostics);
    }
}
