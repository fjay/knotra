package io.knotra.events;

import org.junit.jupiter.api.Test;

import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Collection;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.*;

final class SubscriptionDrainMembershipRaceTest {
    private static final EventDefinition.Parallel<TextEvent> PARALLEL =
            EventDefinition.parallel(TextEvent.class);

    @Test
    void unsubscribeAndFinalReleaseMembershipIsSerialized() throws Exception {
        for (int round = 0; round < 100; round++) {
            EventBus bus = new DefaultEventBus();
            CountDownLatch listenerStarted = new CountDownLatch(1);
            CompletableFuture<Void> gate = new CompletableFuture<>();
            EventSubscription subscription = bus.subscribe(PARALLEL, event -> {
                listenerStarted.countDown();
                return gate;
            });
            var dispatch = bus.dispatch(PARALLEL, new TextEvent("round-" + round))
                    .toCompletableFuture();
            assertTrue(listenerStarted.await(10, TimeUnit.SECONDS));

            RegisteredSubscription internal = (RegisteredSubscription) subscription;
            ReentrantLock membershipLock = field(internal, "membershipLock");
            Queue<AcceptedDispatch> acceptedDispatches = field(internal, "acceptedDispatches");
            Object tracker = field(bus, "drainTracker");
            Collection<?> draining = field(tracker, "draining");

            membershipLock.lock();
            try {
                AcceptedDispatch accepted = acceptedDispatches.iterator().next();
                if ((round & 1) == 0) {
                    // Review交错一：最后一个 release 先清空 pending，inactive 后必须判定无需track。
                    runBlockedAtLock(membershipLock, () -> internal.release(accepted));
                    assertEquals(0, acceptedDispatches.size());
                    runBlockedAtLock(membershipLock, subscription::unsubscribe);
                    assertEquals(0, draining.size());
                } else {
                    // Review交错二：inactive/track 先成立，最后 release 必须在同一临界区内untrack。
                    runBlockedAtLock(membershipLock, subscription::unsubscribe);
                    assertEquals(1, acceptedDispatches.size());
                    assertEquals(1, draining.size());
                    runBlockedAtLock(membershipLock, () -> internal.release(accepted));
                    assertEquals(0, acceptedDispatches.size());
                    assertEquals(0, draining.size());
                }
                assertFalse(subscription.active());
            } finally {
                membershipLock.unlock();
            }

            gate.complete(null);
            dispatch.get(10, TimeUnit.SECONDS);
            subscription.closeAsync().toCompletableFuture().get(10, TimeUnit.SECONDS);
            bus.closeAsync().toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertEquals(0, draining.size());
        }
    }

    @Test
    void highConcurrencyChurnLeavesNoDrainMembership() throws Exception {
        EventBus bus = new DefaultEventBus();
        AtomicInteger dispatchCount = new AtomicInteger();
        ExecutorService workers = Executors.newFixedThreadPool(8);
        try {
            CompletableFuture<?>[] churn = new CompletableFuture<?>[8];
            for (int worker = 0; worker < churn.length; worker++) {
                churn[worker] = CompletableFuture.runAsync(() -> {
                    for (int iteration = 0; iteration < 100; iteration++) {
                        EventSubscription subscription = bus.subscribe(PARALLEL, event -> {
                            dispatchCount.incrementAndGet();
                            return CompletableFuture.completedFuture(null);
                        });
                        try {
                            if ((iteration & 1) == 0) {
                                bus.dispatch(PARALLEL, new TextEvent("dispatch"))
                                        .toCompletableFuture().get(10, TimeUnit.SECONDS);
                            } else {
                                subscription.unsubscribe();
                                bus.dispatch(PARALLEL, new TextEvent("inactive"))
                                        .toCompletableFuture().get(10, TimeUnit.SECONDS);
                            }
                        } catch (Exception error) {
                            throw new AssertionError(error);
                        } finally {
                            subscription.unsubscribe();
                            subscription.closeAsync().toCompletableFuture().join();
                        }
                    }
                }, workers);
            }
            CompletableFuture.allOf(churn).get(30, TimeUnit.SECONDS);
            bus.whenIdle().toCompletableFuture().get(10, TimeUnit.SECONDS);
        } finally {
            workers.shutdown();
            assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS));
            bus.close();
        }

        assertEquals(0, bus.pendingOperations().operations().size());
        Collection<?> draining = field(field(bus, "drainTracker"), "draining");
        assertEquals(0, draining.size());
        assertTrue(dispatchCount.get() > 0);
    }

    @Test
    void drainedSubscriptionListenerAndLoaderAreCollectable() throws Exception {
        URLClassLoader listenerLoader = new URLClassLoader(new URL[0],
                SubscriptionDrainMembershipRaceTest.class.getClassLoader());
        CountDownLatch started = new CountDownLatch(1);
        CompletableFuture<Void> gate = new CompletableFuture<>();
        ParallelEventListener<TextEvent> listener = (ParallelEventListener<TextEvent>)
                Proxy.newProxyInstance(
                        listenerLoader,
                        new Class<?>[]{ParallelEventListener.class},
                        (proxy, method, args) -> {
                            started.countDown();
                            return gate;
                        });
        EventBus bus = new DefaultEventBus();
        EventSubscription subscription = bus.subscribe(PARALLEL, listener);
        var dispatch = bus.dispatch(PARALLEL, new TextEvent("collectable"))
                .toCompletableFuture();
        assertTrue(started.await(10, TimeUnit.SECONDS));
        subscription.unsubscribe();

        WeakReference<EventSubscription> subscriptionReference = new WeakReference<>(subscription);
        WeakReference<Object> listenerReference = new WeakReference<>(listener);
        WeakReference<ClassLoader> loaderReference = new WeakReference<>(listenerLoader);
        WeakReference<EventBus> busReference = new WeakReference<>(bus);
        subscription = null;
        listener = null;
        listenerLoader = null;

        gate.complete(null);
        dispatch.get(10, TimeUnit.SECONDS);
        busReference.get().close();
        dispatch = null;
        bus = null;

        assertTrue(awaitCleared(subscriptionReference, listenerReference, loaderReference, busReference),
                "drain tracker must not retain subscription, listener, loader, or bus");
    }

    private static void runBlockedAtLock(ReentrantLock lock, Runnable action) throws Exception {
        CountDownLatch threadStarted = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            threadStarted.countDown();
            action.run();
        }, "subscription-membership-barrier");
        worker.start();
        assertTrue(threadStarted.await(10, TimeUnit.SECONDS));
        assertTrue(waitUntil(() -> lock.hasQueuedThread(worker)),
                "worker did not reach membership lock");

        lock.unlock();
        try {
            worker.join(10_000);
        } finally {
            lock.lock();
        }
        assertFalse(worker.isAlive(), "worker did not finish membership transition");
    }

    private static boolean waitUntil(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(1);
        }
        return false;
    }

    private static boolean awaitCleared(Reference<?>... references) {
        for (int attempt = 0; attempt < 200; attempt++) {
            System.gc();
            boolean cleared = true;
            for (Reference<?> reference : references) {
                cleared &= reference.get() == null;
            }
            if (cleared) {
                return true;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private static <T> T field(Object owner, String name) throws ReflectiveOperationException {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return (T) field.get(owner);
    }

    private record TextEvent(String text) {
    }
}
