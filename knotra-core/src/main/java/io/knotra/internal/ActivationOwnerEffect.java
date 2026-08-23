package io.knotra.internal;

import io.knotra.FailureInfo;

/**
 * Activation 最终发布后需要一次性落到 owner/activation 上的可执行效果。
 *
 * <p>效果值在 prepublish 阶段计算并冻结，final publish 之后在协调器内显式 apply。
 * apply 只做纯字段赋值与 stale 标记，不执行用户代码、不完成 future，因此不会在
 * 协调器内抛出或触发锁外回调。实例生命周期仅限于单次提交，不进入公开快照或
 * 长期故障诊断。</p>
 */
record ActivationOwnerEffect(
        boolean markStale,
        boolean pendingStartFailure,
        boolean suppressAutoRestart,
        boolean retainFailedCleanup,
        String lastStartError,
        FailureInfo lastStartFailure) {

    void apply(ActivationRuntime activation) {
        ComponentRuntime runtime = activation.owner;
        if (markStale) {
            activation.markStale();
        }
        runtime.recordStartFailureLocked(
                pendingStartFailure,
                lastStartError,
                lastStartFailure);
        runtime.suppressAutoRestartLocked(suppressAutoRestart);
        if (retainFailedCleanup) {
            runtime.retainFailedCleanupLocked(activation);
            runtime.requestRetryLocked(ComponentRuntime.RetryIntent.CLEANUP);
        }
    }
}
