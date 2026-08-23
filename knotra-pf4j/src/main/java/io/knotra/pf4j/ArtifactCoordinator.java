package io.knotra.pf4j;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * 串行化所有 PF4J 变更与面向 PF4J 的读取的单线程协调器。
 *
 * <p>协调器回调中的嵌套提交会在同一可重入锁下内联执行，使 artifact 回调可以安全
 * 查看目录；回调外部的提交仍进入唯一协调线程，避免 PF4J 状态被并发修改。
 * 诊断监视器只保存 token、目标文本和 tick，不保存任务、返回值或异常。</p>
 */
final class ArtifactCoordinator {

    private final ExecutorService executor;
    private final ReentrantLock mutationLock = new ReentrantLock();
    private final Object lifecycleLock = new Object();
    private final Object monitorLock = new Object();
    private final AtomicReference<Thread> coordinatorThread = new AtomicReference<>();
    private final AtomicLong operationIds = new AtomicLong();
    private final Map<Long, MonitorState> operations = new LinkedHashMap<>();
    private final LongSupplier ticker;
    private volatile boolean stopped;

    ArtifactCoordinator() {
        this(System::nanoTime);
    }

    ArtifactCoordinator(LongSupplier ticker) {
        this.ticker = ticker;
        this.executor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "knotra-pf4j-artifact-coordinator");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** 提交 PF4J 相关操作；协调线程内的嵌套调用会内联执行以保持可重入语义。 */
    <T> CompletableFuture<T> submit(Supplier<T> operation) {
        return submit("operation", operation);
    }

    <T> CompletableFuture<T> submit(String target, Supplier<T> operation) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(operation, "operation");
        // stop 与提交共用生命周期锁，避免“检查后执行”窗口内向已关闭 executor 提交。
        synchronized (lifecycleLock) {
            if (stopped) {
                return CompletableFuture.failedFuture(stopped());
            }
            // 协调线程内的读取若再排队会自等待；内联加同一把可重入锁保持串行语义。
            if (isCoordinatorThread()) {
                long token = beginOperation(target, true);
                try {
                    return inline(operation);
                } finally {
                    endOperation(token);
                }
            }
            long token = beginOperation(target, false);
            try {
                return CompletableFuture.supplyAsync(
                        () -> asCoordinatorThread(token, operation), executor);
            } catch (RejectedExecutionException failure) {
                endOperation(token);
                return CompletableFuture.failedFuture(stopped());
            }
        }
    }

    CompletableFuture<Void> execute(Runnable operation) {
        return execute("operation", operation);
    }

    CompletableFuture<Void> execute(String target, Runnable operation) {
        return submit(target, () -> {
            operation.run();
            return null;
        }).thenApply(ignored -> null);
    }

    void stop() {
        synchronized (lifecycleLock) {
            stopped = true;
            executor.shutdown();
        }
    }

    boolean isStopped() {
        return stopped;
    }

    List<CoordinatorOperation> pendingOperations() {
        synchronized (monitorLock) {
            long now = ticker.getAsLong();
            List<CoordinatorOperation> result = new ArrayList<>(operations.size());
            for (MonitorState state : operations.values()) {
                result.add(new CoordinatorOperation(
                        state.running,
                        state.target,
                        TickerAge.elapsed(state.startTick, now)));
            }
            return List.copyOf(result);
        }
    }

    private long beginOperation(String target, boolean running) {
        long token = operationIds.incrementAndGet();
        synchronized (monitorLock) {
            operations.put(token, new MonitorState(target, running, ticker.getAsLong()));
        }
        return token;
    }

    private void markRunning(long token) {
        synchronized (monitorLock) {
            MonitorState state = operations.get(token);
            if (state != null) {
                state.running = true;
            }
        }
    }

    private void endOperation(long token) {
        synchronized (monitorLock) {
            operations.remove(token);
        }
    }

    private boolean isCoordinatorThread() {
        return Thread.currentThread() == coordinatorThread.get();
    }

    private <T> T asCoordinatorThread(long token, Supplier<T> operation) {
        Thread current = Thread.currentThread();
        coordinatorThread.compareAndSet(null, current);
        // 单线程 executor 也显式校验线程身份，防止实现漂移破坏可重入判断。
        if (coordinatorThread.get() != current) {
            endOperation(token);
            throw new IllegalStateException("artifact coordinator executor changed threads");
        }
        markRunning(token);
        try {
            return locked(operation);
        } finally {
            endOperation(token);
        }
    }

    private <T> CompletableFuture<T> inline(Supplier<T> operation) {
        try {
            return CompletableFuture.completedFuture(locked(operation));
        } catch (Throwable failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    private <T> T locked(Supplier<T> operation) {
        mutationLock.lock();
        try {
            return operation.get();
        } finally {
            mutationLock.unlock();
        }
    }

    private IllegalStateException stopped() {
        return new IllegalStateException("artifact coordinator is stopped");
    }

    record CoordinatorOperation(boolean running, String target, Duration age) {
    }

    private static final class MonitorState {
        private final String target;
        private final long startTick;
        private boolean running;

        private MonitorState(String target, boolean running, long startTick) {
            this.target = target;
            this.running = running;
            this.startTick = startTick;
        }
    }
}
