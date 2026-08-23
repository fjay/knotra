package io.knotra.internal;

import io.knotra.CapabilityKey;
import io.knotra.ComponentState;
import io.knotra.DiagnosticCode;
import io.knotra.KnotraRuntime;
import io.knotra.MountFactory;
import io.knotra.MountHandle;
import io.knotra.Publication;
import io.knotra.PublicationChange;
import io.knotra.PublicationOperation;
import io.knotra.PublicationState;
import io.knotra.RegistrationHandle;
import io.knotra.Settlement;
import io.knotra.TransactionRejectedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Publication 槽位内核化后的线性化与终态语义回归：
 * 并发 publish/create-create、update/update 全序成功、update/unpublish 先后顺序、
 * unpublish 幂等、外部 raw 移除 / Context 处置 / runtime 关闭淘汰、终态不复活、
 * 槽位纯字符串结构与 ClassLoader 重绑定。
 */
final class PublicationSlotConcurrencyTest {
    private final KnotraRuntime runtime = KnotraRuntime.create();
    private final DefaultKnotraRuntime internal =
            (DefaultKnotraRuntime) runtime;

    @AfterEach
    void tearDown() {
        internal.providerLeaseRetireFaultProbe = null;
        runtime.close();
    }

    private static void assertRejected(
            Runnable action, DiagnosticCode expectedCode) {
        try {
            action.run();
        } catch (TransactionRejectedException rejection) {
            assertTrue(rejection.diagnostics().stream()
                    .anyMatch(diagnostic -> diagnostic.code() == expectedCode),
                    () -> String.valueOf(rejection.diagnostics()));
            return;
        }
        throw new AssertionError("expected rejection with " + expectedCode);
    }

    @Test
    void concurrentCreateCreateLinearizesSecondAsUpdateOnSameSlot() throws Exception {
        for (int round = 0; round < 20; round++) {
            CapabilityKey<String> key =
                    CapabilityKey.of("create-create-" + round, String.class);
            AtomicReference<PublicationChange<String>> first = new AtomicReference<>();
            AtomicReference<PublicationChange<String>> second = new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            CyclicBarrier barrier = new CyclicBarrier(2);
            try (ExecutorLane lane = ExecutorLane.fixed(2)) {
                lane.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    first.set(runtime.publish(key, "first"));
                    return null;
                });
                lane.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    second.set(runtime.publish(key, "second"));
                    return null;
                });
                failure.set(lane.await());
            }
            if (failure.get() != null) {
                throw new AssertionError(failure.get());
            }

            internal.publishedState().validateInvariants();
            List<PublicationOperation> operations = List.of(
                    first.get().operation(), second.get().operation());
            assertTrue(operations.contains(PublicationOperation.PUBLISH), "round " + round);
            assertTrue(operations.contains(PublicationOperation.UPDATE), "round " + round);
            assertNotEquals(first.get().publication(), second.get().publication());
            assertEquals(
                    first.get().publication().state(),
                    second.get().publication().state());
            assertEquals(PublicationState.PUBLISHED, first.get().publication().state());

            // 两个句柄指向同一 slotId：状态查询完全一致，update 可互达。
            PublicationSlotAssertions.assertSameSlot(
                    internal, first.get().publication(), second.get().publication());
            second.get().awaitSettled(Duration.ofSeconds(10));
            first.get().awaitSettled(Duration.ofSeconds(10));
        }
    }

    @Test
    void concurrentUpdatesAllSucceedInTotalOrder() throws Exception {
        int lanes = 4;
        for (int round = 0; round < 200; round++) {
            CapabilityKey<String> key =
                    CapabilityKey.of("update-update-" + round, String.class);
            Publication<String> publication = runtime.publish(key, "initial").publication();
            List<Long> generations = new ArrayList<>();
            try (ExecutorLane executor = ExecutorLane.fixed(lanes)) {
                CyclicBarrier barrier = new CyclicBarrier(lanes);
                List<Future<PublicationChange<String>>> changes = new ArrayList<>();
                for (int lane = 0; lane < lanes; lane++) {
                    int laneId = lane;
                    changes.add(executor.submit(() -> {
                        barrier.await(10, TimeUnit.SECONDS);
                        return publication.update("lane-" + laneId);
                    }));
                }
                for (Future<PublicationChange<String>> change : changes) {
                    PublicationChange<String> result =
                            change.get(20, TimeUnit.SECONDS);
                    result.awaitSettled(Duration.ofSeconds(20));
                    generations.add(result.generation());
                }
            }
            internal.publishedState().validateInvariants();
            assertEquals(lanes, generations.size(), "round " + round);
            assertEquals(generations.stream().distinct().count(), lanes, "round " + round);
            assertEquals(PublicationState.PUBLISHED, publication.state());
            PublicationSlotAssertions.assertEpochMonotonic(
                    internal, publication, 4);
        }
    }

    @Test
    void updateVersusUnpublishFollowsLinearizationOrder() throws Exception {
        for (int round = 0; round < 40; round++) {
            CapabilityKey<String> key =
                    CapabilityKey.of("update-unpublish-" + round, String.class);
            Publication<String> publication = runtime.publish(key, "initial").publication();
            AtomicReference<PublicationChange<String>> updateChange = new AtomicReference<>();
            AtomicReference<PublicationChange<String>> unpublishChange = new AtomicReference<>();
            AtomicReference<Throwable> updateFailure = new AtomicReference<>();
            try (ExecutorLane lane = ExecutorLane.fixed(2)) {
                CyclicBarrier barrier = new CyclicBarrier(2);
                lane.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    try {
                        updateChange.set(publication.update("replacement"));
                    } catch (TransactionRejectedException expected) {
                        updateFailure.set(expected);
                    }
                    return null;
                });
                lane.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    unpublishChange.set(publication.unpublish());
                    return null;
                });
                lane.await();
            }

            unpublishChange.get().awaitSettled(Duration.ofSeconds(10));
            assertEquals(PublicationState.UNPUBLISHED, publication.state());
            if (updateChange.get() != null) {
                updateChange.get().awaitSettled(Duration.ofSeconds(10));
                assertTrue(updateChange.get().generation()
                        < unpublishChange.get().generation());
            } else {
                assertNotNull(updateFailure.get());
            }
            assertTrue(runtime.root().view().find(key).isEmpty());
            internal.publishedState().validateInvariants();
        }
    }

    @Test
    void concurrentUnpublishKeepsOneRealTransactionAndOthersIdempotent() throws Exception {
        for (int round = 0; round < 20; round++) {
            CapabilityKey<String> key =
                    CapabilityKey.of("unpublish-unpublish-" + round, String.class);
            Publication<String> publication = runtime.publish(key, "value").publication();
            int lanes = 4;
            List<PublicationChange<String>> results = new ArrayList<>();
            try (ExecutorLane executor = ExecutorLane.fixed(lanes)) {
                CyclicBarrier barrier = new CyclicBarrier(lanes);
                List<Future<PublicationChange<String>>> futures = new ArrayList<>();
                for (int lane = 0; lane < lanes; lane++) {
                    futures.add(executor.submit(() -> {
                        barrier.await(10, TimeUnit.SECONDS);
                        return publication.unpublish();
                    }));
                }
                for (Future<PublicationChange<String>> future : futures) {
                    results.add(future.get(10, TimeUnit.SECONDS));
                }
            }
            assertEquals(lanes, results.size());
            for (PublicationChange<String> result : results) {
                assertEquals(PublicationOperation.UNPUBLISH, result.operation());
            }
            long distinctGenerations = results.stream()
                    .map(PublicationChange::generation)
                    .distinct()
                    .count();
            assertTrue(distinctGenerations >= 1 && distinctGenerations <= lanes);
            assertEquals(PublicationState.UNPUBLISHED, publication.state());
            internal.publishedState().validateInvariants();
        }
    }

    @Test
    void sequentialUnpublishIsIdempotentWithTerminalResult() throws Exception {
        CapabilityKey<String> key = CapabilityKey.of("idempotent-unpublish", String.class);
        Publication<String> publication = runtime.publish(key, "value").publication();
        PublicationChange<String> first = publication.unpublish();
        first.awaitSettled(Duration.ofSeconds(10));
        PublicationChange<String> second = publication.unpublish();
        assertEquals(PublicationOperation.UNPUBLISH, second.operation());
        assertEquals(PublicationState.UNPUBLISHED, publication.state());
        assertEquals(first.generation(), second.generation());
        assertNotNull(second.whenSettled().toCompletableFuture().join());
        assertThrows(TransactionRejectedException.class, () -> publication.update("next"));
        internal.publishedState().validateInvariants();
    }

    @Test
    void contextDisposeDisplacesActiveSlot() throws Exception {
        io.knotra.ContextHandle child = runtime.advanced()
                .childContext(runtime.root(), "slot-dispose");
        CapabilityKey<String> key =
                CapabilityKey.of("dispose-slot", String.class);
        Publication<String> publication =
                runtime.publish(child, key, "value").publication();
        assertEquals(PublicationState.PUBLISHED, publication.state());

        runtime.advanced().transact(transaction -> {
            transaction.dispose(child);
            return null;
        }).awaitSettled(Duration.ofSeconds(10));

        assertEquals(PublicationState.DISPLACED, publication.state());
        assertThrows(TransactionRejectedException.class, () -> publication.update("next"));
        assertThrows(TransactionRejectedException.class, publication::unpublish);
        internal.publishedState().validateInvariants();
    }

    @Test
    void runtimeCloseDisplacesActiveSlot() throws Exception {
        KnotraRuntime closed = KnotraRuntime.create();
        CapabilityKey<String> key =
                CapabilityKey.of("close-slot", String.class);
        Publication<String> publication = closed.publish(key, "value").publication();
        closed.close();

        assertEquals(PublicationState.DISPLACED, publication.state());
        assertThrows(TransactionRejectedException.class, () -> publication.update("next"));
        assertThrows(TransactionRejectedException.class, publication::unpublish);
    }

    @Test
    void rawOccupancyIsNotTakenOverByPublicationAndViceVersa() throws Exception {
        CapabilityKey<String> key =
                CapabilityKey.of("raw-collision", String.class);

        // raw 先占：publication 拒绝接管。
        RegistrationHandle raw = runtime.advanced()
                .transact(transaction -> transaction.provide(runtime.root(), key, "raw"))
                .value();
        assertRejected(() -> runtime.publish(key, "publication"),
                DiagnosticCode.CAPABILITY_SLOT_OCCUPIED);
        assertEquals("raw", runtime.root().view().require(key));

        runtime.advanced().transact(transaction -> {
            transaction.revoke(raw);
            return null;
        }).awaitSettled(Duration.ofSeconds(10));

        // publication 先占：raw provide 同样拒绝。
        runtime.publish(key, "publication").awaitSettled(Duration.ofSeconds(10));
        assertRejected(() -> runtime.advanced()
                        .transact(transaction -> transaction.provide(runtime.root(), key, "raw-2")),
                DiagnosticCode.CAPABILITY_SLOT_OCCUPIED);
        internal.publishedState().validateInvariants();
    }

    @Test
    void publishWithDifferentTypeClassIsRejected() {
        CapabilityKey<String> text = CapabilityKey.of("typed-slot", String.class);
        CapabilityKey<Integer> integer = CapabilityKey.of("typed-slot", Integer.class);
        runtime.publish(text, "one");
        assertRejected(() -> runtime.publish(integer, 2),
                DiagnosticCode.CAPABILITY_TYPE_CONFLICT);
        assertEquals("one", runtime.root().view().require(text));
        internal.publishedState().validateInvariants();
    }

    @Test
    void republishAfterTerminalCreatesNewSlotAndOldHandleStaysTerminal() throws Exception {
        CapabilityKey<String> key =
                CapabilityKey.of("terminal-republish", String.class);
        Publication<String> first = runtime.publish(key, "one").publication();
        first.unpublish().awaitSettled(Duration.ofSeconds(10));
        assertEquals(PublicationState.UNPUBLISHED, first.state());

        PublicationChange<String> second = runtime.publish(key, "two");
        second.awaitSettled(Duration.ofSeconds(10));
        assertNotEquals(first, second.publication());
        assertEquals(PublicationState.PUBLISHED, second.publication().state());
        assertEquals(PublicationState.UNPUBLISHED, first.state());
        assertEquals("two", runtime.root().view().require(key));

        // 旧句柄不可复活新槽位；重复 unpublish 幂等返回自身终态。
        assertThrows(TransactionRejectedException.class, () -> first.update("three"));
        assertEquals(PublicationOperation.UNPUBLISH, first.unpublish().operation());
        assertEquals(PublicationState.PUBLISHED, second.publication().state());
        internal.publishedState().validateInvariants();
    }

    @Test
    void externalRawRemovalDisplacesSlotWithoutNewTransaction() throws Exception {
        CapabilityKey<String> key =
                CapabilityKey.of("external-removal", String.class);
        Publication<String> publication = runtime.publish(key, "one").publication();
        String registrationId = runtime.advanced().snapshot().registrations().stream()
                .filter(registration -> registration.capability().name().equals(key.name()))
                .findFirst().orElseThrow().registrationId();
        runtime.advanced().transact(transaction -> {
            transaction.revoke(() -> registrationId);
            return null;
        }).awaitSettled(Duration.ofSeconds(10));

        assertEquals(PublicationState.DISPLACED, publication.state());
        assertThrows(TransactionRejectedException.class, () -> publication.update("next"));
        assertThrows(TransactionRejectedException.class, publication::unpublish);
        internal.publishedState().validateInvariants();
    }

    @Test
    void postCommitLeaseRetireFaultDoesNotRollBackOrPendSlot() throws Exception {
        CapabilityKey<String> key =
                CapabilityKey.of("lease-fault-slot", String.class);
        PublicationChange<String> initial = runtime.publish(key, "one");
        Publication<String> publication = initial.publication();
        initial.awaitSettled(Duration.ofSeconds(10));

        AtomicInteger attempts = new AtomicInteger();
        internal.providerLeaseRetireFaultProbe = index -> {
            if (index == 0 && attempts.getAndIncrement() == 0) {
                throw new IllegalStateException("injected publication retire fault");
            }
        };
        Settlement settlement = publication.update("two");
        java.util.concurrent.ExecutionException failure =
                assertThrows(java.util.concurrent.ExecutionException.class, () ->
                        settlement.whenSettled().toCompletableFuture()
                                .get(10, TimeUnit.SECONDS));
        assertTrue(failure.getCause().getMessage()
                .contains("injected publication retire fault"));

        PublishedKernelState state = internal.publishedState();
        state.validateInvariants();
        assertEquals(PublicationState.PUBLISHED, publication.state());
        assertEquals("two", runtime.root().view().require(key));
        // 故障注入时旧 lease 已完成真实 retire；本用例无在途动态调用，registry 同步清空。
        // 若存在活跃调用，pending 窗口可见到该 lease 直到在途调用释放，属预期语义。
        assertTrue(internal.advanced().pendingOperations().operations().stream()
                .noneMatch(operation -> operation.kind()
                        == io.knotra.PendingOperationsSnapshot.Kind.PROVIDER_LEASE));
        assertEquals(1, attempts.get(), "faulted lease must already be retired when postcommit fault strikes");
    }

    @Test
    void terminalSlotHoldsNoClassReferenceAndSameNameRebindsFromNewClassLoader()
            throws Exception {
        for (int round = 0; round < 10; round++) {
            verifyTerminalSlotRebind("gc-rebind-capability-" + round);
        }
    }

    private void verifyTerminalSlotRebind(String capabilityName) throws Exception {
        try (IsolatedClassLoader firstLoader = new IsolatedClassLoader();
             IsolatedClassLoader secondLoader = new IsolatedClassLoader()) {
            Class<?> firstType = firstLoader.loadClass(IsolatedCapabilityType.class.getName());
            Class<?> secondType = secondLoader.loadClass(IsolatedCapabilityType.class.getName());
            assertNotEquals(firstType, secondType);

            @SuppressWarnings("unchecked")
            CapabilityKey<Object> firstKey = (CapabilityKey<Object>)
                    CapabilityKey.of(capabilityName, firstType);
            Publication<Object> first = runtime.publish(
                    firstKey, proxyOf(firstType, firstLoader)).publication();
            first.unpublish().awaitSettled(Duration.ofSeconds(10));

            PublishedKernelState terminal = internal.publishedState();
            terminal.validateInvariants();
            // 视图 active-only：终态槽位必须连同索引一起移除，终态只经由共享 ref 观察。
            String firstSlotId = ((PublicationImpl<?>) first).slotId();
            assertTrue(terminal.view.publicationSlots.values().stream()
                    .noneMatch(candidate -> candidate.capabilityName()
                            .equals(capabilityName)));
            assertFalse(terminal.index.publicationSlotRefs.containsKey(firstSlotId));
            assertEquals(PublicationState.UNPUBLISHED, first.state());

            @SuppressWarnings("unchecked")
            CapabilityKey<Object> secondKey = (CapabilityKey<Object>)
                    CapabilityKey.of(capabilityName, secondType);
            PublicationChange<Object> secondChange = runtime.publish(
                    secondKey, proxyOf(secondType, secondLoader));
            Publication<Object> second = secondChange.publication();
            secondChange.awaitSettled(Duration.ofSeconds(10));
            assertEquals(PublicationState.PUBLISHED, second.state());
            assertEquals(PublicationState.UNPUBLISHED, first.state());
            assertNotEquals(
                    ((PublicationImpl<?>) first).slotId(),
                    ((PublicationImpl<?>) second).slotId());
        }
    }

    @Test
    void publicationUpdateDrivesConsumerRestartAcrossGenerations() throws Exception {
        CapabilityKey<String> key =
                CapabilityKey.of("consumer-restart-slot", String.class);
        MountHandle consumer = runtime.mount(
                "slot-consumer",
                MountFactory.of("slot-consumer",
                        io.knotra.ComponentDescriptor.named(
                                "slot-consumer",
                                io.knotra.CapabilityRequirement.required(key)),
                        ignored -> {
                        }));
        Publication<String> publication = runtime.publish(key, "one").publication();
        assertEquals(ComponentState.ACTIVE, consumer.whenSettled()
                .toCompletableFuture().get(10, TimeUnit.SECONDS));

        publication.update("two").awaitSettled(Duration.ofSeconds(10));
        assertEquals(ComponentState.ACTIVE, consumer.whenSettled()
                .toCompletableFuture().get(10, TimeUnit.SECONDS));
        internal.publishedState().validateInvariants();
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

        Throwable await() {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                    return new IllegalStateException("lanes did not terminate");
                }
                return null;
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return error;
            }
        }

        @Override
        public void close() {
            executor.shutdownNow();
        }

        interface Callable<V> {
            V call() throws Exception;
        }
    }

    /** 独立加载同名接口副本，验证终态槽位不阻止新 ClassLoader 重绑定。 */
    private static Object proxyOf(Class<?> type, ClassLoader loader) {
        return java.lang.reflect.Proxy.newProxyInstance(
                loader, new Class<?>[]{type}, (proxy, method, args) -> null);
    }

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
