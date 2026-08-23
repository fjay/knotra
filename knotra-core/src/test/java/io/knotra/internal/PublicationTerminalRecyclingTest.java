package io.knotra.internal;

import io.knotra.CapabilityKey;
import io.knotra.KnotraRuntime;
import io.knotra.Publication;
import io.knotra.PublicationChange;
import io.knotra.PublicationState;
import io.knotra.TransactionRejectedException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.ref.WeakReference;
import java.time.Duration;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Publication 终态 tombstone 回收回归：
 * 同坐标高频 churn 后活跃视图/索引不再累积历史终态槽位；
 * 终态语义完全由共享 ref 承载（多句柄共享、并发 terminal/republish、prepublish 失败不完成、
 * postpublish 故障不回滚、ref 与隔离 ClassLoader 均可回收）。
 */
final class PublicationTerminalRecyclingTest {
    private final KnotraRuntime runtime = KnotraRuntime.create();
    private final DefaultKnotraRuntime internal =
            (DefaultKnotraRuntime) runtime;

    @AfterEach
    void tearDown() {
        internal.transitionScheduler.transitionReservationProbe = null;
        internal.providerLeaseRetireFaultProbe = null;
        runtime.close();
    }

    @Test
    void hundredThousandChurnKeepsActiveMapsBounded() {
        CapabilityKey<String> key =
                CapabilityKey.of("terminal-churn", String.class);
        for (int i = 0; i < 100_000; i++) {
            runtime.publish(key, "v" + i).publication().unpublish();
        }
        PublishedKernelState finalState = internal.publishedState();
        finalState.validateInvariants();
        assertTrue(finalState.view.publicationSlots.size() <= 1,
                "active view must not accumulate terminal slots: "
                        + finalState.view.publicationSlots.size());
        assertTrue(finalState.view.activePublicationSlots.size() <= 1);
        assertTrue(finalState.index.publicationSlotRefs.isEmpty(),
                "terminal refs must leave the live index: "
                        + finalState.index.publicationSlotRefs.size());
    }

    @Test
    void sharedRefCoversMultiHandleUnpublishDisplaceAndUpdateNotTerminal() throws Exception {
        CapabilityKey<String> key =
                CapabilityKey.of("multi-handle-slot", String.class);
        Publication<String> first = runtime.publish(key, "one").publication();
        Publication<String> second = runtime.publish(key, "two").publication();
        PublicationImpl<String> firstImpl = (PublicationImpl<String>) first;
        PublicationImpl<String> secondImpl = (PublicationImpl<String>) second;

        assertEquals(firstImpl.slotId(), secondImpl.slotId());
        assertSame(firstImpl.terminalRef(), secondImpl.terminalRef());
        assertNull(firstImpl.terminalRef().terminalData());
        assertEquals(PublicationState.PUBLISHED, first.state());
        assertEquals(PublicationState.PUBLISHED, second.state());

        // UPDATE 不是终态：epoch 前进但 ref 不完成、槽位保持活跃。
        second.update("three").awaitSettled(Duration.ofSeconds(10));
        assertNull(firstImpl.terminalRef().terminalData());
        assertEquals(PublicationState.PUBLISHED, first.state());
        internal.publishedState().validateInvariants();

        first.unpublish().awaitSettled(Duration.ofSeconds(10));
        assertEquals(PublicationState.UNPUBLISHED, first.state());
        assertEquals(PublicationState.UNPUBLISHED, second.state());
        assertEquals(PublicationState.UNPUBLISHED,
                firstImpl.terminalRef().terminalData().state());
        assertNull(internal.publishedState().view.publicationSlots
                .get(firstImpl.slotId()));
        internal.publishedState().validateInvariants();

        // 终态后 republish：新 slotId + 新 ref，旧句柄不观察新槽位。
        PublicationImpl<String> renewed =
                (PublicationImpl<String>) runtime.publish(key, "next").publication();
        assertNotEquals(firstImpl.slotId(), renewed.slotId());
        assertNotSame(firstImpl.terminalRef(), renewed.terminalRef());
        assertEquals(PublicationState.UNPUBLISHED, first.state());
        assertEquals(PublicationState.PUBLISHED, renewed.state());
        assertThrows(TransactionRejectedException.class, () -> first.update("x"));
        internal.publishedState().validateInvariants();
    }

    @Test
    void directAndTransactionalContextDisposeDisplaceSharedHandles() throws Exception {
        io.knotra.ContextHandle directChild = runtime.advanced()
                .childContext(runtime.root(), "direct-dispose");
        Publication<String> direct = runtime.publish(
                directChild, CapabilityKey.of("direct-dispose-cap", String.class), "v")
                .publication();
        directChild.disposeAsync().toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertEquals(PublicationState.DISPLACED, direct.state());
        assertThrows(TransactionRejectedException.class, direct::unpublish);
        internal.publishedState().validateInvariants();

        io.knotra.ContextHandle txChild = runtime.advanced()
                .childContext(runtime.root(), "tx-dispose");
        Publication<String> transactional = runtime.publish(
                txChild, CapabilityKey.of("tx-dispose-cap", String.class), "v")
                .publication();
        runtime.advanced().transact(transaction -> {
            transaction.dispose(txChild);
            return null;
        }).awaitSettled(Duration.ofSeconds(10));
        assertEquals(PublicationState.DISPLACED, transactional.state());
        assertThrows(TransactionRejectedException.class, transactional::unpublish);
        internal.publishedState().validateInvariants();
    }

    @Test
    void concurrentTerminalVersusRepublishKeepsOldHandleTerminal() throws Exception {
        for (int round = 0; round < 50; round++) {
            CapabilityKey<String> key = CapabilityKey.of(
                    "terminal-race-" + round, String.class);
            PublicationImpl<String> oldHandle =
                    (PublicationImpl<String>) runtime.publish(key, "old").publication();
            AtomicReference<PublicationChange<String>> republished =
                    new AtomicReference<>();
            AtomicReference<Throwable> unpublishFailure = new AtomicReference<>();
            AtomicReference<Throwable> republishFailure = new AtomicReference<>();
            try (ExecutorLane lane = ExecutorLane.fixed(2)) {
                CyclicBarrier barrier = new CyclicBarrier(2);
                lane.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    try {
                        oldHandle.unpublish().awaitSettled(Duration.ofSeconds(10));
                    } catch (Throwable error) {
                        unpublishFailure.set(error);
                    }
                    return null;
                });
                lane.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    try {
                        republished.set(runtime.publish(key, "new"));
                    } catch (Throwable error) {
                        republishFailure.set(error);
                    }
                    return null;
                });
                lane.await();
            }
            assertNull(unpublishFailure.get());
            assertNull(republishFailure.get());
            assertEquals(PublicationState.UNPUBLISHED, oldHandle.state());
            assertNotNull(oldHandle.terminalRef().terminalData());

            PublicationImpl<String> renewed =
                    (PublicationImpl<String>) republished.get().publication();
            if (renewed.slotId().equals(oldHandle.slotId())) {
                // publish 先线性化为同槽 UPDATE，随后 unpublish 终态化同一槽位。
                assertSame(oldHandle.terminalRef(), renewed.terminalRef());
                assertEquals(PublicationState.UNPUBLISHED, renewed.state());
            } else {
                // unpublish 先终态化：republish 创建新槽位与新 ref，旧句柄不看新槽位。
                assertNotSame(oldHandle.terminalRef(), renewed.terminalRef());
                assertEquals(PublicationState.PUBLISHED, renewed.state());
            }
            internal.publishedState().validateInvariants();
        }
    }

    @Test
    void prepublishFailureDoesNotCompleteTerminalRefOrPublishTerminalView() throws Exception {
        CapabilityKey<String> key =
                CapabilityKey.of("prepublish-fail-slot", String.class);
        PublicationImpl<String> publication =
                (PublicationImpl<String>) runtime.publish(key, "one").publication();
        internal.transitionScheduler.transitionReservationProbe = () -> {
            throw new IllegalStateException("injected prepublish failure");
        };
        assertThrows(IllegalStateException.class, publication::unpublish);

        // 事务被拒：旧代际仍活跃，ref 绝不提前完成。
        assertEquals(PublicationState.PUBLISHED, publication.state());
        assertNull(publication.terminalRef().terminalData());
        assertNotNull(internal.publishedState().view.publicationSlots
                .get(publication.slotId()));
        internal.publishedState().validateInvariants();

        internal.transitionScheduler.transitionReservationProbe = null;
        publication.unpublish().awaitSettled(Duration.ofSeconds(10));
        assertEquals(PublicationState.UNPUBLISHED, publication.state());
        assertEquals(PublicationState.UNPUBLISHED,
                publication.terminalRef().terminalData().state());
        internal.publishedState().validateInvariants();
    }

    @Test
    void postpublishFaultDoesNotRollBackCommittedTerminal() throws Exception {
        CapabilityKey<String> key =
                CapabilityKey.of("postpublish-fault-slot", String.class);
        PublicationImpl<String> publication =
                (PublicationImpl<String>) runtime.publish(key, "one").publication();

        AtomicInteger attempts = new AtomicInteger();
        internal.providerLeaseRetireFaultProbe = index -> {
            if (index == 0 && attempts.getAndIncrement() == 0) {
                throw new IllegalStateException("injected terminal retire fault");
            }
        };
        PublicationChange<String> removed = publication.unpublish();
        java.util.concurrent.ExecutionException failure =
                assertThrows(java.util.concurrent.ExecutionException.class, () ->
                        removed.whenSettled().toCompletableFuture()
                                .get(10, TimeUnit.SECONDS));
        assertTrue(failure.getCause().getMessage()
                .contains("injected terminal retire fault"));

        // 结构不回滚：终态已提交，视图/索引立即移除槽位，ref 保持终态。
        assertEquals(PublicationState.UNPUBLISHED, publication.state());
        assertNull(internal.publishedState().view.publicationSlots
                .get(publication.slotId()));
        assertNull(internal.publishedState().index.publicationSlotRefs
                .get(publication.slotId()));
        assertEquals(PublicationState.UNPUBLISHED,
                publication.terminalRef().terminalData().state());
        internal.publishedState().validateInvariants();
    }

    @Test
    void terminalRefAndIsolatedClassLoaderAreCollectableAfterTerminal() throws Exception {
        WeakReference<PublicationSlotTerminalRef> refWeak;
        WeakReference<ClassLoader> loaderWeak;
        {
            IsolatedClassLoader loader = new IsolatedClassLoader();
            Class<?> type = loader.loadClass(IsolatedCapabilityType.class.getName());
            @SuppressWarnings("unchecked")
            CapabilityKey<Object> key = (CapabilityKey<Object>)
                    CapabilityKey.of("terminal-gc-capability", type);
            Publication<Object> publication =
                    runtime.publish(key, proxyOf(type, loader)).publication();
            PublicationSlotTerminalRef ref =
                    ((PublicationImpl<Object>) publication).terminalRef();
            refWeak = new WeakReference<>(ref);
            loaderWeak = new WeakReference<>(loader);

            publication.unpublish().awaitSettled(Duration.ofSeconds(10));
            publication = null;
            key = null;
            type = null;
            ref = null;
            loader = null;
        }
        assertTrue(gcCleared(refWeak), "terminal ref must be collectable");
        assertTrue(gcCleared(loaderWeak), "isolated class loader must be collectable");
    }

    private static boolean gcCleared(WeakReference<?> reference) {
        for (int attempt = 0; attempt < 100 && reference.get() != null; attempt++) {
            System.gc();
            System.runFinalization();
            try {
                Thread.sleep(10);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return reference.get() == null;
    }

    /** 小型 AutoCloseable 线程池封装，简化并发用例的资源清理。 */
    private static final class ExecutorLane implements AutoCloseable {
        private final ExecutorService executor;

        private ExecutorLane(ExecutorService executor) {
            this.executor = executor;
        }

        static ExecutorLane fixed(int lanes) {
            return new ExecutorLane(Executors.newFixedThreadPool(lanes));
        }

        <V> Future<V> submit(Callable<V> task) {
            return executor.submit(() -> task.call());
        }

        Throwable await() throws InterruptedException {
            executor.shutdown();
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                return new IllegalStateException("lanes did not terminate");
            }
            return null;
        }

        @Override
        public void close() {
            executor.shutdownNow();
        }

        interface Callable<V> {
            V call() throws Exception;
        }
    }

    private static Object proxyOf(Class<?> type, ClassLoader loader) {
        return java.lang.reflect.Proxy.newProxyInstance(
                loader, new Class<?>[]{type}, (proxy, method, args) -> null);
    }

    /** 独立加载同名接口副本，验证终态 ref 不阻止新 ClassLoader 重绑定与回收。 */
    private static final class IsolatedClassLoader extends ClassLoader
            implements AutoCloseable {
        IsolatedClassLoader() {
            super(null);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve)
                throws ClassNotFoundException {
            if (IsolatedCapabilityType.class.getName().equals(name)) {
                Class<?> isolated = findClass(name);
                if (resolve) {
                    resolveClass(isolated);
                }
                return isolated;
            }
            return super.loadClass(name, resolve);
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            try {
                return findClassInternal(name);
            } catch (java.io.IOException error) {
                throw new ClassNotFoundException(name, error);
            }
        }

        private Class<?> findClassInternal(String name)
                throws ClassNotFoundException, java.io.IOException {
            if (!IsolatedCapabilityType.class.getName().equals(name)) {
                throw new ClassNotFoundException(name);
            }
            String resource = name.replace('.', '/') + ".class";
            try (var input = IsolatedClassLoader.class.getClassLoader()
                    .getResourceAsStream(resource)) {
                if (input == null) {
                    throw new ClassNotFoundException(name);
                }
                byte[] bytes = input.readAllBytes();
                return defineClass(name, bytes, 0, bytes.length);
            }
        }

        @Override
        public void close() {
            // ClassLoader 本身无需显式关闭；try-with-resources 只约束生命周期作用域。
        }
    }
}
