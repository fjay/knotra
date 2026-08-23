package io.knotra.internal;

import io.knotra.FailureInfo;
import io.knotra.FailurePhase;
import io.knotra.KnotraConfig;

/**
 * 用户 start() 失败的锁外纯值证据。
 *
 * <p>用户 Throwable 可覆写 {@code getMessage()}/{@code getCause()} 并在其中阻塞或
 * 重入 Runtime API，因此原始 Throwable 绝不能进入协调器临界区。证据只在锁外构造
 * 一次；协调器内只消费这里的不可变 {@link FailureInfo} 与稳定摘要字符串。</p>
 */
record StartFailureEvidence(
        FailureInfo failure,
        String summary) {

    private static final StartFailureEvidence NONE =
            new StartFailureEvidence(FailureInfo.EMPTY, null);

    static StartFailureEvidence none() {
        return NONE;
    }

    boolean failed() {
        return summary != null;
    }

    /** 只允许在协调器外调用；FailureCapture 与 safeError 都会执行用户覆写方法。 */
    static StartFailureEvidence capture(
            Throwable error,
            KnotraConfig.FailureDetailPolicy policy) {
        return new StartFailureEvidence(
                FailureCapture.capture(error, FailurePhase.ACTIVATION, policy, null),
                LifecycleScopeImpl.safeError(error));
    }
}
