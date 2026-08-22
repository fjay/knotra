package io.knotra.events;

import io.knotra.MountHandle;
import io.knotra.KnotraRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("unchecked")
final class EventBusQuiescenceTest {
    private static final EventDefinition.Sync<TextEvent> SYNC =
            EventDefinition.sync(TextEvent.class);
    private static final EventDefinition.Parallel<TextEvent> PARALLEL =
            EventDefinition.parallel(TextEvent.class);
    private static final EventDefinition.Serial<TextEvent> SERIAL =
            EventDefinition.serial(TextEvent.class);
    private static final EventDefinition.Bail<TextEvent> BAIL =
            EventDefinition.bail(TextEvent.class);
    private static final EventDefinition.Waterfall<TextEvent> WATERFALL =
            EventDefinition.waterfall(TextEvent.class);
    private KnotraRuntime runtime;
    private EventBus bus;

    @BeforeEach
    void setUp() throws Exception {
        runtime = KnotraRuntime.create();
        MountHandle handle = runtime.mount("event-bus", new EventBusFactory());
        assertEquals(io.knotra.ComponentState.ACTIVE, handle.whenSettled()
                .toCompletableFuture().get(10, TimeUnit.SECONDS));
        bus = runtime.root().view().require(EventCapabilities.EVENT_BUS);
    }

    @AfterEach
    void tearDown() throws Exception {
        runtime.close();
    }

    @Test
    void unsubscribeIsNonBlockingAndCloseAsyncWaitsAcceptedDispatch() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CompletableFuture<Void> gate = new CompletableFuture<>();
        EventSubscription subscription = bus.subscribe(PARALLEL, event -> {
            started.countDown();
            return gate;
        });

        var dispatch = bus.dispatch(PARALLEL, new TextEvent("held"))
                .toCompletableFuture();
        assertTrue(started.await(10, TimeUnit.SECONDS));
        subscription.unsubscribe();
        assertFalse(subscription.active());
        assertEquals(0, bus.snapshot().subscriptionCount());

        CompletableFuture<Void> closing = subscription.closeAsync().toCompletableFuture();
        assertFalse(closing.isDone());
        gate.complete(null);
        dispatch.get(10, TimeUnit.SECONDS);
        closing.get(10, TimeUnit.SECONDS);
        assertSame(closing, subscription.closeAsync().toCompletableFuture());
    }

    @Test
    void callbackCanUnsubscribeItselfWithoutDeadlock() {
        AtomicReference<EventSubscription> reference = new AtomicReference<>();
        EventSubscription subscription = bus.subscribe(SYNC, event -> {
            reference.get().unsubscribe();
        });
        reference.set(subscription);

        EventDispatch<TextEvent> result = bus.dispatch(SYNC, new TextEvent("self"));

        assertTrue(result.successful());
        assertEquals(1, result.completedCount());
        assertFalse(subscription.active());
    }

    @Test
    void callbackCanStartBusCloseWithoutBlockingTheCallback() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CompletableFuture<Void> gate = new CompletableFuture<>();
        CompletableFuture<CompletableFuture<Void>> closing =
                new CompletableFuture<>();
        EventBus closingBus = new DefaultEventBus();
        closingBus.subscribe(PARALLEL, event -> {
            started.countDown();
            closing.complete(closingBus.closeAsync().toCompletableFuture());
            return gate;
        });

        var dispatch = closingBus.dispatch(PARALLEL, new TextEvent("closing"))
                .toCompletableFuture();
        assertTrue(started.await(10, TimeUnit.SECONDS));
        CompletableFuture<Void> closingFuture = closing.get(10, TimeUnit.SECONDS);
        assertFalse(closingFuture.isDone());
        gate.complete(null);

        dispatch.get(10, TimeUnit.SECONDS);
        closingFuture.get(10, TimeUnit.SECONDS);
        assertTrue(closingBus.snapshot().closed());
    }

    @Test
    void busWhenIdleAndCloseWaitForAcceptedDispatch() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CompletableFuture<Void> gate = new CompletableFuture<>();
        bus.subscribe(PARALLEL, event -> {
            started.countDown();
            return gate;
        });

        var dispatch = bus.dispatch(PARALLEL, new TextEvent("idle"))
                .toCompletableFuture();
        assertTrue(started.await(10, TimeUnit.SECONDS));
        CompletableFuture<Void> idle = bus.whenIdle().toCompletableFuture();
        CompletableFuture<Void> closing = bus.closeAsync().toCompletableFuture();

        assertFalse(idle.isDone());
        assertFalse(closing.isDone());
        assertTrue(bus.snapshot().closed());
        gate.complete(null);

        dispatch.get(10, TimeUnit.SECONDS);
        idle.get(10, TimeUnit.SECONDS);
        closing.get(10, TimeUnit.SECONDS);
        assertSame(closing, bus.closeAsync().toCompletableFuture());
    }

    @Test
    void skippedSerialListenerReleasesItsAcceptedLease() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CompletableFuture<Boolean> gate = new CompletableFuture<>();
        AtomicReference<EventSubscription> first = new AtomicReference<>();
        AtomicReference<EventSubscription> second = new AtomicReference<>();
        first.set(bus.subscribe(SERIAL, event -> {
            started.countDown();
            return gate;
        }));
        second.set(bus.subscribe(SERIAL, event -> CompletableFuture.completedFuture(true)));

        var dispatch = bus.dispatch(SERIAL, new TextEvent("stop"))
                .toCompletableFuture();
        assertTrue(started.await(10, TimeUnit.SECONDS));
        CompletableFuture<Void> closing = second.get().closeAsync().toCompletableFuture();
        assertFalse(closing.isDone());
        gate.complete(false);

        dispatch.get(10, TimeUnit.SECONDS);
        closing.get(10, TimeUnit.SECONDS);
        assertTrue(dispatch.getNow(null).stoppedEarly());
    }

    @Test
    void skippedBailListenerReleasesItsAcceptedLease() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<EventSubscription> first = new AtomicReference<>();
        AtomicReference<EventSubscription> second = new AtomicReference<>();
        first.set(bus.subscribe(BAIL, event -> {
            started.countDown();
            release.await();
            return true;
        }));
        second.set(bus.subscribe(BAIL, event -> false));

        java.util.concurrent.ExecutorService executor =
                java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var dispatch = CompletableFuture.supplyAsync(() ->
                            bus.dispatch(BAIL, new TextEvent("claim")).toCompletableFuture().join(),
                            executor)
                    .toCompletableFuture();
            assertTrue(started.await(10, TimeUnit.SECONDS));
            CompletableFuture<Void> closing =
                    second.get().closeAsync().toCompletableFuture();
            assertFalse(closing.isDone());
            release.countDown();

            dispatch.get(10, TimeUnit.SECONDS);
            closing.get(10, TimeUnit.SECONDS);
            assertTrue(dispatch.getNow(null).stoppedEarly());
        } finally {
            executor.shutdown();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void skippedWaterfallListenerReleasesItsAcceptedLease() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CompletableFuture<TextEvent> gate = new CompletableFuture<>();
        AtomicReference<EventSubscription> first = new AtomicReference<>();
        AtomicReference<EventSubscription> second = new AtomicReference<>();
        first.set(bus.subscribe(WATERFALL, event -> {
            started.countDown();
            return gate;
        }));
        second.set(bus.subscribe(WATERFALL, event ->
                CompletableFuture.completedFuture(event)));

        var dispatch = bus.dispatch(WATERFALL, new TextEvent("fail"))
                .toCompletableFuture();
        assertTrue(started.await(10, TimeUnit.SECONDS));
        CompletableFuture<Void> closing = second.get().closeAsync().toCompletableFuture();
        assertFalse(closing.isDone());
        gate.completeExceptionally(new IllegalStateException("stop chain"));

        dispatch.get(10, TimeUnit.SECONDS);
        closing.get(10, TimeUnit.SECONDS);
        assertEquals(1, dispatch.getNow(null).failureCount());
    }

    @Test
    void wrongEventTypeIsRejectedBeforeListenerDispatch() {
        bus.subscribe(SYNC, event -> fail("listener must not run"));
        EventDefinition.Sync<Object> definition =
                EventDefinition.sync((Class<Object>) (Class<?>) TextEvent.class);

        assertThrows(ClassCastException.class, () ->
                bus.dispatch(definition, new Object()));
        assertTrue(bus.whenIdle().toCompletableFuture().isDone());
    }

    @Test
    void sameEventNameCanRebindAfterItsLastReferenceIsReleased() throws Exception {
        try (ByteClassLoader firstLoader = new ByteClassLoader();
             ByteClassLoader secondLoader = new ByteClassLoader()) {
            Class<?> firstType = firstLoader.loadClass("dynamic.SameName");
            Class<?> secondType = secondLoader.loadClass("dynamic.SameName");
            assertNotSame(firstType, secondType);

            EventDefinition.Sync<Object> first =
                    EventDefinition.sync((Class<Object>) firstType);
            EventDefinition.Sync<Object> second =
                    EventDefinition.sync((Class<Object>) secondType);
            EventSubscription subscription = bus.subscribe(first, event -> {});

            assertThrows(IllegalArgumentException.class, () -> bus.subscribe(second, event -> {}));
            assertThrows(IllegalArgumentException.class, () ->
                    bus.dispatch(second, secondType.cast(newInstance(secondType))));

            subscription.unsubscribe();
            EventSubscription rebound = bus.subscribe(second, event -> {});
            assertTrue(rebound.active());
            assertEquals(1, bus.dispatch(second, secondType.cast(newInstance(secondType)))
                    .listenerCount());
            rebound.unsubscribe();
        }
    }

    @Test
    void inFlightDispatchKeepsCanonicalBindingUntilSuccessSettles() throws Exception {
        try (ByteClassLoader firstLoader = new ByteClassLoader();
             ByteClassLoader secondLoader = new ByteClassLoader()) {
            Class<?> firstType = firstLoader.loadClass("dynamic.SameName");
            Class<?> secondType = secondLoader.loadClass("dynamic.SameName");
            EventDefinition.Serial<Object> first = serialDefinition(firstType);
            EventDefinition.Serial<Object> second = serialDefinition(secondType);
            CountDownLatch entered = new CountDownLatch(1);
            CompletableFuture<Boolean> gate = new CompletableFuture<>();
            EventSubscription subscription = bus.subscribe(first, event -> {
                entered.countDown();
                return gate;
            });

            var held = bus.dispatch(first, firstType.cast(newInstance(firstType)))
                    .toCompletableFuture();
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            subscription.unsubscribe();
            assertThrows(IllegalArgumentException.class, () -> bus.subscribe(second, event ->
                    CompletableFuture.completedFuture(true)));
            assertThrows(IllegalArgumentException.class, () ->
                    bus.dispatch(second, secondType.cast(newInstance(secondType))));

            CompletableFuture<Void> idle = bus.whenIdle().toCompletableFuture();
            assertFalse(idle.isDone());
            gate.complete(true);
            idle.get(10, TimeUnit.SECONDS);
            assertTrue(held.get(10, TimeUnit.SECONDS).successful());
            EventSubscription rebound = bus.subscribe(second, event ->
                    CompletableFuture.completedFuture(true));
            assertTrue(rebound.active());
            rebound.unsubscribe();
        }
    }

    @Test
    void failedListenerStillReleasesItsAcceptedCanonicalBinding() throws Exception {
        try (ByteClassLoader firstLoader = new ByteClassLoader();
             ByteClassLoader secondLoader = new ByteClassLoader()) {
            Class<?> firstType = firstLoader.loadClass("dynamic.SameName");
            Class<?> secondType = secondLoader.loadClass("dynamic.SameName");
            EventDefinition.Serial<Object> first = serialDefinition(firstType);
            EventDefinition.Serial<Object> second = serialDefinition(secondType);
            CountDownLatch entered = new CountDownLatch(1);
            CompletableFuture<Boolean> gate = new CompletableFuture<>();
            EventSubscription subscription = bus.subscribe(first, event -> {
                entered.countDown();
                return gate;
            });

            var failed = bus.dispatch(first, firstType.cast(newInstance(firstType)))
                    .toCompletableFuture();
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            subscription.unsubscribe();
            assertThrows(IllegalArgumentException.class, () -> bus.subscribe(second, event ->
                    CompletableFuture.completedFuture(true)));

            gate.completeExceptionally(new IllegalStateException("listener failed"));
            assertEquals(1, failed.get(10, TimeUnit.SECONDS).failureCount());
            EventSubscription rebound = bus.subscribe(second, event ->
                    CompletableFuture.completedFuture(true));
            assertTrue(rebound.active());
            rebound.unsubscribe();
        }
    }

    @Test
    void skippedListenersStillReleaseTheirAcceptedCanonicalBinding() throws Exception {
        try (ByteClassLoader firstLoader = new ByteClassLoader();
             ByteClassLoader secondLoader = new ByteClassLoader()) {
            Class<?> firstType = firstLoader.loadClass("dynamic.SameName");
            Class<?> secondType = secondLoader.loadClass("dynamic.SameName");
            EventDefinition.Serial<Object> first = serialDefinition(firstType);
            EventDefinition.Serial<Object> second = serialDefinition(secondType);
            CountDownLatch entered = new CountDownLatch(1);
            CompletableFuture<Boolean> gate = new CompletableFuture<>();
            EventSubscription subscription = bus.subscribe(first, event -> {
                entered.countDown();
                return gate;
            });

            var stopped = bus.dispatch(first, firstType.cast(newInstance(firstType)))
                    .toCompletableFuture();
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            subscription.unsubscribe();
            assertThrows(IllegalArgumentException.class, () -> bus.subscribe(second, event ->
                    CompletableFuture.completedFuture(true)));

            gate.complete(false);
            assertTrue(stopped.get(10, TimeUnit.SECONDS).stoppedEarly());
            EventSubscription rebound = bus.subscribe(second, event ->
                    CompletableFuture.completedFuture(true));
            assertTrue(rebound.active());
            rebound.unsubscribe();
        }
    }

    @Test
    void concurrentRegistrationDispatchAndUnsubscribeAllowsRebindAfterQuiescence()
            throws Exception {
        try (ByteClassLoader firstLoader = new ByteClassLoader();
             ByteClassLoader secondLoader = new ByteClassLoader()) {
            Class<?> firstType = firstLoader.loadClass("dynamic.SameName");
            Class<?> secondType = secondLoader.loadClass("dynamic.SameName");
            EventDefinition.Serial<Object> first = serialDefinition(firstType);
            AtomicInteger deliveries = new AtomicInteger();
            ExecutorService executor = Executors.newFixedThreadPool(6);
            try {
                List<CompletableFuture<Void>> workers = new ArrayList<>();
                for (int worker = 0; worker < 6; worker++) {
                    workers.add(CompletableFuture.runAsync(() -> {
                        for (int iteration = 0; iteration < 100; iteration++) {
                            EventSubscription subscription = bus.subscribe(first, event -> {
                                deliveries.incrementAndGet();
                                return CompletableFuture.completedFuture(true);
                            });
                            try {
                                EventDispatch<Object> dispatch = bus.dispatch(
                                                first, firstType.cast(newInstance(firstType)))
                                        .toCompletableFuture().get(10, TimeUnit.SECONDS);
                                assertTrue(dispatch.successful(), dispatch.toString());
                            } catch (Exception error) {
                                throw new AssertionError(error);
                            } finally {
                                subscription.unsubscribe();
                            }
                        }
                    }, executor));
                }
                CompletableFuture.allOf(workers.toArray(CompletableFuture[]::new))
                        .get(30, TimeUnit.SECONDS);
            } finally {
                executor.shutdown();
                assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
            }

            bus.whenIdle().toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertTrue(deliveries.get() >= 600);
            EventSubscription rebound = bus.subscribe(serialDefinition(secondType), event ->
                    CompletableFuture.completedFuture(true));
            assertTrue(rebound.active());
            rebound.unsubscribe();
        }
    }

    @Test
    void listenerClassLoaderIsUsedOnlyForCallbackInvocation() throws Exception {
        ClassLoader host = Thread.currentThread().getContextClassLoader();
        AtomicReference<ClassLoader> callbackLoader = new AtomicReference<>();
        try (ByteClassLoader pluginLoader = new ByteClassLoader()) {
            Class<?> eventType = pluginLoader.loadClass("dynamic.PluginEvent");
            EventDefinition.Sync<Object> definition =
                    EventDefinition.sync((Class<Object>) eventType);
            EventListener<Object> listener = (EventListener<Object>) Proxy.newProxyInstance(
                    pluginLoader,
                    new Class<?>[]{EventListener.class},
                    (proxy, method, args) -> {
                        callbackLoader.set(Thread.currentThread().getContextClassLoader());
                        return null;
                    });

            EventSubscription subscription = bus.subscribe(definition, listener);
            bus.dispatch(definition, eventType.cast(eventType.getDeclaredConstructor().newInstance()));

            assertSame(pluginLoader, callbackLoader.get());
            assertSame(host, Thread.currentThread().getContextClassLoader());
            subscription.unsubscribe();
        }
    }

    @Test
    void maliciousFailureTextIsBoundedAndStable() {
        EventSubscription subscription = bus.subscribe(SYNC, event -> {
            throw new EvilThrowable();
        });

        EventDispatch<TextEvent> result = bus.dispatch(SYNC, new TextEvent("evil"));

        assertEquals(1, result.failureCount());
        assertEquals(EvilThrowable.class.getName(), result.failures().getFirst().message());
        assertTrue(result.failures().getFirst().message().length() <= 512);
        assertEquals(EvilThrowable.class.getName(), result.failures().getFirst().message());
        subscription.unsubscribe();
    }

    @Test
    void snapshotIsSortedByNameModeAndSequence() {
        EventDefinition.Sync<AlphaEvent> alpha = EventDefinition.sync(AlphaEvent.class);
        EventDefinition.Sync<BetaEvent> betaSync = EventDefinition.sync(BetaEvent.class);
        EventDefinition.Parallel<BetaEvent> betaParallel =
                EventDefinition.parallel(BetaEvent.class);
        EventSubscription betaFirst = bus.subscribe(betaSync, event -> {});
        EventSubscription alphaSecond = bus.subscribe(alpha, event -> {});
        EventSubscription betaThird = bus.subscribe(betaParallel,
                event -> CompletableFuture.completedFuture(null));

        List<String> ids = bus.snapshot().subscriptions().stream()
                .map(EventBusSnapshot.Item::subscriptionId)
                .toList();

        assertEquals(List.of(alphaSecond.subscriptionId(), betaFirst.subscriptionId(),
                betaThird.subscriptionId()), ids);
    }

    @Test
    void subscriptionAndBusQuiescenceIsStableAcrossTenCycles() throws Exception {
        for (int cycle = 0; cycle < 10; cycle++) {
            EventBus cycleBus = new DefaultEventBus();
            CountDownLatch started = new CountDownLatch(1);
            CompletableFuture<Void> gate = new CompletableFuture<>();
            EventSubscription subscription = cycleBus.subscribe(PARALLEL, event -> {
                started.countDown();
                return gate;
            });

            var dispatch = cycleBus.dispatch(PARALLEL, new TextEvent("cycle-" + cycle))
                    .toCompletableFuture();
            assertTrue(started.await(10, TimeUnit.SECONDS));
            CompletableFuture<Void> subscriptionClose =
                    subscription.closeAsync().toCompletableFuture();
            CompletableFuture<Void> busClose =
                    cycleBus.closeAsync().toCompletableFuture();
            assertFalse(subscriptionClose.isDone());
            assertFalse(busClose.isDone());
            gate.complete(null);

            dispatch.get(10, TimeUnit.SECONDS);
            subscriptionClose.get(10, TimeUnit.SECONDS);
            busClose.get(10, TimeUnit.SECONDS);
        }
    }
    @Test
    void pluginListenerAndBusClassLoadersAreCollectableAfterClose() {
        for (int iteration = 0; iteration < 10; iteration++) {
            References references = createClosedPluginBus();
            assertTrue(awaitCleared(references.bus(), references.loader()));
        }
    }

    private References createClosedPluginBus() {
        EventBus bus = new DefaultEventBus();
        ByteClassLoader loader = new ByteClassLoader();
        Class<?> eventType = loadClass(loader, "dynamic.RecycledEvent");
        EventDefinition.Sync<Object> definition =
                EventDefinition.sync((Class<Object>) eventType);
        EventListener<Object> listener = (EventListener<Object>) Proxy.newProxyInstance(
                loader,
                new Class<?>[]{EventListener.class},
                (proxy, method, args) -> null);
        EventSubscription subscription = bus.subscribe(definition, listener);

        bus.dispatch(definition, eventType.cast(newInstance(eventType)));
        // Close 必须负责处理此仍处于活跃状态的规范绑定。
        bus.close();

        WeakReference<EventBus> busReference = new WeakReference<>(bus);
        WeakReference<ClassLoader> loaderReference = new WeakReference<>(loader);
        return new References(busReference, loaderReference);
    }

    @SuppressWarnings("unchecked")
    private static EventDefinition.Serial<Object> serialDefinition(Class<?> type) {
        return EventDefinition.serial((Class<Object>) type);
    }

    private static Class<?> loadClass(ClassLoader loader, String name) {
        try {
            return loader.loadClass(name);
        } catch (ClassNotFoundException error) {
            throw new AssertionError(error);
        }
    }

    private static Object newInstance(Class<?> type) {
        try {
            return type.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    private static boolean awaitCleared(Reference<?> first, Reference<?> second) {
        for (int index = 0; index < 100; index++) {
            System.gc();
            if (first.get() == null && second.get() == null) {
                return true;
            }
        }
        return false;
    }

    private static final class EvilThrowable extends RuntimeException {
        @Override
        public String getMessage() {
            throw new IllegalStateException("message failed");
        }

        @Override
        public String toString() {
            throw new OutOfMemoryError("toString failed");
        }
    }

    private record References(
            WeakReference<EventBus> bus,
            WeakReference<ClassLoader> loader) {
    }

    private static final class ByteClassLoader extends URLClassLoader {
        ByteClassLoader() {
            super(new URL[0]);
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            if (!"dynamic.SameName".equals(name) && !"dynamic.PluginEvent".equals(name)
                    && !"dynamic.RecycledEvent".equals(name)) {
                throw new ClassNotFoundException(name);
            }
            byte[] bytes = classBytes(name.replace('.', '/'));
            return defineClass(name, bytes, 0, bytes.length);
        }

        private static byte[] classBytes(String internalName) {
            byte[] className = internalName.getBytes(StandardCharsets.UTF_8);
            byte[] objectName = "java/lang/Object".getBytes(StandardCharsets.UTF_8);
            byte[] init = "<init>".getBytes(StandardCharsets.UTF_8);
            byte[] noArgs = "()V".getBytes(StandardCharsets.UTF_8);
            byte[] code = "Code".getBytes(StandardCharsets.UTF_8);

            int size = 10
                    + classConstantSize(className)
                    + classConstantSize(objectName)
                    + utf8Size(init) + utf8Size(noArgs) + utf8Size(code)
                    + 5 + 5
                    + 2 + 2 + 2 + 2 + 2 + 2
                    + 8 + 2 + 4 + 17 + 2;
            byte[] bytes = new byte[size];
            int offset = 0;
            offset = u4(bytes, offset, 0xCAFEBABE);
            offset = u2(bytes, offset, 0);
            offset = u2(bytes, offset, 65);
            offset = u2(bytes, offset, 10);
            offset = constant(bytes, offset, (byte) 7, 2);
            offset = utf8(bytes, offset, className);
            offset = constant(bytes, offset, (byte) 7, 4);
            offset = utf8(bytes, offset, objectName);
            offset = utf8(bytes, offset, init);
            offset = utf8(bytes, offset, noArgs);
            offset = utf8(bytes, offset, code);
            offset = constant(bytes, offset, (byte) 10, 3, 9);
            offset = constant(bytes, offset, (byte) 12, 5, 6);

            offset = u2(bytes, offset, 0x0021);
            offset = u2(bytes, offset, 1);
            offset = u2(bytes, offset, 3);
            offset = u2(bytes, offset, 0);
            offset = u2(bytes, offset, 0);
            offset = u2(bytes, offset, 1);
            offset = u2(bytes, offset, 0x0001);
            offset = u2(bytes, offset, 5);
            offset = u2(bytes, offset, 6);
            offset = u2(bytes, offset, 1);
            offset = u2(bytes, offset, 7);
            offset = u4(bytes, offset, 17);
            offset = u2(bytes, offset, 1);
            offset = u2(bytes, offset, 1);
            offset = u4(bytes, offset, 5);
            bytes[offset] = 0x2a;
            bytes[offset + 1] = (byte) 0xb7;
            bytes[offset + 2] = 0;
            bytes[offset + 3] = 8;
            bytes[offset + 4] = (byte) 0xb1;
            offset += 5;
            offset = u2(bytes, offset, 0);
            offset = u2(bytes, offset, 0);
            offset = u2(bytes, offset, 0);
            assert offset == size;
            return bytes;
        }

        private static int classConstantSize(byte[] value) {
            return 3 + utf8Size(value);
        }

        private static int utf8Size(byte[] value) {
            return 3 + value.length;
        }

        private static int constant(byte[] bytes, int offset, byte tag, int... indexes) {
            bytes[offset] = tag;
            bytes[offset + 1] = (byte) (indexes[0] >>> 8);
            bytes[offset + 2] = (byte) indexes[0];
            if (indexes.length == 2) {
                bytes[offset + 3] = (byte) (indexes[1] >>> 8);
                bytes[offset + 4] = (byte) indexes[1];
                return offset + 5;
            }
            return offset + 3;
        }

        private static int utf8(byte[] bytes, int offset, byte[] value) {
            bytes[offset] = 1;
            bytes[offset + 1] = (byte) (value.length >>> 8);
            bytes[offset + 2] = (byte) value.length;
            System.arraycopy(value, 0, bytes, offset + 3, value.length);
            return offset + 3 + value.length;
        }

        private static int u4(byte[] bytes, int offset, int value) {
            bytes[offset] = (byte) (value >>> 24);
            bytes[offset + 1] = (byte) (value >>> 16);
            bytes[offset + 2] = (byte) (value >>> 8);
            bytes[offset + 3] = (byte) value;
            return offset + 4;
        }

        private static int u2(byte[] bytes, int offset, int value) {
            bytes[offset] = (byte) (value >>> 8);
            bytes[offset + 1] = (byte) value;
            return offset + 2;
        }
    }

    private record TextEvent(String text) {
    }

    private record AlphaEvent(String value) {
    }

    private record BetaEvent(String value) {
    }
}
