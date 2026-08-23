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

    /** 返回替换运行时标识后的配置副本。 */
    public KnotraConfig withRuntimeId(String nextRuntimeId) {
        return new KnotraConfig(nextRuntimeId, maxReconcileIterations, failureDetailPolicy);
    }

    /** 返回替换协调迭代上限后的配置副本。 */
    public KnotraConfig withMaxReconcileIterations(int nextMaxReconcileIterations) {
        return new KnotraConfig(runtimeId, nextMaxReconcileIterations, failureDetailPolicy);
    }

    /** 返回替换失败详情策略后的配置副本。 */
    public KnotraConfig withFailureDetailPolicy(FailureDetailPolicy nextPolicy) {
        return new KnotraConfig(
                runtimeId,
                maxReconcileIterations,
                Objects.requireNonNull(nextPolicy, "failureDetailPolicy"));
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

        public FailureDetailPolicy withMaxCauses(int nextMaxCauses) {
            return new FailureDetailPolicy(nextMaxCauses, maxFrames, maxTextLength, includeStackTraces);
        }

        public FailureDetailPolicy withMaxFrames(int nextMaxFrames) {
            return new FailureDetailPolicy(maxCauses, nextMaxFrames, maxTextLength, includeStackTraces);
        }

        public FailureDetailPolicy withMaxTextLength(int nextMaxTextLength) {
            return new FailureDetailPolicy(maxCauses, maxFrames, nextMaxTextLength, includeStackTraces);
        }
    }
}
