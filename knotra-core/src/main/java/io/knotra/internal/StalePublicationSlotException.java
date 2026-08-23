package io.knotra.internal;

/**
 * Publication 槽位乐观期望失效：另一个操作已先线性化提交。
 *
 * <p>该异常只在协调器内由意图校验抛出，{@code transact} 不将其包装为
 * {@code TransactionRejectedException}；Publication 句柄在锁外重读最新槽位后重试，
 * 因此它不是用户可见的失败，而是内部收敛信号。</p>
 */
final class StalePublicationSlotException extends RuntimeException {
    StalePublicationSlotException(String message) {
        super(message);
    }
}
