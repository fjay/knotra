package io.knotra.internal;

import java.util.Map;
import java.util.Set;

/**
 * Activation final publish 之后仍需执行的可提交后效果。
 *
 * <p>所有集合在 prepublish 阶段冻结为不可变副本；lease 引用只用于注册表登记与
 * 排空，不保留 Throwable、Class 或 ClassLoader。实例生命周期仅限于单次提交，
 * 不进入公开快照或长期故障诊断。</p>
 */
record ActivationPostCommitEffects(
        Set<String> dirty,
        Map<String, ProviderLeaseRuntime> leasesToRetire) {

    static ActivationPostCommitEffects empty() {
        return new ActivationPostCommitEffects(Set.of(), Map.of());
    }
}
