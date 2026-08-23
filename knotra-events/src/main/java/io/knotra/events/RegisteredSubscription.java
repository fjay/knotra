package io.knotra.events;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 总线内部的一次注册。除注册表条目外，还记录每个订阅已接受的分发租约，
 * 使订阅关闭可以独立等待自己的在途工作。
 */
final class RegisteredSubscription implements EventSubscription {
    private final String subscriptionId;
    private final long sequence;
    private final EventDefinition<?> definition;
    private final Object listener;
    private final ReentrantReadWriteLock lock;
    private final RemovalCallback removalCallback;
    private final AtomicBoolean active = new AtomicBoolean(true);
    private final ConcurrentLinkedQueue<CompletableFuture<Void>> acceptedDispatches =
            new ConcurrentLinkedQueue<>();
    private CompletableFuture<Void> closeFuture;

    RegisteredSubscription(
            String subscriptionId,
            long sequence,
            EventDefinition<?> definition,
            Object listener,
            ReentrantReadWriteLock lock,
            RemovalCallback removalCallback) {
        this.subscriptionId = Objects.requireNonNull(subscriptionId, "subscriptionId");
        this.sequence = sequence;
        this.definition = Objects.requireNonNull(definition, "definition");
        this.listener = Objects.requireNonNull(listener, "listener");
        this.lock = Objects.requireNonNull(lock, "lock");
        this.removalCallback = Objects.requireNonNull(removalCallback, "removalCallback");
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
        return sequence;
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
            removalCallback.remove(this);
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
            closeFuture = CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new));
        }
        return closeFuture;
    }

    void accept(CompletableFuture<Void> dispatch) {
        // 即使串行/瀑布链最终跳过该监听，已入队的租约也由 finish 统一释放。
        acceptedDispatches.add(dispatch);
    }

    void release(CompletableFuture<Void> dispatch) {
        acceptedDispatches.remove(dispatch);
    }

    @SuppressWarnings("unchecked")
    <T> EventListener<? super T> syncListener() {
        verifyMode(EventMode.SYNC);
        return (EventListener<? super T>) listener;
    }

    @SuppressWarnings("unchecked")
    <T> ParallelEventListener<? super T> parallelListener() {
        verifyMode(EventMode.PARALLEL);
        return (ParallelEventListener<? super T>) listener;
    }

    @SuppressWarnings("unchecked")
    <T> SerialEventListener<? super T> serialListener() {
        verifyMode(EventMode.SERIAL);
        return (SerialEventListener<? super T>) listener;
    }

    @SuppressWarnings("unchecked")
    <T> BailEventListener<? super T> bailListener() {
        verifyMode(EventMode.BAIL);
        return (BailEventListener<? super T>) listener;
    }

    @SuppressWarnings("unchecked")
    <T> WaterfallEventListener<T> waterfallListener() {
        verifyMode(EventMode.WATERFALL);
        return (WaterfallEventListener<T>) listener;
    }

    ClassLoader listenerClassLoader() {
        // 使用监听实现的 ClassLoader，而不是事件类型或总线创建者的 ClassLoader。
        return listener.getClass().getClassLoader();
    }

    Class<?> definitionEventType() {
        return definition.eventType();
    }

    String eventTypeName() {
        return definition.eventType().getName();
    }

    EventBusSnapshot.Item toSnapshotItem() {
        return new EventBusSnapshot.Item(
                subscriptionId,
                definition.name(),
                definition.eventType().getName(),
                definition.mode(),
                sequence);
    }

    void markClosed() {
        active.set(false);
    }

    private void verifyMode(EventMode expectedMode) {
        if (definition.mode() != expectedMode) {
            throw new IllegalStateException(
                    "internal event subscription mode mismatch: expected " + expectedMode
                            + " but was " + definition.mode());
        }
    }

    /** 订阅从所属注册表移除时需要同步更新的事件绑定等状态。 */
    interface RemovalCallback {
        void remove(RegisteredSubscription subscription);
    }
}
