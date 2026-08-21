package io.knotra.pf4j;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * 串行化所有 PF4J 变更与面向 PF4J 的读取的单线程协调器。
 *
 * <p>协调器回调中的嵌套提交会在同一可重入锁下内联执行，使 artifact 回调可以安全
 * 查看目录；回调外部的提交仍进入唯一协调线程，避免 PF4J 状态被并发修改。</p>
 */
final class ArtifactCoordinator {

    private final ExecutorService executor;
    private final ReentrantLock mutationLock = new ReentrantLock();
    private final Object lifecycleLock = new Object();
    private final AtomicReference<Thread> coordinatorThread = new AtomicReference<>();
    private volatile boolean stopped;

    ArtifactCoordinator() {
        this.executor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "knotra-pf4j-artifact-coordinator");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** 提交 PF4J 相关操作；协调线程内的嵌套调用会内联执行以保持可重入语义。 */
    <T> CompletableFuture<T> submit(Supplier<T> operation) {
        // stop 与提交共用生命周期锁，避免“检查后执行”窗口内向已关闭 executor 提交。
        synchronized (lifecycleLock) {
            if (stopped) {
                return CompletableFuture.failedFuture(stopped());
            }
            // 协调线程内的读取若再排队会自等待；内联加同一把可重入锁保持串行语义。
            if (isCoordinatorThread()) {
                return inline(operation);
            }
            try {
                return CompletableFuture.supplyAsync(
                        () -> asCoordinatorThread(operation), executor);
            } catch (RejectedExecutionException failure) {
                return CompletableFuture.failedFuture(stopped());
            }
        }
    }

    CompletableFuture<Void> execute(Runnable operation) {
        return submit(() -> {
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

    private boolean isCoordinatorThread() {
        return Thread.currentThread() == coordinatorThread.get();
    }

    private <T> T asCoordinatorThread(Supplier<T> operation) {
        Thread current = Thread.currentThread();
        coordinatorThread.compareAndSet(null, current);
        // 单线程 executor 也显式校验线程身份，防止实现漂移破坏可重入判断。
        if (coordinatorThread.get() != current) {
            throw new IllegalStateException("artifact coordinator executor changed threads");
        }
        return locked(operation);
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
}
