package io.knotra.events;

import io.knotra.PendingOperationsSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

final class EventBusPendingOperationsTest {
    private static final EventDefinition.Parallel<TextEvent> PARALLEL =
            EventDefinition.parallel(TextEvent.class);
    private static final EventDefinition.Serial<TextEvent> SERIAL =
            EventDefinition.serial(TextEvent.class);

    private EventBus bus;
    private ExecutorService readerPool;

    @BeforeEach
    void setUp() {
        bus = new DefaultEventBus();
        readerPool = Executors.newFixedThreadPool(16);
    }

    @AfterEach
    void tearDown() throws Exception {
        readerPool.shutdown();
        assertTrue(readerPool.awaitTermination(10, TimeUnit.SECONDS));
        bus.close();
    }

    @Test
    void serialGateShowsEventDispatchOperation() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CompletableFuture<Boolean> gate = new CompletableFuture<>();
        bus.subscribe(SERIAL, event -> {
            started.countDown();
            return gate;
        });

        var dispatch = bus.dispatch(SERIAL, new TextEvent("held"))
                .toCompletableFuture();
        assertTrue(started.await(10, TimeUnit.SECONDS));

        PendingOperationsSnapshot.Operation operation = assertTimeoutPreemptively(
                Duration.ofMillis(500), () -> requiredOperation(
                        bus.pendingOperations(),
                        item -> item.kind() == PendingOperationsSnapshot.Kind.EVENT_DISPATCH));
        assertEquals(PendingOperationsSnapshot.WaitType.LISTENER, operation.waitsFor());
        assertTrue(operation.targetId().startsWith("event-dispatch-"));
        assertTrue(operation.detail().contains("event=" + TextEvent.class.getName()));
        assertTrue(operation.detail().contains("mode=SERIAL"));
        assertTrue(operation.detail().contains("listeners=1"));
        assertFalse(operation.age().isNegative());

        gate.complete(null);
        dispatch.get(10, TimeUnit.SECONDS);
        bus.whenIdle().toCompletableFuture().get(10, TimeUnit.SECONDS);
        PendingOperationsSnapshot settled = bus.pendingOperations();
        assertTrue(find(settled, item -> item.kind()
                == PendingOperationsSnapshot.Kind.EVENT_DISPATCH).isEmpty());
    }

    @Test
    void parallelGateShowsEventDispatchOperation() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CompletableFuture<Void> gate = new CompletableFuture<>();
        bus.subscribe(PARALLEL, event -> {
            started.countDown();
            return gate;
        });

        var dispatch = bus.dispatch(PARALLEL, new TextEvent("held"))
                .toCompletableFuture();
        assertTrue(started.await(10, TimeUnit.SECONDS));

        PendingOperationsSnapshot.Operation operation = requiredOperation(
                bus.pendingOperations(),
                item -> item.kind() == PendingOperationsSnapshot.Kind.EVENT_DISPATCH);
        assertEquals(PendingOperationsSnapshot.WaitType.LISTENER, operation.waitsFor());
        assertTrue(operation.detail().contains("mode=PARALLEL"));
        assertTrue(operation.detail().contains("listeners=1"));

        gate.complete(null);
        dispatch.get(10, TimeUnit.SECONDS);
        assertNoEventDispatchOperations(bus.pendingOperations());
    }

    @Test
    void syncDispatchIsVisibleWhileListenerRuns() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        EventDefinition.Sync<TextEvent> sync = EventDefinition.sync(TextEvent.class);
        bus.subscribe(sync, event -> {
            started.countDown();
            release.await();
        });

        ExecutorService dispatcher = Executors.newSingleThreadExecutor();
        try {
            CompletableFuture<EventDispatch<TextEvent>> dispatch = CompletableFuture.supplyAsync(
                    () -> bus.dispatch(sync, new TextEvent("sync")), dispatcher);
            assertTrue(started.await(10, TimeUnit.SECONDS));

            PendingOperationsSnapshot.Operation operation = requiredOperation(
                    bus.pendingOperations(),
                    item -> item.kind() == PendingOperationsSnapshot.Kind.EVENT_DISPATCH);
            assertTrue(operation.detail().contains("mode=SYNC"));

            release.countDown();
            dispatch.get(10, TimeUnit.SECONDS);
        } finally {
            dispatcher.shutdown();
            assertTrue(dispatcher.awaitTermination(10, TimeUnit.SECONDS));
        }
        assertNoEventDispatchOperations(bus.pendingOperations());
    }

    @Test
    void subscriptionDrainReportsSameDispatchId() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CompletableFuture<Void> gate = new CompletableFuture<>();
        EventSubscription subscription = bus.subscribe(PARALLEL, event -> {
            started.countDown();
            return gate;
        });

        var dispatch = bus.dispatch(PARALLEL, new TextEvent("drain"))
                .toCompletableFuture();
        assertTrue(started.await(10, TimeUnit.SECONDS));
        subscription.unsubscribe();
        assertFalse(subscription.active());

        PendingOperationsSnapshot snapshot = bus.pendingOperations();
        PendingOperationsSnapshot.Operation dispatchOperation = requiredOperation(
                snapshot, item -> item.kind() == PendingOperationsSnapshot.Kind.EVENT_DISPATCH);
        PendingOperationsSnapshot.Operation drainOperation = requiredOperation(
                snapshot,
                item -> item.kind() == PendingOperationsSnapshot.Kind.EVENT_SUBSCRIPTION_DRAIN);
        assertEquals(PendingOperationsSnapshot.WaitType.DISPATCH, drainOperation.waitsFor());
        assertEquals(subscription.subscriptionId(), drainOperation.targetId());
        assertTrue(drainOperation.detail().contains("pending dispatches=1"));
        assertTrue(drainOperation.detail().contains(dispatchOperation.targetId()),
                drainOperation.detail());

        gate.complete(null);
        dispatch.get(10, TimeUnit.SECONDS);
        subscription.closeAsync().toCompletableFuture().get(10, TimeUnit.SECONDS);
        PendingOperationsSnapshot settled = bus.pendingOperations();
        assertTrue(find(settled, item -> item.kind()
                == PendingOperationsSnapshot.Kind.EVENT_SUBSCRIPTION_DRAIN).isEmpty());
        assertNoEventDispatchOperations(settled);
    }

    @Test
    void closeExecutorTerminationPhaseIsVisibleAndClears() throws Exception {
        GatedExecutor executor = new GatedExecutor();
        EventBus owned = new DefaultEventBus(executor, true);
        try {
            CompletableFuture<Void> closing = owned.closeAsync().toCompletableFuture();
            assertTrue(executor.terminationStarted.await(10, TimeUnit.SECONDS));
            assertFalse(closing.isDone());

            PendingOperationsSnapshot snapshot = owned.pendingOperations();
            assertTrue(snapshot.closeRequested());
            PendingOperationsSnapshot.Operation operation = requiredOperation(
                    snapshot,
                    item -> item.kind() == PendingOperationsSnapshot.Kind.EVENT_SUBSCRIPTION_DRAIN
                            && item.waitsFor()
                            == PendingOperationsSnapshot.WaitType.EXECUTOR_TERMINATION);
            assertEquals(owned.busId(), operation.targetId());

            executor.release.countDown();
            closing.get(10, TimeUnit.SECONDS);
            PendingOperationsSnapshot closed = owned.pendingOperations();
            assertTrue(closed.closeRequested());
            assertEquals(0, closed.operations().size());
            assertEquals(0, closed.omitted());
        } finally {
            executor.release.countDown();
            owned.close();
        }
    }

    @Test
    void closedBusSnapshotStaysEmptyAcrossRepeatedCloseAndRejectedWork() throws Exception {
        bus.subscribe(PARALLEL, event -> CompletableFuture.completedFuture(null));
        bus.dispatch(PARALLEL, new TextEvent("before-close"))
                .toCompletableFuture().get(10, TimeUnit.SECONDS);

        CompletableFuture<Void> first = bus.closeAsync().toCompletableFuture();
        CompletableFuture<Void> second = bus.closeAsync().toCompletableFuture();
        assertNotSame(first, second);
        first.get(10, TimeUnit.SECONDS);
        second.get(10, TimeUnit.SECONDS);

        assertThrows(IllegalStateException.class,
                () -> bus.dispatch(EventDefinition.sync(TextEvent.class), new TextEvent("x")));
        CompletionException error = assertThrows(CompletionException.class, () ->
                bus.dispatch(PARALLEL, new TextEvent("after-close"))
                        .toCompletableFuture().join());
        assertInstanceOf(IllegalStateException.class, error.getCause());

        PendingOperationsSnapshot snapshot = bus.pendingOperations();
        assertTrue(snapshot.closeRequested());
        assertEquals(0, snapshot.operations().size());
        assertEquals(0, snapshot.omitted());
    }

    @Test
    void hundredConcurrentReadersObserveGatedDispatchWithoutBlocking() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CompletableFuture<Void> gate = new CompletableFuture<>();
        bus.subscribe(PARALLEL, event -> {
            started.countDown();
            return gate;
        });

        var dispatch = bus.dispatch(PARALLEL, new TextEvent("readers"))
                .toCompletableFuture();
        assertTrue(started.await(10, TimeUnit.SECONDS));

        List<CompletableFuture<PendingOperationsSnapshot>> readers = IntStream.range(0, 100)
                .mapToObj(ignored -> CompletableFuture.supplyAsync(
                        () -> assertTimeoutPreemptively(
                                Duration.ofMillis(500), bus::pendingOperations),
                        readerPool))
                .toList();
        for (CompletableFuture<PendingOperationsSnapshot> reader : readers) {
            PendingOperationsSnapshot snapshot = reader.get(10, TimeUnit.SECONDS);
            PendingOperationsSnapshot.Operation operation = requiredOperation(
                    snapshot,
                    item -> item.kind() == PendingOperationsSnapshot.Kind.EVENT_DISPATCH);
            assertFalse(operation.age().isNegative());
        }

        gate.complete(null);
        dispatch.get(10, TimeUnit.SECONDS);
        assertNoEventDispatchOperations(bus.pendingOperations());
    }

    @Test
    void failedBindingConflictDispatchLeavesNoPendingOperation() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CompletableFuture<Void> gate = new CompletableFuture<>();
        try (ShadowLoader shadow = new ShadowLoader()) {
            Class<?> shadowType = shadow.loadShadowTextEvent();
            EventDefinition.Parallel<Object> conflicting =
                    EventDefinition.parallel("shadow.conflict", (Class<Object>) shadowType);
            EventDefinition.Parallel<Object> canonical =
                    EventDefinition.parallel("shadow.conflict", (Class<Object>) (Class<?>) TextEvent.class);
            bus.subscribe(canonical, event -> {
                started.countDown();
                return gate;
            });
            java.lang.reflect.Constructor<?> shadowConstructor =
                    shadowType.getDeclaredConstructor(String.class);
            shadowConstructor.setAccessible(true);
            Object shadowEvent = shadowConstructor.newInstance("shadow");

            var held = bus.dispatch(canonical, new TextEvent("held"))
                    .toCompletableFuture();
            assertTrue(started.await(10, TimeUnit.SECONDS));

            CompletionException error = assertThrows(CompletionException.class, () ->
                    bus.dispatch(conflicting, shadowEvent).toCompletableFuture().join());
            assertInstanceOf(IllegalArgumentException.class, error.getCause());

            List<PendingOperationsSnapshot.Operation> dispatches = find(
                    bus.pendingOperations(),
                    item -> item.kind() == PendingOperationsSnapshot.Kind.EVENT_DISPATCH);
            assertEquals(1, dispatches.size());
            assertTrue(dispatches.getFirst().detail().contains("event=shadow.conflict"));

            gate.complete(null);
            held.get(10, TimeUnit.SECONDS);
        }
        assertNoEventDispatchOperations(bus.pendingOperations());
    }

    @Test
    void closeWaitingForAcceptedDispatchIsExplainedByEventDispatch() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CompletableFuture<Void> gate = new CompletableFuture<>();
        bus.subscribe(PARALLEL, event -> {
            started.countDown();
            return gate;
        });

        var dispatch = bus.dispatch(PARALLEL, new TextEvent("closing"))
                .toCompletableFuture();
        assertTrue(started.await(10, TimeUnit.SECONDS));
        CompletableFuture<Void> closing = bus.closeAsync().toCompletableFuture();
        assertFalse(closing.isDone());

        PendingOperationsSnapshot snapshot = bus.pendingOperations();
        assertTrue(snapshot.closeRequested());
        requiredOperation(snapshot,
                item -> item.kind() == PendingOperationsSnapshot.Kind.EVENT_DISPATCH);
        assertTrue(find(snapshot, item -> item.waitsFor()
                == PendingOperationsSnapshot.WaitType.EXECUTOR_TERMINATION).isEmpty());

        gate.complete(null);
        dispatch.get(10, TimeUnit.SECONDS);
        closing.get(10, TimeUnit.SECONDS);
        assertEquals(0, bus.pendingOperations().operations().size());
    }

    @Test
    void nullArgumentsLeaveNoPendingOperation() {
        assertThrows(NullPointerException.class, () -> bus.dispatch(PARALLEL, null));
        assertThrows(NullPointerException.class,
                () -> bus.dispatch((EventDefinition.Parallel<TextEvent>) null, new TextEvent("x")));
        assertEquals(0, bus.pendingOperations().operations().size());
    }

    @Test
    void snapshotRetainsNoEventListenerListenerLoaderOrBus() throws Exception {
        URLClassLoader listenerLoader = new URLClassLoader(new URL[0],
                EventBusPendingOperationsTest.class.getClassLoader());
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
        TextEvent event = new TextEvent("collectable");
        EventBus localBus = new DefaultEventBus();
        EventSubscription subscription = localBus.subscribe(PARALLEL, listener);

        var dispatch = localBus.dispatch(PARALLEL, event).toCompletableFuture();
        assertTrue(started.await(10, TimeUnit.SECONDS));
        PendingOperationsSnapshot retained = localBus.pendingOperations();
        PendingOperationsSnapshot.Operation operation = requiredOperation(
                retained,
                item -> item.kind() == PendingOperationsSnapshot.Kind.EVENT_DISPATCH);
        String retainedDispatchId = operation.targetId();

        WeakReference<TextEvent> eventReference = new WeakReference<>(event);
        WeakReference<Object> listenerReference = new WeakReference<>(listener);
        WeakReference<ClassLoader> loaderReference = new WeakReference<>(listenerLoader);
        WeakReference<EventBus> busReference = new WeakReference<>(localBus);
        event = null;
        listener = null;
        listenerLoader = null;
        localBus = null;
        subscription = null;

        gate.complete(null);
        dispatch.get(10, TimeUnit.SECONDS);
        busReference.get().close();
        dispatch = null;

        assertTrue(awaitCleared(eventReference, listenerReference, loaderReference, busReference),
                "pending operations snapshot must not retain event, listener, loader or bus");
        assertTrue(retained.render().contains(retainedDispatchId));
        assertTrue(retained.operations().isEmpty() || retained.operations().stream()
                .allMatch(item -> item.targetId().equals(retainedDispatchId)
                        || !item.targetId().startsWith("event-dispatch-")));
    }

    @Test
    void ageUsesInjectedTickerWithoutAssumingNonNegativeNanoTime() throws Exception {
        AtomicLong clock = new AtomicLong(-100);
        EventBus tickedBus = new DefaultEventBus(clock::get);
        try {
            CountDownLatch started = new CountDownLatch(1);
            CompletableFuture<Void> gate = new CompletableFuture<>();
            tickedBus.subscribe(PARALLEL, event -> {
                started.countDown();
                return gate;
            });

            var dispatch = tickedBus.dispatch(PARALLEL, new TextEvent("ticked"))
                    .toCompletableFuture();
            assertTrue(started.await(10, TimeUnit.SECONDS));
            clock.set(-40);

            PendingOperationsSnapshot.Operation operation = requiredOperation(
                    tickedBus.pendingOperations(),
                    item -> item.kind() == PendingOperationsSnapshot.Kind.EVENT_DISPATCH);
            assertEquals(Duration.ofNanos(60), operation.age());

            gate.complete(null);
            dispatch.get(10, TimeUnit.SECONDS);
        } finally {
            tickedBus.close();
        }
    }

    @RepeatedTest(20)
    void dispatchDrainLifecycleIsStable() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CompletableFuture<Void> gate = new CompletableFuture<>();
        EventSubscription subscription = bus.subscribe(PARALLEL, event -> {
            started.countDown();
            return gate;
        });

        var dispatch = bus.dispatch(PARALLEL, new TextEvent("cycle"))
                .toCompletableFuture();
        assertTrue(started.await(10, TimeUnit.SECONDS));
        subscription.unsubscribe();

        PendingOperationsSnapshot pending = bus.pendingOperations();
        assertFalse(pending.closeRequested());
        requiredOperation(pending,
                item -> item.kind() == PendingOperationsSnapshot.Kind.EVENT_DISPATCH);
        requiredOperation(pending,
                item -> item.kind() == PendingOperationsSnapshot.Kind.EVENT_SUBSCRIPTION_DRAIN);

        gate.complete(null);
        dispatch.get(10, TimeUnit.SECONDS);
        subscription.closeAsync().toCompletableFuture().get(10, TimeUnit.SECONDS);
        bus.whenIdle().toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertEquals(0, bus.pendingOperations().operations().size());
    }

    private static PendingOperationsSnapshot.Operation requiredOperation(
            PendingOperationsSnapshot snapshot,
            Predicate<PendingOperationsSnapshot.Operation> filter) {
        List<PendingOperationsSnapshot.Operation> matches = find(snapshot, filter);
        if (matches.size() != 1) {
            fail("expected exactly one matching operation but was " + matches.size()
                    + "\n" + snapshot.render());
        }
        return matches.getFirst();
    }

    private static List<PendingOperationsSnapshot.Operation> find(
            PendingOperationsSnapshot snapshot,
            Predicate<PendingOperationsSnapshot.Operation> filter) {
        List<PendingOperationsSnapshot.Operation> result = new ArrayList<>();
        for (PendingOperationsSnapshot.Operation operation : snapshot.operations()) {
            if (filter.test(operation)) {
                result.add(operation);
            }
        }
        return result;
    }

    private static void assertNoEventDispatchOperations(PendingOperationsSnapshot snapshot) {
        assertTrue(find(snapshot, item -> item.kind()
                == PendingOperationsSnapshot.Kind.EVENT_DISPATCH).isEmpty(),
                snapshot.render());
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

    /** 子优先加载同名 record 副本，用于构造同名事件绑定冲突。 */
    private static final class ShadowLoader extends URLClassLoader {
        ShadowLoader() {
            super(new URL[0], EventBusPendingOperationsTest.class.getClassLoader());
        }

        Class<?> loadShadowTextEvent() throws ClassNotFoundException {
            String name = TextEvent.class.getName();
            Class<?> defined = findLoadedClass(name);
            if (defined == null) {
                defined = defineClass(name, classBytes(), 0, classBytes().length);
            }
            return defined;
        }

        private static byte[] classBytes() {
            try (java.io.InputStream input = TextEvent.class.getResourceAsStream(
                    "EventBusPendingOperationsTest$TextEvent.class")) {
                if (input == null) {
                    throw new IllegalStateException("TextEvent class bytes are missing");
                }
                return input.readAllBytes();
            } catch (java.io.IOException error) {
                throw new IllegalStateException("cannot read TextEvent class bytes", error);
        }
    }
}

    /** 终止阶段可被测试门住的执行器，用于观察 close 的 executor termination 相位。 */
    private static final class GatedExecutor extends ThreadPoolExecutor {
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch terminationStarted = new CountDownLatch(1);

        GatedExecutor() {
            super(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit)
                throws InterruptedException {
            terminationStarted.countDown();
            release.await();
            return super.awaitTermination(timeout, unit);
        }
    }
    private record TextEvent(String text) {
    }
}
