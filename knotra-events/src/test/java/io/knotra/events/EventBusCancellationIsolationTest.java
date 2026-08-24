package io.knotra.events;

import io.knotra.PendingOperationsSnapshot;
import org.junit.jupiter.api.Test;

import java.lang.ref.WeakReference;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 公开 stage 取消隔离契约：serial/parallel/whenIdle/closeAsync 的返回值都是独立观察，
 * 调用方 cancel 不传播到内部驱动 future；租约清理、绑定释放与总线收敛不受影响。
 */
@SuppressWarnings("unchecked")
final class EventBusCancellationIsolationTest {
    private static final EventDefinition.Parallel<TextEvent> PARALLEL =
            EventDefinition.parallel(TextEvent.class);
    private static final EventDefinition.Serial<TextEvent> SERIAL =
            EventDefinition.serial(TextEvent.class);

    @Test
    void parallelPublicCancelKeepsInternalDispatchAndClearsEveryLease() throws Exception {
        EventBus bus = new DefaultEventBus();
        CountDownLatch started = new CountDownLatch(1);
        CompletableFuture<Void> gate = new CompletableFuture<>();
        CompletableFuture<Boolean> listenerFinished = new CompletableFuture<>();
        EventSubscription subscription = bus.subscribe(PARALLEL, event -> {
            started.countDown();
            return gate.whenComplete((ignored, error) ->
                    listenerFinished.complete(error == null));
        });

        CompletableFuture<EventDispatch<TextEvent>> observed =
                bus.dispatch(PARALLEL, new TextEvent("parallel")).toCompletableFuture();
        assertTrue(started.await(10, TimeUnit.SECONDS));
        assertTrue(observed.cancel(false));
        assertTrue(observed.isCancelled());
        assertThrows(CancellationException.class, observed::join);

        CompletableFuture<Void> idle = bus.whenIdle().toCompletableFuture();
        CompletableFuture<Void> subscriptionClose = subscription.closeAsync().toCompletableFuture();
        CompletableFuture<Void> busClose = bus.closeAsync().toCompletableFuture();
        assertFalse(idle.isDone());
        assertFalse(subscriptionClose.isDone());
        assertFalse(busClose.isDone());
        assertTrue(hasEventDispatch(bus.pendingOperations()));

        gate.complete(null);

        assertTrue(listenerFinished.get(10, TimeUnit.SECONDS));
        idle.get(10, TimeUnit.SECONDS);
        subscriptionClose.get(10, TimeUnit.SECONDS);
        busClose.get(10, TimeUnit.SECONDS);
        assertFalse(hasEventDispatch(bus.pendingOperations()));
        // 内部已收敛，但被取消的观察永远保持取消，不会“复活”为结果。
        assertTrue(observed.isCancelled());
        assertThrows(CancellationException.class, observed::join);
    }

    @Test
    void serialPublicCancelKeepsInternalDispatchAndClearsEveryLease() throws Exception {
        EventBus bus = new DefaultEventBus();
        CountDownLatch started = new CountDownLatch(1);
        CompletableFuture<Boolean> gate = new CompletableFuture<>();
        CompletableFuture<Boolean> listenerFinished = new CompletableFuture<>();
        EventSubscription subscription = bus.subscribe(SERIAL, event -> {
            started.countDown();
            return gate.whenComplete((ignored, error) ->
                    listenerFinished.complete(error == null));
        });

        CompletableFuture<EventDispatch<TextEvent>> observed =
                bus.dispatch(SERIAL, new TextEvent("serial")).toCompletableFuture();
        assertTrue(started.await(10, TimeUnit.SECONDS));
        assertTrue(observed.cancel(false));
        assertTrue(observed.isCancelled());

        CompletableFuture<Void> idle = bus.whenIdle().toCompletableFuture();
        CompletableFuture<Void> subscriptionClose = subscription.closeAsync().toCompletableFuture();
        CompletableFuture<Void> busClose = bus.closeAsync().toCompletableFuture();
        assertFalse(idle.isDone());
        assertFalse(subscriptionClose.isDone());
        assertFalse(busClose.isDone());

        gate.complete(true);

        assertTrue(listenerFinished.get(10, TimeUnit.SECONDS));
        idle.get(10, TimeUnit.SECONDS);
        subscriptionClose.get(10, TimeUnit.SECONDS);
        busClose.get(10, TimeUnit.SECONDS);
        assertFalse(hasEventDispatch(bus.pendingOperations()));
        assertTrue(observed.isCancelled());
    }

    @Test
    void publicCancelStillReleasesCanonicalBindingForReuse() throws Exception {
        EventBus bus = new DefaultEventBus();
        CountDownLatch started = new CountDownLatch(1);
        CompletableFuture<Boolean> gate = new CompletableFuture<>();
        EventDefinition.Serial<TextEvent> held =
                EventDefinition.serial("shared-cancel-binding", TextEvent.class);
        EventDefinition.Sync<OtherEvent> conflicting =
                EventDefinition.sync("shared-cancel-binding", OtherEvent.class);
        EventSubscription subscription = bus.subscribe(held, event -> {
            started.countDown();
            return gate;
        });

        CompletableFuture<EventDispatch<TextEvent>> observed =
                bus.dispatch(held, new TextEvent("held")).toCompletableFuture();
        assertTrue(started.await(10, TimeUnit.SECONDS));
        assertTrue(observed.cancel(false));

        // 分发仍在途：规范绑定继续由已接受的 dispatch 持有，冲突类型不能抢占。
        assertThrows(IllegalArgumentException.class, () ->
                bus.subscribe(conflicting, event -> {}));

        gate.complete(true);
        bus.whenIdle().toCompletableFuture().get(10, TimeUnit.SECONDS);
        subscription.unsubscribe();

        // 观察被取消不阻止 finish 释放绑定：静默后同名事件可绑定到新类型。
        bus.whenIdle().toCompletableFuture().get(10, TimeUnit.SECONDS);
        EventSubscription rebound = bus.subscribe(conflicting, event -> {});
        assertTrue(rebound.active());
        assertTrue(observed.isCancelled());
    }

    @Test
    void cancellingOneObservationDoesNotAffectOtherObserversOfTheSameWork() throws Exception {
        EventBus bus = new DefaultEventBus();
        CountDownLatch started = new CountDownLatch(1);
        CompletableFuture<Void> gate = new CompletableFuture<>();
        bus.subscribe(PARALLEL, event -> {
            started.countDown();
            return gate;
        });

        CompletableFuture<EventDispatch<TextEvent>> observed =
                bus.dispatch(PARALLEL, new TextEvent("observers")).toCompletableFuture();
        assertTrue(started.await(10, TimeUnit.SECONDS));
        CompletableFuture<Void> idleFirst = bus.whenIdle().toCompletableFuture();
        CompletableFuture<Void> idleSecond = bus.whenIdle().toCompletableFuture();
        CompletableFuture<Void> closeFirst = bus.closeAsync().toCompletableFuture();
        CompletableFuture<Void> closeSecond = bus.closeAsync().toCompletableFuture();
        assertNotSame(closeFirst, closeSecond);

        assertTrue(observed.cancel(false));
        assertTrue(closeFirst.cancel(false));
        gate.complete(null);

        idleFirst.get(10, TimeUnit.SECONDS);
        idleSecond.get(10, TimeUnit.SECONDS);
        closeSecond.get(10, TimeUnit.SECONDS);
        assertTrue(observed.isCancelled());
        assertTrue(closeFirst.isCancelled());
        // close() 通过新的独立观察等待真实收敛，不受被取消观察影响。
        assertDoesNotThrow(bus::close);
    }

    @Test
    void mirrorPassesThroughValueAndStaysCancelledAfterPublicCancel() throws Exception {
        EventBus bus = new DefaultEventBus();
        bus.subscribe(PARALLEL, event -> CompletableFuture.completedFuture(null));
        bus.subscribe(SERIAL, event -> CompletableFuture.completedFuture(true));

        CompletableFuture<EventDispatch<TextEvent>> parallelObserved =
                bus.dispatch(PARALLEL, new TextEvent("value")).toCompletableFuture();
        EventDispatch<TextEvent> parallelResult = parallelObserved.get(10, TimeUnit.SECONDS);
        assertTrue(parallelResult.successful());
        assertEquals(1, parallelResult.completedCount());
        // 已完成的 mirror 再 cancel 是无操作，不改变已传递的结果。
        assertFalse(parallelObserved.cancel(false));
        assertTrue(parallelResult.successful());

        CountDownLatch started = new CountDownLatch(1);
        CompletableFuture<Boolean> gate = new CompletableFuture<>();
        bus.subscribe(SERIAL, event -> {
            started.countDown();
            return gate;
        });
        CompletableFuture<EventDispatch<TextEvent>> cancelled =
                bus.dispatch(SERIAL, new TextEvent("cancel")).toCompletableFuture();
        assertTrue(started.await(10, TimeUnit.SECONDS));
        assertTrue(cancelled.cancel(false));
        gate.complete(true);
        bus.whenIdle().toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertTrue(cancelled.isCancelled());
        assertThrows(CancellationException.class, cancelled::join);
    }

    @Test
    void pluginClassLoaderIsCollectableAfterCancelledDispatchAndClose() throws Exception {
        for (int iteration = 0; iteration < 5; iteration++) {
            References references = createBusWithCancelledPluginDispatch();
            assertTrue(awaitCleared(references.bus(), references.loader()));
        }
    }

    @Test
    void hundredRoundsOfSerialAndParallelPublicCancelStillConverge() throws Exception {
        for (int round = 0; round < 100; round++) {
            EventBus bus = new DefaultEventBus();
            CountDownLatch parallelStarted = new CountDownLatch(1);
            CountDownLatch serialStarted = new CountDownLatch(1);
            CompletableFuture<Void> parallelGate = new CompletableFuture<>();
            CompletableFuture<Boolean> serialGate = new CompletableFuture<>();

            bus.subscribe(PARALLEL, event -> {
                parallelStarted.countDown();
                return parallelGate;
            });
            bus.subscribe(SERIAL, event -> {
                serialStarted.countDown();
                return serialGate;
            });

            CompletableFuture<EventDispatch<TextEvent>> parallelObserved =
                    bus.dispatch(PARALLEL, new TextEvent("p-" + round)).toCompletableFuture();
            CompletableFuture<EventDispatch<TextEvent>> serialObserved =
                    bus.dispatch(SERIAL, new TextEvent("s-" + round)).toCompletableFuture();
            assertTrue(parallelStarted.await(10, TimeUnit.SECONDS));
            assertTrue(serialStarted.await(10, TimeUnit.SECONDS));
            assertTrue(parallelObserved.cancel(false));
            assertTrue(serialObserved.cancel(false));

            CompletableFuture<Void> idle = bus.whenIdle().toCompletableFuture();
            CompletableFuture<Void> closing = bus.closeAsync().toCompletableFuture();
            parallelGate.complete(null);
            serialGate.complete(true);

            idle.get(10, TimeUnit.SECONDS);
            closing.get(10, TimeUnit.SECONDS);
            assertTrue(parallelObserved.isCancelled(), "round " + round);
            assertTrue(serialObserved.isCancelled(), "round " + round);
        }
    }

    private static References createBusWithCancelledPluginDispatch() throws Exception {
        EventBus bus = new DefaultEventBus();
        ByteClassLoader loader = new ByteClassLoader();
        Class<?> eventType = loadClass(loader, "dynamic.CancelledPluginEvent");
        EventDefinition.Parallel<Object> definition =
                EventDefinition.parallel((Class<Object>) (Class<?>) eventType);
        EventListenerHolder holder = new EventListenerHolder();
        ParallelEventListener<Object> listener = (ParallelEventListener<Object>)
                Proxy.newProxyInstance(loader, new Class<?>[]{ParallelEventListener.class},
                        (proxy, method, args) -> {
                            if ("listen".equals(method.getName())) {
                                holder.started.countDown();
                                return holder.gate;
                            }
                            return null;
                        });
        EventSubscription subscription = bus.subscribe(definition, listener);

        CompletableFuture<EventDispatch<Object>> observed =
                bus.dispatch(definition, eventType.cast(newInstance(eventType)))
                        .toCompletableFuture();
        assertTrue(holder.started.await(10, TimeUnit.SECONDS));
        assertTrue(observed.cancel(false));
        holder.gate.complete(null);

        subscription.closeAsync().toCompletableFuture().get(10, TimeUnit.SECONDS);
        bus.closeAsync().toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertTrue(observed.isCancelled());

        WeakReference<EventBus> busReference = new WeakReference<>(bus);
        WeakReference<ClassLoader> loaderReference = new WeakReference<>(loader);
        return new References(busReference, loaderReference);
    }

    private static boolean hasEventDispatch(PendingOperationsSnapshot snapshot) {
        return snapshot.operations().stream()
                .anyMatch(item -> item.kind() == PendingOperationsSnapshot.Kind.EVENT_DISPATCH);
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

    private static boolean awaitCleared(WeakReference<?> first, WeakReference<?> second) {
        for (int index = 0; index < 100; index++) {
            System.gc();
            if (first.get() == null && second.get() == null) {
                return true;
            }
        }
        return false;
    }

    private static final class EventListenerHolder {
        final CountDownLatch started = new CountDownLatch(1);
        final CompletableFuture<Void> gate = new CompletableFuture<>();
    }

    private record References(
            WeakReference<EventBus> bus,
            WeakReference<ClassLoader> loader) {
    }

    private record TextEvent(String text) {
    }

    private record OtherEvent(String value) {
    }

    /** 与 QuiescenceTest 相同的最小字节码生成器：为取消隔离的 GC 断言提供插件侧类。 */
    @SuppressWarnings("unused")
    private static final class ByteClassLoader extends URLClassLoader {
        ByteClassLoader() {
            super(new URL[0]);
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            if (!"dynamic.CancelledPluginEvent".equals(name)) {
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
}
