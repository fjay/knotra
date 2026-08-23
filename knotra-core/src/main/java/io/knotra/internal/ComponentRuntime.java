package io.knotra.internal;

import io.knotra.ComponentState;
import io.knotra.FailureInfo;
import io.knotra.PendingOperationsSnapshot;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * 单个稳定 MountHandle 的可执行运行时记录。
 *
 * <p>一个实例只服务一个挂载点，保存期望配置、当前/待重试清理的 {@link ActivationRuntime}，
 * 以及收敛控制字段。状态迁移由 {@link DefaultKnotraRuntime#driveTransition} 驱动；
 * {@code chainLock} 只把并发请求合并到同一个过渡 Future，不保护全局视图。锁顺序是
 * 协调器锁可以在内部调用过渡完成方法时嵌套获取 {@code chainLock}，反向嵌套不允许。</p>
 */
final class ComponentRuntime {
    final String handleId;
    final String contextId;
    final String mountId;
    final PreparedComponent<?> prepared;

    // 宿主事务提交后的期望状态；正在运行的 Activation 仍持有启动时捕获的旧配置。
    private volatile DesiredComponentState desired = DesiredComponentState.INITIAL;
    // 当前启动或等待清理的 Activation；清理失败时会转入 failedCleanup，阻止新代际提前启动。
    volatile ActivationRuntime current;
    volatile ActivationRuntime failedCleanup;
    // 用户 start() 自身失败：清理完成后需要显式 retry，避免自动重启掩盖外部故障。
    volatile boolean pendingStartFailure;
    // retryAsync 校验成功后写入的一次性意图；由状态机在实际重启/重跑清理时消费。
    private volatile RetryIntent retryIntent = RetryIntent.NONE;
    // 绑定环等结构性失败在拓扑指纹变化前不重试，防止无限无效 Activation。
    volatile boolean suppressAutoRestart;
    volatile boolean blockedNonConvergent;
    volatile String lastStartError = "";
    volatile String lastCleanupError = "";
    volatile FailureInfo lastStartFailure = FailureInfo.EMPTY;
    volatile FailureInfo lastCleanupFailure = FailureInfo.EMPTY;
    volatile String reconcileFingerprint = "";
    volatile int reconcileAttempts;

    // 过渡链表锁：同一 MountHandle 的并发 whenSettled/dispose/retry 共享一个 Future。
    private final Object chainLock = new Object();
    private final AtomicReference<CompletableFuture<io.knotra.ComponentState>> transition =
            new AtomicReference<>();
    private final AtomicReference<CompletableFuture<io.knotra.ComponentState>> requestedDriver =
            new AtomicReference<>();
    // chainLock 内维护；pendingLock 只保护这段纯元数据，观察者不等待 transition 完成回调。
    private final Object pendingLock = new Object();
    private PendingOperationSample pendingTransition;

    ComponentRuntime(
            String handleId,
            String contextId,
            String mountId,
            PreparedComponent<?> prepared) {
        this.handleId = handleId;
        this.contextId = contextId;
        this.mountId = mountId;
        this.prepared = prepared;
        this.desired = new DesiredComponentState(prepared.config(), 1);
    }

    CompletableFuture<io.knotra.ComponentState> enqueue(
            DefaultKnotraRuntime runtime,
            ExecutorService executor) {
        Reservation reservation;
        // 与预约同锁采样展示标签，避免 pending 详情与实际过渡意图错位。
        synchronized (chainLock) {
            reservation = reserveTransition(
                    runtime.pendingTime(), transitionDetail());
        }
        if (reservation.created()) {
            reservation.component().executeReserved(
                    runtime,
                    executor,
                    reservation.future());
        }
        return reservation.future();
    }

    void requestRetry(RetryIntent intent) {
        this.retryIntent = intent;
    }

    private String transitionDetail() {
        return switch (retryIntent) {
            case CLEANUP -> "component cleanup retry";
            case ACTIVATION -> "component activation retry";
            case NONE -> "component transition";
        };
    }

    boolean consumeActivationRetryIntent() {
        if (retryIntent != RetryIntent.ACTIVATION) {
            return false;
        }
        retryIntent = RetryIntent.NONE;
        return true;
    }

    boolean consumeCleanupRetryIntent() {
        if (retryIntent != RetryIntent.CLEANUP) {
            return false;
        }
        retryIntent = RetryIntent.NONE;
        return true;
    }

    CompletableFuture<io.knotra.ComponentState> observeSettled(
            Supplier<io.knotra.ComponentState> currentState) {
        synchronized (chainLock) {
            CompletableFuture<io.knotra.ComponentState> existing = transition.get();
            if (existing != null && !existing.isDone()) {
                return existing;
            }
            return CompletableFuture.completedFuture(currentState.get());
        }
    }

    // 预约可在发布前于协调器内发生；实际驱动必须等协调器释放后提交到虚拟线程。
    Reservation reserveTransition(long startNanos, String detail) {
        synchronized (chainLock) {
            CompletableFuture<io.knotra.ComponentState> existing = transition.get();
            if (existing != null && !existing.isDone()) {
                return new Reservation(this, existing, false);
            }
            CompletableFuture<io.knotra.ComponentState> created = new CompletableFuture<>();
            publishPending(pendingSample(startNanos, detail));
            transition.set(created);
            return new Reservation(this, created, true);
        }
    }

    PendingOperationSample pendingSnapshot() {
        synchronized (pendingLock) {
            return pendingTransition;
        }
    }

    private PendingOperationSample pendingSample(long startNanos, String detail) {
        return new PendingOperationSample(
                PendingOperationsSnapshot.Kind.COMPONENT_TRANSITION,
                handleId,
                PendingOperationsSnapshot.WaitType.COMPONENT,
                startNanos,
                detail);
    }

    private void publishPending(PendingOperationSample sample) {
        synchronized (pendingLock) {
            pendingTransition = sample;
        }
    }

    private void clearPending() {
        synchronized (pendingLock) {
            pendingTransition = null;
        }
    }

    void executeReserved(
            DefaultKnotraRuntime runtime,
            ExecutorService executor,
            CompletableFuture<ComponentState> future) {
        synchronized (chainLock) {
            if (transition.get() != future) {
                return;
            }
            requestedDriver.set(future);
        }
        try {
            executor.execute(() -> runtime.driveTransition(this, future));
        } catch (RejectedExecutionException error) {
            failTransition(future, new TransitionRejectedStateException(error));
        }
    }

    String transitionDiagnostic() {
        CompletableFuture<ComponentState> current = transition.get();
        CompletableFuture<ComponentState> driver = requestedDriver.get();
        return "slot=" + System.identityHashCode(current)
                + "/" + (current == null ? "null" : current.isDone())
                + " driver=" + System.identityHashCode(driver)
                + "/" + (driver == null ? "null" : driver.isDone())
                + " ownsDriver=" + (current == driver);
    }

    boolean noLongerOwnsTransition(CompletableFuture<ComponentState> future) {
        synchronized (chainLock) {
            return transition.get() != future;
        }
    }

    boolean cancelTransition(CompletableFuture<ComponentState> future) {
        synchronized (chainLock) {
            boolean cancelled = transition.compareAndSet(future, null);
            if (cancelled) {
                clearPending();
            }
            return cancelled;
        }
    }

    void failTransition(
            CompletableFuture<io.knotra.ComponentState> future,
            Throwable error) {
        synchronized (chainLock) {
            if (transition.compareAndSet(future, null)) {
                clearPending();
            }
            future.completeExceptionally(error);
        }
    }

    Reservation replaceTransition(long startNanos, String detail) {
        synchronized (chainLock) {
            CompletableFuture<io.knotra.ComponentState> created = new CompletableFuture<>();
            publishPending(pendingSample(startNanos, detail));
            transition.set(created);
            return new Reservation(this, created, true);
        }
    }

    void clearTransition(CompletableFuture<io.knotra.ComponentState> future) {
        synchronized (chainLock) {
            if (transition.compareAndSet(future, null)) {
                clearPending();
            }
        }
    }

    void finishTransition(CompletableFuture<io.knotra.ComponentState> future) {
        clearTransition(future);
    }

    void finishTransition(
            CompletableFuture<io.knotra.ComponentState> future,
            io.knotra.ComponentState state) {
        synchronized (chainLock) {
            if (transition.compareAndSet(future, null)) {
                clearPending();
            }
            future.complete(state);
        }
    }

    DesiredComponentState desiredState() {
        return desired;
    }

    void updateConfig(Object config, long revision) {
        this.desired = new DesiredComponentState(config, revision);
    }

    record Reservation(
            ComponentRuntime component,
            CompletableFuture<io.knotra.ComponentState> future,
            boolean created) {
    }

    enum RetryIntent {
        NONE,
        ACTIVATION,
        CLEANUP
    }
}
