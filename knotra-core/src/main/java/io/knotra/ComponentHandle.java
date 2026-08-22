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

    /** 无限期等待当前过渡收敛，且仅在结算状态为 ACTIVE 时返回自身。 */
    default ComponentHandle<C> requireActive() {
        return awaitActive(null);
    }

    /**
     * 有界等待当前过渡收敛，且仅在结算状态为 ACTIVE 时返回自身。
     * WAITING、FAILED、DISPOSED 与超时都会抛出 ComponentNotActiveException。
     */
    default ComponentHandle<C> requireActive(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        return awaitActive(timeout);
    }

    private ComponentHandle<C> awaitActive(Duration timeout) {
        ComponentState settled;
        try {
            CompletableFuture<ComponentState> future = whenSettled().toCompletableFuture();
            settled = timeout == null
                    ? future.get()
                    : future.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw notActive(timeout, error);
        } catch (TimeoutException error) {
            throw notActive(timeout, error);
        } catch (ExecutionException | CompletionException error) {
            throw notActive(timeout, error);
        }
        if (settled == ComponentState.ACTIVE) {
            return this;
        }
        throw notActive(timeout, null);
    }

    private ComponentNotActiveException notActive(Duration timeout, Throwable cause) {
        return new ComponentNotActiveException(
                state(),
                handleId(),
                mountId(),
                componentId(),
                factoryId(),
                contextId(),
                timeout,
                List.of(),
                cause);
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
