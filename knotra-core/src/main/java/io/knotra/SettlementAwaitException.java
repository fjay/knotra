package io.knotra;

/**
 * 同步阻塞等待结算（{@link Awaitable#awaitSettled}）超时、中断或失败时抛出的非受检异常。
 *
 * <p>为防止类加载器泄漏，该异常不保存底层 Throwable 引用。</p>
 */
public final class SettlementAwaitException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /** 结算等待失败的具体原因枚举。 */
    public enum Reason {
        /** 等待超时。 */
        TIMEOUT,
        /** 线程在等待期间被中断。 */
        INTERRUPTED,
        /** 结算异步阶段以失败完成。 */
        FAILED
    }

    private final Reason reason;

    public SettlementAwaitException(Reason reason, String message) {
        super(message, null, false, false);
        this.reason = java.util.Objects.requireNonNull(reason, "reason");
    }

    public Reason reason() {
        return reason;
    }
}
