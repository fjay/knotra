package io.knotra.events;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.ReentrantLock;
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
    private volatile boolean active = true;
    private final AtomicBoolean unsubscribeRequested = new AtomicBoolean(false);
    // 锁序固定为总线写锁 -> membershipLock；release 只取 membershipLock。
    private final ReentrantLock membershipLock = new ReentrantLock();
    private final ConcurrentLinkedQueue<AcceptedDispatch> acceptedDispatches =
            new ConcurrentLinkedQueue<>();
    private final SubscriptionDrainTracker drainTracker;
    private CompletableFuture<Void> closeFuture;

    RegisteredSubscription(
            String subscriptionId,
            long sequence,
            EventDefinition<?> definition,
            Object listener,
            ReentrantReadWriteLock lock,
            RemovalCallback removalCallback,
            SubscriptionDrainTracker drainTracker) {
        this.subscriptionId = Objects.requireNonNull(subscriptionId, "subscriptionId");
        this.sequence = sequence;
        this.definition = Objects.requireNonNull(definition, "definition");
        this.listener = Objects.requireNonNull(listener, "listener");
        this.lock = Objects.requireNonNull(lock, "lock");
        this.removalCallback = Objects.requireNonNull(removalCallback, "removalCallback");
        this.drainTracker = Objects.requireNonNull(drainTracker, "drainTracker");
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
        return active;
    }

    @Override
    public void unsubscribe() {
        // 只用原子标志合并重复取消；active 的发布点在总线写锁内，
        // 保证 inactive 后不再接受新分发。
        if (!unsubscribeRequested.compareAndSet(false, true)) {
            return;
        }
        lock.writeLock().lock();
        try {
            try {
                removalCallback.remove(this);
            } finally {
                deactivateForDrain();
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
            closeFuture = CompletableFuture.allOf(pendingDispatches().stream()
                    .map(AcceptedDispatch::settled)
                    .toArray(CompletableFuture[]::new));
        }
        return closeFuture;
    }

    void accept(AcceptedDispatch dispatch) {
        // 即使串行/瀑布链最终跳过该监听，已入队的租约也由 finish 统一释放。
        membershipLock.lock();
        try {
            acceptedDispatches.add(dispatch);
        } finally {
            membershipLock.unlock();
        }
    }

    void release(AcceptedDispatch dispatch) {
        membershipLock.lock();
        try {
            acceptedDispatches.remove(dispatch);
            if (!active && acceptedDispatches.isEmpty()) {
                drainTracker.untrack(this);
            }
        } finally {
            membershipLock.unlock();
        }
    }

    List<AcceptedDispatch> pendingDispatches() {
        membershipLock.lock();
        try {
            List<AcceptedDispatch> result = new ArrayList<>();
            for (AcceptedDispatch dispatch : acceptedDispatches) {
                if (!dispatch.settled().isDone()) {
                    result.add(dispatch);
                }
            }
            return result;
        } finally {
            membershipLock.unlock();
        }
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
        deactivateForDrain();
    }

    /**
     * 必须在总线写锁内调用：accept 与注册表移除同样持有该锁，
     * 因此 active=false 后不会再有新的 accept。
     */
    private void deactivateForDrain() {
        membershipLock.lock();
        try {
            active = false;
            if (acceptedDispatches.isEmpty()) {
                drainTracker.untrack(this);
            } else {
                drainTracker.track(this);
            }
        } finally {
            membershipLock.unlock();
        }
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
