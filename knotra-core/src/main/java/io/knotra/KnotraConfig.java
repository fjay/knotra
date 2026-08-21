package io.knotra;

import java.util.Objects;

/**
 * Runtime 的静态配置。
 *
 * <p>记录不可变；紧凑构造函数要求 runtimeId 非空白、maxReconcileIterations 为正。
 * 后者限制同一组件在绑定或配置反复变化时的自动重新激活次数，超过后组件保持 WAITING
 * 并报告 NON_CONVERGENT_RECONCILE。
 */
public record KnotraConfig(String runtimeId, int maxReconcileIterations) {
    public KnotraConfig {
        if (runtimeId == null || runtimeId.isBlank()) {
            throw new IllegalArgumentException("runtimeId must not be blank");
        }
        if (maxReconcileIterations < 1) {
            throw new IllegalArgumentException("maxReconcileIterations must be positive");
        }
    }

    /** 默认配置：runtimeId 为 knotra-runtime，收敛上限 256 次。 */
    public static KnotraConfig defaults() {
        return new KnotraConfig("knotra-runtime", 256);
    }

    /** 指定 runtimeId，收敛上限取默认值 256。 */
    public static KnotraConfig of(String runtimeId) {
        return new KnotraConfig(Objects.requireNonNull(runtimeId, "runtimeId"), 256);
    }
}
