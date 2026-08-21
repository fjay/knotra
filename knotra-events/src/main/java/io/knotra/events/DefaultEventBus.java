package io.knotra.events;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.atomic.AtomicBoolean;
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
    private final Map<SubscriptionIndex, List<RegisteredSubscription>> subscriptions = new HashMap<>();
    // 事件名到规范 JVM Class 的绑定。只要订阅或已接受分发仍引用该名称，绑定就必须保留。
    private final Map<String, EventBinding> eventBindings = new HashMap<>();
    // 每个已接受分发对应一个收敛租约；whenIdle/closeAsync 只等待调用前已经进入该队列的租约。
    private final ConcurrentLinkedQueue<CompletableFuture<Void>> acceptedDispatches =
            new ConcurrentLinkedQueue<>();
    private final AtomicLong sequences = new AtomicLong();
    private final ExecutorService executor;
    private final boolean ownsExecutor;
    private boolean closed;
    private CompletableFuture<Void> closeFuture;

    DefaultEventBus() {
        this.hostContextClassLoader = Thread.currentThread().getContextClassLoader();
        this.executor = Executors.newCachedThreadPool(task -> {
            Thread thread = new Thread(task, busId + "-worker");
            thread.setDaemon(true);
            thread.setContextClassLoader(hostContextClassLoader);
            return thread;
        });
        this.ownsExecutor = true;
    }

    DefaultEventBus(ExecutorService executor, boolean ownsExecutor) {
        this.hostContextClassLoader = Thread.currentThread().getContextClassLoader();
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
                try {
                    EventListener<? super T> listener = listener(subscription);
                    withListenerContext(subscription, () -> {
                        listener.listen(accepted.event());
                        return null;
                    });
                } catch (Throwable error) {
                    failures.add(failure(subscription, error));
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
        Accepted<T> accepted = accept(definition, event, EventMode.PARALLEL);
        try {
            List<CompletableFuture<ListenerOutcome<T>>> futures = new ArrayList<>();
            for (RegisteredSubscription subscription : accepted.listeners()) {
                futures.add(parallelListener(subscription, accepted.event()));
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
        Accepted<T> accepted = accept(definition, event, mode);
        try {
            DispatchState<T> state = DispatchState.initial(
                    accepted.event(), mode, accepted.listeners().size());
            // 把固定监听集合折叠成一条 stage 链；每个节点基于上一个 DispatchState 决定是否继续。
            for (RegisteredSubscription subscription : accepted.listeners()) {
                state = composeListener(state, subscription);
            }
            return state.future().thenApply(DispatchState::toDispatch)
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
            List<EventBusSnapshot.Item> items = new ArrayList<>();
            for (List<RegisteredSubscription> listeners : subscriptions.values()) {
                for (RegisteredSubscription subscription : listeners) {
                    if (subscription.active()) {
                        items.add(subscription.toSnapshotItem());
                    }
                }
            }
            items.sort(Comparator
                    .comparing(EventBusSnapshot.Item::eventName)
                    .thenComparing(item -> item.mode().ordinal())
                    .thenComparingLong(EventBusSnapshot.Item::sequence));
            return new EventBusSnapshot(busId, closed, items);
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
                pruneIdleEventBindings();
            }
            return whenDispatchesSettle(pending);
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
                for (List<RegisteredSubscription> listeners : subscriptions.values()) {
                    for (RegisteredSubscription subscription : listeners) {
                        subscription.markClosed();
                    }
                }
                subscriptions.clear();
                // 只清空注册表；已接受分发仍直接持有 EventBinding，可等待结束后自行释放规范身份。
                eventBindings.clear();
            } else {
                pending = List.of();
            }
        } finally {
            lock.writeLock().unlock();
        }

        // 只有第一个关闭者负责收敛和停止执行器；后续调用直接复用同一个 closeFuture。
        if (owner) {
            whenDispatchesSettle(pending).whenComplete((ignored, error) -> {
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
                closeFuture.completeExceptionally(asException(error));
            }
        }, busId + "-close");
        terminator.setDaemon(true);
        terminator.setContextClassLoader(hostContextClassLoader);
        terminator.start();
    }

    private <T> DispatchState<T> composeListener(
            DispatchState<T> state,
            RegisteredSubscription subscription) {
        CompletableFuture<DispatchState<T>> current = state.future();
        // 各模式共用状态机：已完成计数、失败列表和停止位沿 stage 链传递，监听失败不会让外层 stage 异常完成。
        switch (state.mode()) {
            case SERIAL -> {
                return DispatchState.of(state, current.thenCompose(next -> {
                    if (next.stopped()) {
                        return CompletableFuture.completedFuture(next);
                    }
                    return serialListener(subscription, next).thenApply(outcome ->
                            next.afterListener(outcome.continueDispatch(), outcome.failure()));
                }));
            }
            case BAIL -> {
                return DispatchState.of(state, current.thenCompose(next -> {
                    if (next.stopped()) {
                        return CompletableFuture.completedFuture(next);
                    }
                    // bail 的返回值是“是否认领”，状态机需要反转为“是否继续”，认领即停止。
                    return bailListener(subscription, next).thenApply(outcome ->
                            next.afterListener(!outcome.continueDispatch(), outcome.failure()));
                }));
            }
            case WATERFALL -> {
                return DispatchState.of(state, current.thenCompose(next -> {
                    if (next.stopped()) {
                        return CompletableFuture.completedFuture(next);
                    }
                    return waterfallListener(subscription, next).thenApply(outcome ->
                            next.afterTransform(outcome.event(), outcome.failure()));
                }));
            }
            default -> throw new IllegalArgumentException(
                    "unsupported sequential mode: " + state.mode());
        }
    }

    private EventSubscription register(
            EventDefinition<?> definition,
            Object listener) {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(listener, "listener");

        lock.writeLock().lock();
        try {
            if (closed) {
                throw new IllegalStateException("event bus is closed: " + busId);
            }
            // 注册前获取规范绑定，防止同名事件在存活期间被另一个 ClassLoader 的同名 Class 重绑。
            EventBinding binding = acquireEventBinding(definition);
            Class<?> eventType = binding.eventType();
            RegisteredSubscription subscription = new RegisteredSubscription(
                    "event-subscription-" + busId + "-" + sequences.incrementAndGet(),
                    definition,
                    listener);
            subscriptions.computeIfAbsent(
                            new SubscriptionIndex(definition.name(), eventType, definition.mode()),
                            ignored -> new ArrayList<>())
                    .add(subscription);
            binding.subscriptionRegistered();
            return subscription;
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
            EventBinding binding = acquireEventBinding(definition);
            try {
                // 在类型转换和租约入队前占用规范绑定；这条路径上的任何异常都必须走 releaseAcceptedBinding。
                binding.dispatchAccepted();
                Class<?> eventType = binding.eventType();
                // 写锁下复制监听集合，随后 unsubscribe 不影响本次分发，但会影响下一次分发。
                List<RegisteredSubscription> matching = subscriptions
                        .getOrDefault(new SubscriptionIndex(definition.name(), eventType, mode), List.of())
                        .stream()
                        .toList();
                T typedEvent = definition.eventType().cast(event);
                CompletableFuture<Void> dispatch = new CompletableFuture<>();
                for (RegisteredSubscription subscription : matching) {
                    subscription.accept(dispatch);
                }
                // 总线租约最后入队；到这里所有订阅的关闭租约也已记录，跳过的监听同样会被等待。
                acceptedDispatches.add(dispatch);
                return new Accepted<>(typedEvent, matching, dispatch, binding);
            } catch (Throwable error) {
                releaseAcceptedBinding(binding);
                throw error;
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    private EventBinding acquireEventBinding(EventDefinition<?> definition) {
        Class<?> candidate = definition.eventType();
        EventBinding binding = eventBindings.get(definition.name());
        // 按事件名查找规范 Class 绑定；绑定存在时后续判断必须使用对象身份。
        if (binding == null) {
            binding = new EventBinding(candidate);
            eventBindings.put(definition.name(), binding);
            // 必须用对象身份比较 Class：不同 artifact ClassLoader 中的同名 Class 不是同一事件身份。
        } else if (binding.eventType() != candidate) {
            throw new IllegalArgumentException("event name is already bound to a different Class: "
                    + definition.name() + " ["
                    + identity(binding.eventType()) + " != " + identity(candidate) + "]");
        }
        return binding;
    }

    private void releaseAcceptedBinding(EventBinding binding) {
        // 分发计数归零且无订阅才移除绑定，避免在途分发期间允许同名不同 Class 重绑。
        if (binding.dispatchFinished()) {
            eventBindings.values().removeIf(current -> current == binding && current.isIdle());
        }
    }

    private void pruneIdleEventBindings() {
        eventBindings.values().removeIf(EventBinding::isIdle);
    }

    private static String identity(Class<?> type) {
        return type.getName() + "@" + System.identityHashCode(type);
    }

    private void finish(Accepted<?> accepted) {
        // finish 是收敛点：先移除总线租约并释放规范绑定，再清理订阅租约，最后完成 dispatch。
        lock.writeLock().lock();
        try {
            acceptedDispatches.remove(accepted.dispatch());
            releaseAcceptedBinding(accepted.binding());
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

    private static CompletionStage<Void> whenDispatchesSettle(
            List<CompletableFuture<Void>> dispatches) {
        return CompletableFuture.allOf(dispatches.toArray(CompletableFuture[]::new));
    }

    private EventFailure failure(RegisteredSubscription subscription, Throwable error) {
        return new EventFailure(
                subscription.subscriptionId(),
                subscription.eventName(),
                subscription.eventTypeName(),
                subscription.mode(),
                EventFailureText.describe(error));
    }

    @SuppressWarnings("unchecked")
    private <T> EventListener<? super T> listener(RegisteredSubscription subscription) {
        verifyMode(subscription, EventMode.SYNC);
        return (EventListener<? super T>) subscription.listener();
    }

    private <T> CompletableFuture<ListenerOutcome<T>> parallelListener(
            RegisteredSubscription subscription,
            T event) {
        try {
            // supplyAsync 的同步抛错和返回 stage 的异步失败都归一为 ListenerOutcome，供最终聚合。
            CompletableFuture<CompletionStage<ListenerOutcome<T>>> submitted =
                    CompletableFuture.supplyAsync(() -> {
                        try {
                            ParallelEventListener<? super T> listener =
                                    parallelListenerTyped(subscription);
                            CompletionStage<Void> stage = withListenerContext(
                                    subscription, (ContextCallback<CompletionStage<Void>>) () ->
                                            listener.listen(event));
                            Objects.requireNonNull(stage, "listener returned a null completion stage");
                            return stage.thenApply(ignored -> ListenerOutcome.<T>success());
                        } catch (Throwable error) {
                            return CompletableFuture.completedFuture(
                                    ListenerOutcome.<T>failure(failure(subscription, error)));
                        }
                    }, executor);
            return submitted.thenCompose(outcome -> outcome)
                    .exceptionally(error -> ListenerOutcome.failure(
                            failure(subscription, asException(error))));
        } catch (Throwable error) {
            return CompletableFuture.completedFuture(
                    ListenerOutcome.failure(failure(subscription, asException(error))));
        }
    }

    @SuppressWarnings("unchecked")
    private <T> ParallelEventListener<? super T> parallelListenerTyped(
            RegisteredSubscription subscription) {
        verifyMode(subscription, EventMode.PARALLEL);
        return (ParallelEventListener<? super T>) subscription.listener();
    }

    private <T> CompletionStage<ListenerOutcome<T>> serialListener(
            RegisteredSubscription subscription,
            DispatchState<T> state) {
        try {
            SerialEventListener<? super T> listener = serialListenerTyped(subscription);
            CompletionStage<Boolean> stage = withListenerContext(
                    subscription, (ContextCallback<CompletionStage<Boolean>>) () ->
                            listener.listen(state.event()));
            Objects.requireNonNull(stage, "listener returned a null completion stage");
            CompletionStage<ListenerOutcome<T>> outcome = stage.thenApply(continueDispatch ->
                    new ListenerOutcome<>(continueDispatch, null, null));
            return outcome.exceptionally(error -> ListenerOutcome.<T>failure(
                    failure(subscription, asException(error))));
        } catch (Throwable error) {
            return CompletableFuture.completedFuture(ListenerOutcome.failure(
                    failure(subscription, error)));
        }
    }

    @SuppressWarnings("unchecked")
    private <T> SerialEventListener<? super T> serialListenerTyped(
            RegisteredSubscription subscription) {
        verifyMode(subscription, EventMode.SERIAL);
        return (SerialEventListener<? super T>) subscription.listener();
    }

    private <T> CompletionStage<ListenerOutcome<T>> bailListener(
            RegisteredSubscription subscription,
            DispatchState<T> state) {
        try {
            BailEventListener<? super T> listener = bailListenerTyped(subscription);
            boolean claimed = withListenerContext(
                    subscription, () -> listener.bail(state.event()));
            return CompletableFuture.completedFuture(new ListenerOutcome<>(claimed, null, null));
        } catch (Throwable error) {
            return CompletableFuture.completedFuture(ListenerOutcome.failure(
                    failure(subscription, error)));
        }
    }

    @SuppressWarnings("unchecked")
    private <T> BailEventListener<? super T> bailListenerTyped(
            RegisteredSubscription subscription) {
        verifyMode(subscription, EventMode.BAIL);
        return (BailEventListener<? super T>) subscription.listener();
    }

    private <T> CompletionStage<ListenerOutcome<T>> waterfallListener(
            RegisteredSubscription subscription,
            DispatchState<T> state) {
        try {
            WaterfallEventListener<T> listener = waterfallListenerTyped(subscription);
            CompletionStage<T> stage = withListenerContext(
                    subscription, (ContextCallback<CompletionStage<T>>) () ->
                            listener.transform(state.event()));
            Objects.requireNonNull(stage, "listener returned a null completion stage");
            CompletionStage<ListenerOutcome<T>> outcome = stage.thenApply(value ->
                    new ListenerOutcome<>(true, value, null));
            return outcome.exceptionally(error -> ListenerOutcome.<T>failure(
                    failure(subscription, asException(error))));
        } catch (Throwable error) {
            return CompletableFuture.completedFuture(ListenerOutcome.failure(
                    failure(subscription, error)));
        }
    }

    @SuppressWarnings("unchecked")
    private <T> WaterfallEventListener<T> waterfallListenerTyped(
            RegisteredSubscription subscription) {
        verifyMode(subscription, EventMode.WATERFALL);
        return (WaterfallEventListener<T>) subscription.listener();
    }

    private <R> R withListenerContext(
            RegisteredSubscription subscription,
            ContextCallback<R> callback) throws Throwable {
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        // 回调期间切换到监听实现的 ClassLoader，finally 恢复调用线程原状态，避免污染执行器线程。
        Thread.currentThread().setContextClassLoader(subscription.listenerClassLoader());
        try {
            return callback.run();
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    private void verifyMode(RegisteredSubscription subscription, EventMode mode) {
        if (subscription.mode() != mode) {
            throw new IllegalStateException(
                    "internal event subscription mode mismatch: expected " + mode
                            + " but was " + subscription.mode());
        }
    }

    private static Exception asException(Throwable error) {
        Throwable cause = error;
        // 层层组合 stage 会引入 Completion/Execution 包装，诊断和异常完成都应面向原始原因。
        while ((cause instanceof java.util.concurrent.CompletionException
                || cause instanceof java.util.concurrent.ExecutionException)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause instanceof Exception exception ? exception : new IllegalStateException(cause);
    }

    private static <T> CompletionStage<EventDispatch<T>> failed(Throwable error) {
        CompletableFuture<EventDispatch<T>> future = new CompletableFuture<>();
        future.completeExceptionally(asException(error));
        return future;
    }

    private interface ContextCallback<R> {
        R run() throws Throwable;
    }

    private record SubscriptionIndex(String name, Class<?> eventType, EventMode mode) {
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

    /** 单个监听的模式化结果：串行的继续标记、瀑布的下一个事件值或失败诊断。 */
    private record ListenerOutcome<T>(
            boolean continueDispatch,
            T event,
            EventFailure failure) {

        static <T> ListenerOutcome<T> success() {
            return new ListenerOutcome<>(true, null, null);
        }

        static <T> ListenerOutcome<T> failure(EventFailure failure) {
            return new ListenerOutcome<>(false, null, failure);
        }
    }

    /**
     * 串行、应急与瀑布共享的不可变分发状态。每执行一个监听就派生下一个状态，并沿同一个
     * completion chain 传递；因此停止、失败和完成计数不会受并发注册或取消订阅影响。
     */
    private static final class DispatchState<T> {
        private final T initialEvent;
        private final T event;
        private final EventMode mode;
        private final int listenerCount;
        private final int completed;
        private final boolean stopped;
        private final List<EventFailure> failures;
        private final CompletableFuture<DispatchState<T>> future;

        private DispatchState(
                T initialEvent,
                T event,
                EventMode mode,
                int listenerCount,
                int completed,
                boolean stopped,
                List<EventFailure> failures,
                CompletableFuture<DispatchState<T>> future) {
            this.initialEvent = initialEvent;
            this.event = event;
            this.mode = mode;
            this.listenerCount = listenerCount;
            this.completed = completed;
            this.stopped = stopped;
            this.failures = List.copyOf(failures);
            this.future = future;
        }

        private static <T> DispatchState<T> initial(T event, EventMode mode, int listenerCount) {
            CompletableFuture<DispatchState<T>> future = new CompletableFuture<>();
            DispatchState<T> state = new DispatchState<>(
                    event, event, mode, listenerCount, 0, false, List.of(), future);
            future.complete(state);
            return state;
        }

        private static <T> DispatchState<T> of(
                DispatchState<T> previous,
                CompletableFuture<DispatchState<T>> future) {
            return new DispatchState<>(
                    previous.initialEvent,
                    previous.event,
                    previous.mode,
                    previous.listenerCount,
                    previous.completed,
                    previous.stopped,
                    previous.failures,
                    future);
        }

        private T initialEvent() {
            return initialEvent;
        }

        private T event() {
            return event;
        }

        private EventMode mode() {
            return mode;
        }

        private int listenerCount() {
            return listenerCount;
        }

        private int completed() {
            return completed;
        }

        private boolean stopped() {
            return stopped;
        }

        private List<EventFailure> failures() {
            return failures;
        }

        private CompletableFuture<DispatchState<T>> future() {
            return future;
        }

        /** 记录串行/应急监听结果：失败不计完成并停止，无错误停止只标记 stopped。 */
        private DispatchState<T> afterListener(boolean continueDispatch, EventFailure failure) {
            boolean failed = failure != null;
            return new DispatchState<>(
                    initialEvent,
                    event,
                    mode,
                    listenerCount,
                    failed ? completed : completed + 1,
                    failed || !continueDispatch,
                    failed ? append(failure) : failures,
                    CompletableFuture.completedFuture(null));
        }

        /** 瀑布监听成功才推进事件值；失败保留上一个成功值并停止后续变换。 */
        private DispatchState<T> afterTransform(T nextEvent, EventFailure failure) {
            boolean failed = failure != null;
            return new DispatchState<>(
                    initialEvent,
                    failed ? event : nextEvent,
                    mode,
                    listenerCount,
                    failed ? completed : completed + 1,
                    failed,
                    failed ? append(failure) : failures,
                    CompletableFuture.completedFuture(null));
        }

        private List<EventFailure> append(EventFailure failure) {
            List<EventFailure> next = new ArrayList<>(failures);
            next.add(failure);
            return next;
        }

        private EventDispatch<T> toDispatch() {
            return new EventDispatch<>(
                    initialEvent,
                    event,
                    mode,
                    listenerCount,
                    completed,
                    stopped,
                    failures);
        }
    }

    /** 事件名当前锁定的规范 JVM Class，以及仍引用该身份的订阅和分发计数。 */
    private static final class EventBinding {
        private final Class<?> eventType;
        private int subscriptions;
        private int dispatches;

        private EventBinding(Class<?> eventType) {
            this.eventType = eventType;
        }

        private Class<?> eventType() {
            return eventType;
        }

        private void subscriptionRegistered() {
            subscriptions++;
        }

        private void subscriptionRemoved() {
            subscriptions--;
        }

        private void dispatchAccepted() {
            dispatches++;
        }

        private boolean dispatchFinished() {
            return --dispatches == 0;
        }

        private boolean isIdle() {
            return subscriptions == 0 && dispatches == 0;
        }
    }

    /**
     * 总线内部的一次注册。除注册表条目外，还记录每个订阅已接受的分发租约，
     * 使订阅关闭可以独立等待自己的在途工作。
     */
    private final class RegisteredSubscription implements EventSubscription {
        private final String subscriptionId;
        private final EventDefinition<?> definition;
        private final Object listener;
        private final AtomicBoolean active = new AtomicBoolean(true);
        private final ConcurrentLinkedQueue<CompletableFuture<Void>> acceptedDispatches =
                new ConcurrentLinkedQueue<>();
        private CompletableFuture<Void> closeFuture;

        private RegisteredSubscription(
                String subscriptionId,
                EventDefinition<?> definition,
                Object listener) {
            this.subscriptionId = subscriptionId;
            this.definition = definition;
            this.listener = listener;
        }

        @Override
        public String subscriptionId() {
            return subscriptionId;
        }

        @Override
        public String eventName() {
            return definition.name();
        }

        @Override
        public EventMode mode() {
            return definition.mode();
        }

        @Override
        public long sequence() {
            // 订阅 ID 内嵌写锁下分配的原子序号；从唯一标识解析可避免维护第二份排序来源。
            return Long.parseLong(subscriptionId.substring(subscriptionId.lastIndexOf('-') + 1));
        }

        @Override
        public boolean active() {
            return active.get();
        }

        @Override
        public void unsubscribe() {
            // 先原子抢占状态，保证重复取消和回调内自取消都幂等，同时避免阻塞在总线写锁上。
            if (!active.compareAndSet(true, false)) {
                return;
            }
            lock.writeLock().lock();
            try {
                SubscriptionIndex index = new SubscriptionIndex(
                        eventName(), eventType(), mode());
                List<RegisteredSubscription> listeners = subscriptions.get(index);
                if (listeners != null && listeners.remove(this)) {
                    EventBinding binding = eventBindings.get(eventName());
                    if (binding != null) {
                        binding.subscriptionRemoved();
                        if (binding.isIdle()) {
                            eventBindings.remove(eventName(), binding);
                        }
                    }
                    if (listeners.isEmpty()) {
                        subscriptions.remove(index);
                    }
                }
            } finally {
                lock.writeLock().unlock();
            }
        }

        @Override
        public synchronized CompletionStage<Void> closeAsync() {
            unsubscribe();
            // unsubscribe 只影响未来分发；这里快照并等待本订阅在关闭观察前已接受的租约。
            if (closeFuture == null) {
                List<CompletableFuture<Void>> pending = new ArrayList<>();
                for (CompletableFuture<Void> dispatch : acceptedDispatches) {
                    if (!dispatch.isDone()) {
                        pending.add(dispatch);
                    }
                }
                closeFuture = CompletableFuture.allOf(
                        pending.toArray(CompletableFuture[]::new));
            }
            return closeFuture;
        }

        private void accept(CompletableFuture<Void> dispatch) {
            // 即使串行/瀑布链最终跳过该监听，已入队的租约也由 finish 统一释放。
            acceptedDispatches.add(dispatch);
        }

        private void release(CompletableFuture<Void> dispatch) {
            acceptedDispatches.remove(dispatch);
        }

        private Object listener() {
            return listener;
        }

        private ClassLoader listenerClassLoader() {
            // 使用监听实现的 ClassLoader，而不是事件类型或总线创建者的 ClassLoader。
            return listener.getClass().getClassLoader();
        }

        private Class<?> eventType() {
            return definition.eventType();
        }

        private String eventTypeName() {
            return definition.eventType().getName();
        }

        private void markClosed() {
            active.set(false);
        }

        private EventBusSnapshot.Item toSnapshotItem() {
            return new EventBusSnapshot.Item(
                    subscriptionId,
                    definition.name(),
                    eventTypeName(),
                    definition.mode(),
                    sequence());
        }
    }
}
