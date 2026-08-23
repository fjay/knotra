package io.knotra.events;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.atomic.AtomicLong;

/**
 * EventBus 的默认实现。注册、分发接受、取消订阅和关闭都在写锁下建立线性顺序；分发一旦被接受，
 * 就持有固定监听集合与规范事件绑定，后续注册或取消订阅不会改变本次分发的可见结果。
 *
 * <p>总线可以拥有执行器，也可以复用调用方执行器。关闭总是等待关闭请求被观察到之前已接受的分发；
 * 只有自有执行器会在这些分发收敛后被停止。</p>
 */
final class DefaultEventBus implements EventBus {
    private static final AtomicLong BUS_IDS = new AtomicLong();

    private final String busId = "event-bus-" + BUS_IDS.incrementAndGet();
    private final ClassLoader hostContextClassLoader;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final EventBusRegistry registry;
    // 每个已接受分发对应一个收敛租约；whenIdle/closeAsync 只等待调用前已经进入该队列的租约。
    private final ConcurrentLinkedQueue<CompletableFuture<Void>> acceptedDispatches =
            new ConcurrentLinkedQueue<>();
    private final ExecutorService executor;
    private final boolean ownsExecutor;
    private boolean closed;
    private CompletableFuture<Void> closeFuture;

    /** 创建自带 daemon cached pool 的总线；组件 Activation 当前使用该独立执行器策略。 */
    DefaultEventBus() {
        this.hostContextClassLoader = Thread.currentThread().getContextClassLoader();
        this.registry = new EventBusRegistry(lock);
        this.executor = Executors.newCachedThreadPool(task -> {
            Thread thread = new Thread(task, busId + "-worker");
            thread.setDaemon(true);
            thread.setContextClassLoader(hostContextClassLoader);
            return thread;
        });
        this.ownsExecutor = true;
    }

    /** 复用宿主执行器；线程池尺寸、线程工厂和关闭策略均由宿主装配决定。 */
    DefaultEventBus(ExecutorService executor, boolean ownsExecutor) {
        this.hostContextClassLoader = Thread.currentThread().getContextClassLoader();
        this.registry = new EventBusRegistry(lock);
        this.executor = Objects.requireNonNull(executor, "executor");
        this.ownsExecutor = ownsExecutor;
    }

    @Override
    public String busId() {
        return busId;
    }

    @Override
    public <T> EventSubscription subscribe(
            EventDefinition.Sync<T> definition,
            EventListener<? super T> listener) {
        Objects.requireNonNull(listener, "listener");
        return register(definition, listener);
    }

    @Override
    public <T> EventSubscription subscribe(
            EventDefinition.Parallel<T> definition,
            ParallelEventListener<? super T> listener) {
        Objects.requireNonNull(listener, "listener");
        return register(definition, listener);
    }

    @Override
    public <T> EventSubscription subscribe(
            EventDefinition.Serial<T> definition,
            SerialEventListener<? super T> listener) {
        Objects.requireNonNull(listener, "listener");
        return register(definition, listener);
    }

    @Override
    public <T> EventSubscription subscribe(
            EventDefinition.Bail<T> definition,
            BailEventListener<? super T> listener) {
        Objects.requireNonNull(listener, "listener");
        return register(definition, listener);
    }

    @Override
    public <T> EventSubscription subscribe(
            EventDefinition.Waterfall<T> definition,
            WaterfallEventListener<T> listener) {
        Objects.requireNonNull(listener, "listener");
        return register(definition, listener);
    }

    @Override
    public <T> EventDispatch<T> dispatch(EventDefinition.Sync<T> definition, T event) {
        Accepted<T> accepted = accept(definition, event, EventMode.SYNC);
        // accepted.listeners 已被固化，同步回调即使修改注册表也不会改变本次分发的监听集合。
        try {
            List<EventFailure> failures = new ArrayList<>();
            for (RegisteredSubscription subscription : accepted.listeners()) {
                EventFailure failure = ListenerInvocations.invokeSync(
                        subscription,
                        () -> subscription.<T>syncListener().listen(accepted.event()));
                if (failure != null) {
                    failures.add(failure);
                }
            }
            return EventDispatch.sync(accepted.event(), accepted.listeners().size(), failures);
        } finally {
            finish(accepted);
        }
    }

    @Override
    public <T> CompletionStage<EventDispatch<T>> dispatch(
            EventDefinition.Parallel<T> definition,
            T event) {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(event, "event");
        Accepted<T> accepted;
        try {
            accepted = accept(definition, event, EventMode.PARALLEL);
        } catch (Throwable error) {
            return failed(error);
        }
        try {
            List<CompletableFuture<ListenerOutcome<T>>> futures = new ArrayList<>();
            for (RegisteredSubscription subscription : accepted.listeners()) {
                futures.add(ListenerInvocations.parallel(
                        subscription, accepted.event(), executor));
            }
            // 先提交 accepted 固化出的全部监听，再等待整体收敛，避免一个监听失败短路其他监听。
            CompletableFuture<Void> all = CompletableFuture.allOf(
                    futures.toArray(CompletableFuture[]::new));
            // 所有监听结束后再聚合结果，保证 completedCount 与 failures 描述同一个收敛时刻。
            return all.thenApply(ignored -> {
                int completed = 0;
                List<EventFailure> failures = new ArrayList<>();
                for (CompletableFuture<ListenerOutcome<T>> future : futures) {
                    ListenerOutcome<T> outcome = future.join();
                    if (outcome.failure() == null) {
                        completed++;
                    } else {
                        failures.add(outcome.failure());
                    }
                }
                return new EventDispatch<>(accepted.event(), accepted.event(), EventMode.PARALLEL,
                        accepted.listeners().size(), completed, false, failures);
            }).whenComplete((ignored, error) -> finish(accepted));
        } catch (Throwable error) {
            finish(accepted);
            return failed(error);
        }
    }

    @Override
    public <T> CompletionStage<EventDispatch<T>> dispatch(
            EventDefinition.Serial<T> definition,
            T event) {
        return sequential(definition, event, EventMode.SERIAL);
    }

    @Override
    public <T> CompletionStage<EventDispatch<T>> dispatch(
            EventDefinition.Bail<T> definition,
            T event) {
        return sequential(definition, event, EventMode.BAIL);
    }

    @Override
    public <T> CompletionStage<EventDispatch<T>> dispatch(
            EventDefinition.Waterfall<T> definition,
            T event) {
        return sequential(definition, event, EventMode.WATERFALL);
    }

    private <T> CompletionStage<EventDispatch<T>> sequential(
            EventDefinition<T> definition,
            T event,
            EventMode mode) {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(event, "event");
        Accepted<T> accepted;
        try {
            accepted = accept(definition, event, mode);
        } catch (Throwable error) {
            return failed(error);
        }
        try {
            SequentialListenerInvoker invoker = SequentialListenerInvoker.forMode(mode);
            CompletableFuture<DispatchState<T>> chain = CompletableFuture.completedFuture(
                    DispatchState.initial(accepted.event(), mode, accepted.listeners().size()));
            // 把固定监听集合折叠成一条 stage 链；每个节点基于上一个 DispatchState 决定是否继续。
            for (RegisteredSubscription subscription : accepted.listeners()) {
                chain = chain.thenCompose(next -> next.stopped()
                        ? CompletableFuture.completedFuture(next)
                        : invoker.invoke(subscription, next));
            }
            return chain.thenApply(DispatchState::toDispatch)
                    .whenComplete((ignored, error) -> finish(accepted));
        } catch (Throwable error) {
            finish(accepted);
            return failed(error);
        }
    }

    @Override
    public EventBusSnapshot snapshot() {
        // 读锁内只读取注册表并生成不可变数据，避免 Snapshot 暴露后续变更或部分构建状态。
        lock.readLock().lock();
        try {
            return new EventBusSnapshot(busId, closed, registry.snapshotItems());
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public CompletionStage<Void> whenIdle() {
        // 使用写锁截取待等待租约，保证快照完成前不会有新的分发被接受；空快照可顺带清理空绑定。
        lock.writeLock().lock();
        try {
            List<CompletableFuture<Void>> pending = snapshotAcceptedDispatches();
            if (pending.isEmpty()) {
                registry.pruneIdleBindings();
            }
            return CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new));
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public CompletionStage<Void> closeAsync() {
        List<CompletableFuture<Void>> pending;
        boolean owner = false;
        // 写锁内设置 closed 就是关闭观察边界：此前进入 accept 的分发会被等待，之后的新工作被拒绝。
        lock.writeLock().lock();
        try {
            if (closeFuture == null) {
                closed = true;
                closeFuture = new CompletableFuture<>();
                owner = true;
                pending = snapshotAcceptedDispatches();
                registry.markClosedAndClear();
            } else {
                pending = List.of();
            }
        } finally {
            lock.writeLock().unlock();
        }

        // 只有第一个关闭者负责收敛和停止执行器；后续调用直接复用同一个 closeFuture。
        if (owner) {
            CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new))
                    .whenComplete((ignored, error) -> {
                        if (error != null) {
                            closeFuture.completeExceptionally(error);
                        } else {
                            stopOwnedExecutor();
                        }
                    });
        }
        return closeFuture;
    }

    private void stopOwnedExecutor() {
        if (!ownsExecutor) {
            closeFuture.complete(null);
            return;
        }

        // 此时已接受分发已经收敛；shutdownNow 只用于丢弃不再需要的排队工作，终止结果仍需异步确认。
        executor.shutdownNow();
        Thread terminator = new Thread(() -> {
            try {
                if (!executor.awaitTermination(Long.MAX_VALUE, java.util.concurrent.TimeUnit.NANOSECONDS)) {
                    closeFuture.completeExceptionally(
                            new IllegalStateException("event bus executor did not terminate: " + busId));
                    return;
                }
                closeFuture.complete(null);
            } catch (Throwable error) {
                closeFuture.completeExceptionally(ListenerInvocations.asException(error));
            }
        }, busId + "-close");
        terminator.setDaemon(true);
        terminator.setContextClassLoader(hostContextClassLoader);
        terminator.start();
    }

    private EventSubscription register(
            EventDefinition<?> definition,
            Object listener) {
        Objects.requireNonNull(definition, "definition");
        lock.writeLock().lock();
        try {
            if (closed) {
                throw new IllegalStateException("event bus is closed: " + busId);
            }
            return registry.register(busId, definition, listener);
        } finally {
            lock.writeLock().unlock();
        }
    }

    private <T> Accepted<T> accept(
            EventDefinition<T> definition,
            T event,
            EventMode mode) {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(event, "event");
        lock.writeLock().lock();
        try {
            if (closed) {
                throw new IllegalStateException("event bus is closed: " + busId);
            }
            EventBinding binding = registry.acquireBinding(definition);
            try {
                // 在类型转换和租约入队前占用规范绑定；这条路径上的任何异常都必须走 releaseAcceptedBinding。
                binding.dispatchAccepted();
                List<RegisteredSubscription> matching = registry.matching(
                        definition.name(), binding.eventType(), mode);
                T typedEvent = definition.eventType().cast(event);
                CompletableFuture<Void> dispatch = new CompletableFuture<>();
                for (RegisteredSubscription subscription : matching) {
                    subscription.accept(dispatch);
                }
                // 总线租约最后入队；到这里所有订阅的关闭租约也已记录，跳过的监听同样会被等待。
                acceptedDispatches.add(dispatch);
                return new Accepted<>(typedEvent, matching, dispatch, binding);
            } catch (Throwable error) {
                registry.releaseAcceptedBinding(binding);
                throw error;
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void finish(Accepted<?> accepted) {
        // finish 是收敛点：先移除总线租约并释放规范绑定，再清理订阅租约，最后完成 dispatch。
        lock.writeLock().lock();
        try {
            acceptedDispatches.remove(accepted.dispatch());
            registry.releaseAcceptedBinding(accepted.binding());
        } finally {
            lock.writeLock().unlock();
        }
        for (RegisteredSubscription subscription : accepted.listeners()) {
            subscription.release(accepted.dispatch());
        }
        accepted.dispatch().complete(null);
    }

    private List<CompletableFuture<Void>> snapshotAcceptedDispatches() {
        List<CompletableFuture<Void>> result = new ArrayList<>();
        for (CompletableFuture<Void> dispatch : acceptedDispatches) {
            if (!dispatch.isDone()) {
                result.add(dispatch);
            }
        }
        return result;
    }

    private static <T> CompletionStage<EventDispatch<T>> failed(Throwable error) {
        return CompletableFuture.failedFuture(ListenerInvocations.asException(error));
    }

    /** 一次已接受分发的固定输入：事件值、监听集合、收敛租约与规范事件绑定。 */
    private record Accepted<T>(
            T event,
            List<RegisteredSubscription> listeners,
            CompletableFuture<Void> dispatch,
            EventBinding binding) {

        Accepted {
            listeners = List.copyOf(listeners);
        }
    }
}
