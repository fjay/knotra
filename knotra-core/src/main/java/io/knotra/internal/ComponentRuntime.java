package io.knotra.internal;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;


/**
 * 单个稳定 ComponentHandle 的可执行运行时记录。
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
    volatile Object desiredConfig;
    volatile long desiredRevision = 1;
    // 当前启动或等待清理的 Activation；清理失败时会转入 failedCleanup，阻止新代际提前启动。
    volatile ActivationRuntime current;
    volatile ActivationRuntime failedCleanup;
    // 用户 start() 自身失败：清理完成后需要显式 retry，避免自动重启掩盖外部故障。
    volatile boolean pendingStartFailure;
    // 绑定环等结构性失败在拓扑指纹变化前不重试，防止无限无效 Activation。
    volatile boolean suppressAutoRestart;
    volatile boolean blockedNonConvergent;
    volatile String lastStartError = "";
    volatile String lastCleanupError = "";
    // 收敛指纹只包含目标、配置代际和注册身份；值相等不触发重新激活。
    volatile String reconcileFingerprint = "";
    volatile int reconcileAttempts;

    // 过渡链表锁：同一 ComponentHandle 的并发 whenSettled/dispose/retry 共享一个 Future。
    private final Object chainLock = new Object();
    private final AtomicReference<CompletableFuture<io.knotra.ComponentState>> transition =
            new AtomicReference<>();

    ComponentRuntime(
            String handleId,
            String contextId,
            String mountId,
            PreparedComponent<?> prepared) {
        this.handleId = handleId;
        this.contextId = contextId;
        this.mountId = mountId;
        this.prepared = prepared;
        this.desiredConfig = prepared.config();
    }

    CompletableFuture<io.knotra.ComponentState> enqueue(
            DefaultKnotraRuntime runtime,
            ExecutorService executor) {
        Reservation reservation = reserveTransition();
        if (reservation.created()) {
            reservation.component().executeReserved(
                    runtime,
                    executor,
                    reservation.future());
        }
        return reservation.future();
    }
    // 预约必须在协调器临界区外执行：先合并调用方，再由一个虚拟线程驱动实际状态迁移。
    Reservation reserveTransition() {
        synchronized (chainLock) {
            CompletableFuture<io.knotra.ComponentState> existing = transition.get();
            if (existing != null && !existing.isDone()) {
                return new Reservation(this, existing, false);
            }
            CompletableFuture<io.knotra.ComponentState> created = new CompletableFuture<>();
            transition.set(created);
            return new Reservation(this, created, true);
        }
    }

    void executeReserved(
            DefaultKnotraRuntime runtime,
            ExecutorService executor,
            CompletableFuture<io.knotra.ComponentState> future) {
        synchronized (chainLock) {
            if (transition.get() != future) {
                return;
            }
        }
        executor.execute(() -> runtime.driveTransition(handleId, future));
    }


    void cancelTransition(CompletableFuture<io.knotra.ComponentState> future) {
        synchronized (chainLock) {
            transition.compareAndSet(future, null);
        }
    }

    void failTransition(
            CompletableFuture<io.knotra.ComponentState> future,
            Throwable error) {
        synchronized (chainLock) {
            transition.compareAndSet(future, null);
            future.completeExceptionally(error);
        }
    }

    Reservation replaceTransition() {
        synchronized (chainLock) {
            CompletableFuture<io.knotra.ComponentState> created = new CompletableFuture<>();
            transition.set(created);
            return new Reservation(this, created, true);
        }
    }

    void finishTransition(CompletableFuture<io.knotra.ComponentState> future) {
        synchronized (chainLock) {
            transition.compareAndSet(future, null);
        }
    }

    void finishTransition(
            CompletableFuture<io.knotra.ComponentState> future,
            io.knotra.ComponentState state) {
        synchronized (chainLock) {
            transition.compareAndSet(future, null);
            future.complete(state);
        }
    }

    void updateConfig(Object config, long revision) {
        this.desiredConfig = config;
        this.desiredRevision = revision;
    }

    record Reservation(
            ComponentRuntime component,
            CompletableFuture<io.knotra.ComponentState> future,
            boolean created) {
    }
}
