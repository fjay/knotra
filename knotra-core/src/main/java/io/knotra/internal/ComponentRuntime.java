package io.knotra.internal;

import io.knotra.ComponentState;
import io.knotra.FailureInfo;
import io.knotra.PendingOperationsSnapshot;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * 单个稳定 MountHandle 的可执行运行时记录。
 *
 * <p>一个实例只服务一个挂载点。可变状态按不变量分组为三个不可变 tuple
 * （{@link ActivationSlots}、{@link ComponentFailureState}、{@link ComponentReconcileState}）
 * 与期望配置 tuple，各自通过单一 volatile 引用发布，读者永远不会看到组内撕裂。
 * coordinator-owned 写方法以 {@code Locked} 结尾，并通过 assert 校验调用方持有
 * 构造传入的协调器锁；生产环境关闭 assert 时零开销。
 * {@code chainLock} 只把并发请求合并到同一个过渡 Future，不保护全局视图。锁顺序是
 * 协调器锁可以在内部调用过渡完成方法时嵌套获取 {@code chainLock}，反向嵌套不允许。</p>
 */
final class ComponentRuntime {
    private final String handleId;
    private final String contextId;
    private final String mountId;
    private final PreparedComponent<?> prepared;
    private final Object coordinatorLock;

    // 宿主事务提交后的期望状态；正在运行的 Activation 仍持有启动时捕获的旧配置。
    private volatile DesiredComponentState desired = DesiredComponentState.INITIAL;
    // 当前启动或等待清理的 Activation；清理失败时会转入 failedCleanup，阻止新代际提前启动。
    private volatile ActivationSlots slots = ActivationSlots.EMPTY;
    // 用户 start()/清理自身失败：清理完成后需要显式 retry，避免自动重启掩盖外部故障。
    private volatile ComponentFailureState failure = ComponentFailureState.INITIAL;
    // 绑定环等结构性失败在拓扑指纹变化前不重试，防止无限无效 Activation。
    private volatile ComponentReconcileState reconcile = ComponentReconcileState.INITIAL;
    // retryAsync 校验成功后写入的一次性意图；由状态机在实际重启/重跑清理时消费。
    private volatile RetryIntent retryIntent = RetryIntent.NONE;

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
            PreparedComponent<?> prepared,
            Object coordinatorLock) {
        this.handleId = handleId;
        this.contextId = contextId;
        this.mountId = mountId;
        this.prepared = prepared;
        this.coordinatorLock = coordinatorLock;
        this.desired = new DesiredComponentState(prepared.config(), 1);
    }

    String handleId() {
        return handleId;
    }

    String contextId() {
        return contextId;
    }

    String mountId() {
        return mountId;
    }

    PreparedComponent<?> prepared() {
        return prepared;
    }

    // ------------------------------------------------------------------
    // 期望配置：无锁读，coordinator 写。
    // ------------------------------------------------------------------

    DesiredComponentState desiredState() {
        return desired;
    }

    void updateDesiredLocked(Object config, long revision) {
        assert Thread.holdsLock(coordinatorLock);
        this.desired = new DesiredComponentState(config, revision);
    }

    // ------------------------------------------------------------------
    // Activation 槽位：current 与 failedCleanup 成对发布，读者不见撕裂组合。
    // ------------------------------------------------------------------

    ActivationSlots slots() {
        return slots;
    }

    ActivationRuntime current() {
        return slots.current();
    }

    ActivationRuntime failedCleanup() {
        return slots.failedCleanup();
    }

    void claimCurrentLocked(ActivationRuntime activation) {
        assert Thread.holdsLock(coordinatorLock);
        slots = new ActivationSlots(activation, slots.failedCleanup());
    }

    void clearCurrentLocked() {
        assert Thread.holdsLock(coordinatorLock);
        slots = new ActivationSlots(null, slots.failedCleanup());
    }

    void markFailedCleanupLocked(ActivationRuntime activation) {
        assert Thread.holdsLock(coordinatorLock);
        slots = new ActivationSlots(slots.current(), activation);
    }

    void clearFailedCleanupLocked() {
        assert Thread.holdsLock(coordinatorLock);
        slots = new ActivationSlots(slots.current(), null);
    }

    void retainFailedCleanupLocked(ActivationRuntime activation) {
        assert Thread.holdsLock(coordinatorLock);
        slots = new ActivationSlots(activation, activation);
    }

    // ------------------------------------------------------------------
    // start/cleanup 失败：一次读取拿到整组字段，写入按 start/cleanup 两半收敛。
    // ------------------------------------------------------------------

    ComponentFailureState failureState() {
        return failure;
    }

    boolean pendingStartFailure() {
        return failure.pendingStartFailure();
    }

    String lastStartError() {
        return failure.lastStartError();
    }

    FailureInfo lastStartFailure() {
        return failure.lastStartFailure();
    }

    String lastCleanupError() {
        return failure.lastCleanupError();
    }

    FailureInfo lastCleanupFailure() {
        return failure.lastCleanupFailure();
    }

    void recordStartFailureLocked(
            boolean pendingStartFailure,
            String lastStartError,
            FailureInfo lastStartFailure) {
        assert Thread.holdsLock(coordinatorLock);
        failure = new ComponentFailureState(
                pendingStartFailure,
                lastStartError,
                lastStartFailure,
                failure.lastCleanupError(),
                failure.lastCleanupFailure());
    }

    void recordStartFailureDetailLocked(FailureInfo lastStartFailure) {
        assert Thread.holdsLock(coordinatorLock);
        failure = new ComponentFailureState(
                failure.pendingStartFailure(),
                failure.lastStartError(),
                lastStartFailure,
                failure.lastCleanupError(),
                failure.lastCleanupFailure());
    }

    void clearStartFailureLocked() {
        recordStartFailureLocked(false, "", FailureInfo.EMPTY);
    }

    void recordCleanupFailureLocked(String lastCleanupError, FailureInfo lastCleanupFailure) {
        assert Thread.holdsLock(coordinatorLock);
        failure = new ComponentFailureState(
                failure.pendingStartFailure(),
                failure.lastStartError(),
                failure.lastStartFailure(),
                lastCleanupError,
                lastCleanupFailure);
    }

    void clearCleanupFailureLocked() {
        recordCleanupFailureLocked("", FailureInfo.EMPTY);
    }

    // ------------------------------------------------------------------
    // 收敛控制：指纹、尝试次数与两个抑制位同代发布。
    // ------------------------------------------------------------------

    ComponentReconcileState reconcileState() {
        return reconcile;
    }

    boolean suppressAutoRestart() {
        return reconcile.suppressAutoRestart();
    }

    boolean blockedNonConvergent() {
        return reconcile.blockedNonConvergent();
    }

    void recordReconcileFingerprintLocked(String fingerprint) {
        assert Thread.holdsLock(coordinatorLock);
        reconcile = new ComponentReconcileState(
                reconcile.suppressAutoRestart(),
                reconcile.blockedNonConvergent(),
                fingerprint,
                reconcile.attempts());
    }

    void resetAutoRestartLocked() {
        assert Thread.holdsLock(coordinatorLock);
        reconcile = new ComponentReconcileState(
                false,
                false,
                reconcile.fingerprint(),
                0);
    }

    void suppressAutoRestartLocked(boolean suppressAutoRestart) {
        assert Thread.holdsLock(coordinatorLock);
        reconcile = new ComponentReconcileState(
                suppressAutoRestart,
                reconcile.blockedNonConvergent(),
                reconcile.fingerprint(),
                reconcile.attempts());
    }

    void clearBlockedNonConvergentLocked() {
        assert Thread.holdsLock(coordinatorLock);
        reconcile = new ComponentReconcileState(
                reconcile.suppressAutoRestart(),
                false,
                reconcile.fingerprint(),
                reconcile.attempts());
    }

    boolean planReconcileLocked(String fingerprint, int maxIterations) {
        assert Thread.holdsLock(coordinatorLock);
        ComponentReconcileState state = reconcile;
        if (!fingerprint.equals(state.fingerprint())) {
            state = new ComponentReconcileState(false, false, fingerprint, 0);
        }
        if (state.suppressAutoRestart()) {
            reconcile = state;
            return false;
        }
        int attempts = state.attempts() + 1;
        boolean blocked = attempts >= maxIterations;
        reconcile = new ComponentReconcileState(
                state.suppressAutoRestart(),
                blocked,
                state.fingerprint(),
                attempts);
        return !blocked;
    }

    // ------------------------------------------------------------------
    // retry 意图：写入与消费是 coordinator-owned；peek 供 chainLock 内组装详情。
    // ------------------------------------------------------------------

    RetryIntent peekRetryIntent() {
        return retryIntent;
    }

    void requestRetryLocked(RetryIntent intent) {
        assert Thread.holdsLock(coordinatorLock);
        this.retryIntent = intent;
    }

    boolean consumeActivationRetryIntentLocked() {
        assert Thread.holdsLock(coordinatorLock);
        if (retryIntent != RetryIntent.ACTIVATION) {
            return false;
        }
        retryIntent = RetryIntent.NONE;
        return true;
    }

    boolean consumeCleanupRetryIntentLocked() {
        assert Thread.holdsLock(coordinatorLock);
        if (retryIntent != RetryIntent.CLEANUP) {
            return false;
        }
        retryIntent = RetryIntent.NONE;
        return true;
    }

    // ------------------------------------------------------------------
    // 过渡链：只用 chainLock/pendingLock，不要求协调器锁。
    // ------------------------------------------------------------------

    CompletableFuture<io.knotra.ComponentState> enqueue(TransitionScheduler scheduler) {
        Reservation reservation;
        // 与预约同锁采样展示标签，避免 pending 详情与实际过渡意图错位。
        synchronized (chainLock) {
            reservation = reserveTransition(
                    scheduler.pendingTime(), transitionDetail());
        }
        if (reservation.created()) {
            scheduler.driveReservation(reservation);
        }
        return reservation.future();
    }

    private String transitionDetail() {
        return switch (peekRetryIntent()) {
            case CLEANUP -> "component cleanup retry";
            case ACTIVATION -> "component activation retry";
            case NONE -> "component transition";
        };
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

    boolean executeReserved(
            Executor executor,
            TransitionDriver driver,
            CompletableFuture<ComponentState> future) {
        synchronized (chainLock) {
            if (transition.get() != future || requestedDriver.get() != null) {
                return false;
            }
            requestedDriver.set(future);
        }
        try {
            executor.execute(() -> driver.drive(this, future));
            return true;
        } catch (RejectedExecutionException error) {
            Runnable completion = failTransition(
                    future, new TransitionRejectedStateException(error));
            completion.run();
            return true;
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
                requestedDriver.compareAndSet(future, null);
            }
            return cancelled;
        }
    }

    Runnable failTransition(
            CompletableFuture<io.knotra.ComponentState> future,
            Throwable error) {
        synchronized (chainLock) {
            if (transition.compareAndSet(future, null)) {
                clearPending();
                requestedDriver.compareAndSet(future, null);
            }
            return () -> future.completeExceptionally(error);
        }
    }

    Reservation replaceTransition(long startNanos, String detail) {
        synchronized (chainLock) {
            CompletableFuture<io.knotra.ComponentState> created = new CompletableFuture<>();
            publishPending(pendingSample(startNanos, detail));
            transition.set(created);
            requestedDriver.set(null);
            return new Reservation(this, created, true);
        }
    }

    void clearTransition(CompletableFuture<ComponentState> future) {
        synchronized (chainLock) {
            if (transition.compareAndSet(future, null)) {
                clearPending();
                requestedDriver.compareAndSet(future, null);
            }
        }
    }

    void finishTransition(CompletableFuture<ComponentState> future) {
        clearTransition(future);
    }

    Runnable finishTransition(
            CompletableFuture<io.knotra.ComponentState> future,
            io.knotra.ComponentState state) {
        synchronized (chainLock) {
            if (transition.compareAndSet(future, null)) {
                clearPending();
                requestedDriver.compareAndSet(future, null);
            }
            return () -> future.complete(state);
        }
    }

    /**
     * 当前与待重试清理的 Activation。合法组合只有三种：两者为 null、仅 current 非 null、
     * 或两者指向同一实例（清理失败的 Activation 同时占据两个语义槽）。
     */
    record ActivationSlots(ActivationRuntime current, ActivationRuntime failedCleanup) {
        static final ActivationSlots EMPTY = new ActivationSlots(null, null);

        boolean consistent() {
            return failedCleanup == null || failedCleanup == current;
        }
    }

    /** start/cleanup 失败字段的同代快照；FailureInfo 本身是不可变 DTO。 */
    record ComponentFailureState(
            boolean pendingStartFailure,
            String lastStartError,
            FailureInfo lastStartFailure,
            String lastCleanupError,
            FailureInfo lastCleanupFailure) {
        static final ComponentFailureState INITIAL = new ComponentFailureState(
                false,
                "",
                FailureInfo.EMPTY,
                "",
                FailureInfo.EMPTY);
    }

    /** 拓扑收敛控制的同代快照。 */
    record ComponentReconcileState(
            boolean suppressAutoRestart,
            boolean blockedNonConvergent,
            String fingerprint,
            int attempts) {
        static final ComponentReconcileState INITIAL =
                new ComponentReconcileState(false, false, "", 0);
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
