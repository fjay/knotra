package io.knotra;

import java.util.Objects;

public record KnotraConfig(String runtimeId, int maxReconcileIterations) {
    public KnotraConfig {
        if (runtimeId == null || runtimeId.isBlank()) {
            throw new IllegalArgumentException("runtimeId must not be blank");
        }
        if (maxReconcileIterations < 1) {
            throw new IllegalArgumentException("maxReconcileIterations must be positive");
        }
    }

    public static KnotraConfig defaults() {
        return new KnotraConfig("knotra-runtime", 256);
    }

    public static KnotraConfig of(String runtimeId) {
        return new KnotraConfig(Objects.requireNonNull(runtimeId, "runtimeId"), 256);
    }
}
