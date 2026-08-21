package io.knotra.pf4j;

import java.nio.file.Path;
import java.util.Map;

import io.knotra.ComponentOrigin;
import io.knotra.MountOptions;

/** 附加到每个 artifact 根挂载与子挂载上的稳定来源事实。 */
record ArtifactMetadata(
        String artifactId,
        String version,
        Path path,
        MountOptions options) {

    ArtifactMetadata {
        if (artifactId == null || artifactId.isBlank()) {
            throw new IllegalArgumentException("artifactId must not be blank");
        }
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("artifact version must not be blank");
        }
        path = path.toAbsolutePath().normalize();
        options = options == null
                ? new MountOptions(ComponentOrigin.artifact(
                        artifactId, version, "pf4j:" + artifactId + ":" + path))
                : options;
    }

    static ArtifactMetadata of(String artifactId, String version, Path path) {
        return new ArtifactMetadata(artifactId, version, path, null);
    }

    ComponentOrigin origin() {
        return ComponentOrigin.artifact(
                artifactId,
                version,
                "pf4j:" + artifactId + ":" + path);
    }

    Map<String, String> metadata() {
        return options.metadata();
    }
}
