package io.knotra;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** 组件的稳定逻辑挂载点句柄。 */
public interface ComponentHandle<C> extends AutoCloseable {
    String handleId();

    String mountId();

    String componentId();

    String factoryId();

    String contextId();

    ComponentState state();

    ComponentGoal goal();

    long configRevision();

    /** 等待组件离开当前 STARTING/STOPPING 过渡并返回结算状态。 */
    CompletionStage<ComponentState> whenSettled();

    /**
     * 无限期等待当前过渡收敛，且仅在组件可用时返回自身。
     *
     * <p>结算结果不是 ACTIVE 时抛出 {@link ComponentNotActiveException}，异常状态等于结算状态。
     * 若失败瞬间的单次状态观察看到更新的 ACTIVE（并发过渡刚刚完成），则直接返回自身。
     * 等待被中断时恢复调用线程的中断标记；若此时组件已 ACTIVE 则返回自身并保留中断标记，
     * 否则抛出异常。抛出的异常不保留任何 Throwable 引用。</p>
     */
    default ComponentHandle<C> requireActive() {
        return awaitActive(null);
    }

    /**
     * 有界等待当前过渡收敛，且仅在组件可用时返回自身。
     *
     * <p>WAITING、FAILED、DISPOSED 与超时都会抛出 {@link ComponentNotActiveException}；
     * 异常状态来自结算结果，或超时/中断/结算失败时的一次当前状态观察，永远不会是 ACTIVE。
     * 若失败瞬间的单次状态观察看到更新的 ACTIVE（并发过渡刚刚完成），则直接返回自身。
     * 等待被中断时恢复调用线程的中断标记；超时通过异常的 {@code timeout()} 表达；
     * 中断与结算失败同时以稳定文本诊断编码。抛出的异常不保留任何 Throwable 引用。</p>
     */
    default ComponentHandle<C> requireActive(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        return awaitActive(timeout);
    }

    private ComponentHandle<C> awaitActive(Duration timeout) {
        CompletableFuture<ComponentState> future = whenSettled().toCompletableFuture();
        ComponentState settled = null;
        boolean interrupted = false;
        boolean timedOut = false;
        Throwable settlementError = null;
        try {
            settled = timeout == null
                    ? future.get()
                    : future.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            interrupted = true;
        } catch (TimeoutException error) {
            timedOut = true;
        } catch (ExecutionException | CompletionException error) {
            settlementError = error;
        }

        boolean settledNormally = !interrupted && !timedOut && settlementError == null;
        if (settledNormally && settled == ComponentState.ACTIVE) {
            return this;
        }
        ComponentState observed = state();
        if (observed == ComponentState.ACTIVE) {
            // 并发过渡刚刚完成；中断路径已提前恢复中断标记，这里返回自身而不是抛出 ACTIVE 异常。
            return this;
        }
        ComponentState failureState = settledNormally ? settled : observed;
        RuntimeDiagnostic detail = failureDetail(interrupted, settlementError);
        throw new ComponentNotActiveException(
                failureState,
                handleId(),
                mountId(),
                componentId(),
                factoryId(),
                contextId(),
                timeout,
                detail == null ? List.of() : List.of(detail));
    }

    private RuntimeDiagnostic failureDetail(boolean interrupted, Throwable settlementError) {
        if (interrupted) {
            return new RuntimeDiagnostic(
                    DiagnosticCode.INVALID_LIFECYCLE_OPERATION,
                    handleId(),
                    "wait interrupted before settlement");
        }
        if (settlementError != null) {
            return new RuntimeDiagnostic(
                    DiagnosticCode.ROLLBACK_FAILED,
                    handleId(),
                    "settlement failed: " + stableError(settlementError));
        }
        return null;
    }

    private static String stableError(Throwable error) {
        try {
            String message = error.getMessage();
            String text = message == null || message.isBlank()
                    ? error.getClass().getName()
                    : message;
            return text.length() <= 160 ? text : text.substring(0, 160);
        } catch (Throwable ignored) {
            return "<invalid description>";
        }
    }
    /** 请求类型化配置变更；事务拒绝时 stage 异常完成。 */
    CompletionStage<ComponentState> reconfigureAsync(C config);

    /** 重试 FAILED 组件的启动或未完成清理。 */
    CompletionStage<ComponentState> retryAsync();

    /** 逻辑释放组件及其拥有的子挂载与注册。 */
    CompletionStage<ComponentState> disposeAsync();

    /**
     * 阻塞释放组件。清理未收敛到 DISPOSED 时抛出异常，FAILED 状态仍保留供 retryAsync 使用。
     */
    @Override
    default void close() {
        ComponentState settled = disposeAsync().toCompletableFuture().join();
        if (settled != ComponentState.DISPOSED) {
            throw new IllegalStateException(
                    "component cleanup did not converge: " + handleId() + " is " + settled);
        }
    }
}
