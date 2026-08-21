package io.knotra.pf4j;

import io.knotra.ComponentState;

/** 单个来源于 artifact 的组件当前归属事实；这是运行时快照的不可变投影。 */
public record ArtifactOwnership(
        String artifactId,
        String factoryId,
        String handleId,
        String mountId,
        String parentHandleId,
        ComponentState state) {
}
