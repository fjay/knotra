package io.knotra;

import java.util.Objects;

/** 运行时静态配置。 */
public record KnotraConfig(
        String runtimeId,
        int maxReconcileIterations,
        FailureDetailPolicy failureDetailPolicy) {

    public KnotraConfig {
        if (runtimeId == null || runtimeId.isBlank()) {
            throw new IllegalArgumentException("runtimeId must not be blank");
        }
        if (maxReconcileIterations < 1) {
            throw new IllegalArgumentException("maxReconcileIterations must be positive");
        }
        Objects.requireNonNull(failureDetailPolicy, "failureDetailPolicy");
    }

    public KnotraConfig(String runtimeId, int maxReconcileIterations) {
        this(runtimeId, maxReconcileIterations, FailureDetailPolicy.defaults());
    }

    public static KnotraConfig defaults() {
        return new KnotraConfig("knotra-runtime", 256, FailureDetailPolicy.defaults());
    }

    public static KnotraConfig of(String runtimeId) {
        return new KnotraConfig(
                Objects.requireNonNull(runtimeId, "runtimeId"),
                256,
                FailureDetailPolicy.defaults());
    }

    /** 运行时诊断所保留失败详情的有界策略。 */
    public record FailureDetailPolicy(
            int maxCauses,
            int maxFrames,
            int maxTextLength,
            boolean includeStackTraces) {

        public FailureDetailPolicy {
            if (maxCauses < 0 || maxFrames < 1 || maxTextLength < 32) {
                throw new IllegalArgumentException("failure detail bounds are invalid");
            }
        }

        public static FailureDetailPolicy defaults() {
            return new FailureDetailPolicy(3, 32, 500, false);
        }

        public FailureDetailPolicy withStackTraces(boolean include) {
            return new FailureDetailPolicy(maxCauses, maxFrames, maxTextLength, include);
        }
    }
}
