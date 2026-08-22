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

/**
 * 稳定的组件挂载点句柄。
 *
 * <p>代表组件在上下文树中的逻辑挂载位置；在多次激活重试、故障恢复或重新配置过程中保持身份稳定。
 * 若挂载组件具有公开配置契约，可使用子类型 {@link ConfiguredMountHandle} 进行动态重新配置。</p>
 */
public interface MountHandle extends AutoCloseable {

    /** 挂载句柄在运行时代际中的唯一实例标识。 */
    String handleId();

    /** 挂载点的逻辑路径标识（mountId）。 */
    String mountId();

    /** 目标组件的唯一标识。 */
    String componentId();

    /** 创建该挂载的工厂标识。 */
    String factoryId();

    /** 所属上下文节点的标识。 */
    String contextId();

    /** 组件当前的生命周期状态（ACTIVE, WAITING, STARTING, FAILED, DISPOSED 等）。 */
    ComponentState state();

    /** 组件当前的目标状态（RUNNING, DISPOSED 等）。 */
    ComponentGoal goal();

    /** 当前已应用的配置版本号。 */
    long configRevision();

    /** 观察当前挂载点自身生命周期过渡结算的异步阶段。 */
    CompletionStage<ComponentState> whenSettled();

    /** 同步等待组件结算并断言必须处于 ACTIVE 状态（无限期等待）。 */
    default MountHandle requireActive() {
        return awaitActive(null);
    }

    /** 有界同步等待组件结算并断言必须处于 ACTIVE 状态（超时抛出 MountNotActiveException）。 */
    default MountHandle requireActive(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        return awaitActive(timeout);
    }

    private MountHandle awaitActive(Duration timeout) {
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
            return this;
        }
        ComponentState failureState = settledNormally ? settled : observed;
        RuntimeDiagnostic detail = failureDetail(interrupted, settlementError);
        throw new MountNotActiveException(
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
            Throwable cause = error instanceof CompletionException || error instanceof ExecutionException
                    ? error.getCause()
                    : error;
            if (cause == null) {
                cause = error;
            }
            String type = cause.getClass().getName();
            String message = cause.getMessage();
            String text = message == null || message.isBlank() ? type : type + ": " + message;
            return text.length() <= 160 ? text : text.substring(0, 160);
        } catch (Throwable ignored) {
            return "<invalid description>";
        }
    }

    /** 异步重试处于 FAILED 状态的挂载激活或未完成的清理操作。 */
    CompletionStage<ComponentState> retryAsync();

    /** 异步逻辑销毁当前挂载及其级联拥有的所有子组件与资源。 */
    CompletionStage<ComponentState> disposeAsync();

    @Override
    default void close() {
        ComponentState settled = disposeAsync().toCompletableFuture().join();
        if (settled != ComponentState.DISPOSED) {
            throw new IllegalStateException(
                    "mount cleanup did not converge: " + handleId() + " is " + settled);
        }
    }
}
