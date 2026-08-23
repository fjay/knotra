package io.knotra;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

final class PendingOperationsRuntimeTest {
    private static final CapabilityKey<String> X = CapabilityKey.of("pending-x", String.class);

    private KnotraRuntime runtime = KnotraRuntime.create();

    @AfterEach
    void tearDown() throws Exception {
        try {
            runtime.close();
        } catch (Exception ignored) {
            // 失败关闭测试在此处完成最终排空。
        }
    }

    @Test
    void blockedStartIsVisibleAndClearsAfterSettlement() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CompletableFuture<Void> gate = new CompletableFuture<>();
        MountHandle handle = TestKit.mount(runtime, runtime.root(), "blocked-start",
                (context, config) -> {
                    entered.countDown();
                    gate.join();
                });
        assertTrue(entered.await(10, TimeUnit.SECONDS));

        ExecutorService readers = Executors.newFixedThreadPool(100);
        try {
            List<CompletableFuture<PendingOperationsSnapshot>> results =
                    IntStream.range(0, 100)
                            .mapToObj(ignored -> CompletableFuture.supplyAsync(
                                    () -> assertTimeoutPreemptively(
                                            Duration.ofMillis(200),
                                            runtime.advanced()::pendingOperations),
                                    readers))
                            .toList();
            for (CompletableFuture<PendingOperationsSnapshot> result : results) {
                PendingOperationsSnapshot snapshot = result.get(10, TimeUnit.SECONDS);
                PendingOperationsSnapshot.Operation operation = find(snapshot,
                        item -> item.kind() == PendingOperationsSnapshot.Kind.COMPONENT_TRANSITION
                                && item.targetId().equals(handle.handleId()));
                assertEquals(PendingOperationsSnapshot.WaitType.COMPONENT,
                        operation.waitsFor());
                assertEquals("component activation start", operation.detail());
                assertFalse(operation.age().isNegative());
            }
        } finally {
            gate.complete(null);
            readers.shutdown();
            assertTrue(readers.awaitTermination(10, TimeUnit.SECONDS));
        }

        assertEquals(ComponentState.ACTIVE, handle.whenSettled()
                .toCompletableFuture().get(10, TimeUnit.SECONDS));
        awaitNoOperations(item -> item.targetId().equals(handle.handleId()));
    }

    @Test
    void asyncLifecycleDisposerIsVisibleAndClearsAfterRelease() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CompletableFuture<Void> gate = new CompletableFuture<>();
        MountHandle handle = TestKit.mount(runtime, runtime.root(), "async-cleanup",
                (context, config) -> context.lifecycle().onCloseAsync(
                        "blocked disposer", () -> {
                            entered.countDown();
                            return gate;
                        }));
        assertEquals(ComponentState.ACTIVE, handle.whenSettled()
                .toCompletableFuture().get(10, TimeUnit.SECONDS));

        CompletableFuture<ComponentState> disposed =
                handle.disposeAsync().toCompletableFuture();
        assertTrue(entered.await(10, TimeUnit.SECONDS));
        PendingOperationsSnapshot snapshot = assertTimeoutPreemptively(
                Duration.ofMillis(200), runtime.advanced()::pendingOperations);
        PendingOperationsSnapshot.Operation cleanup = find(snapshot,
                item -> item.kind() == PendingOperationsSnapshot.Kind.LIFECYCLE_CLEANUP);
        assertTrue(cleanup.targetId().startsWith("entry-"));
        assertEquals(PendingOperationsSnapshot.WaitType.LIFECYCLE_ENTRY,
                cleanup.waitsFor());
        assertEquals("async blocked disposer", cleanup.detail());

        gate.complete(null);
        assertEquals(ComponentState.DISPOSED, disposed.get(10, TimeUnit.SECONDS));
        awaitNoOperations(ignored -> true);
    }

    @Test
    void blockedDynamicCallReportsConsumerAndProviderLeases() throws Exception {
        AtomicReference<DynamicCapability<String>> dynamic = new AtomicReference<>();
        MountHandle consumer = TestKit.mount(runtime, runtime.root(), "dynamic-consumer",
                (context, config) -> dynamic.set(context.subscribe(X)),
                CapabilityRequirement.dynamicOptional(X));
        RegistrationHandle provider = TestKit.provide(runtime, runtime.root(), X, "value");
        assertEquals(ComponentState.ACTIVE, consumer.whenSettled()
                .toCompletableFuture().get(10, TimeUnit.SECONDS));

        CountDownLatch callbackEntered = new CountDownLatch(1);
        CompletableFuture<String> callbackStage = new CompletableFuture<>();
        CompletableFuture<String> call = CompletableFuture.supplyAsync(() ->
                dynamic.get().callAsync(value -> {
                    callbackEntered.countDown();
                    return callbackStage;
                }).toCompletableFuture().join());
        assertTrue(callbackEntered.await(10, TimeUnit.SECONDS));
        CompletableFuture<ComponentState> consumerDisposed =
                consumer.disposeAsync().toCompletableFuture();
        try {
            TransactionReceipt<Void> revoke = runtime.advanced().transact(transaction -> {
                transaction.revoke(provider);
                return null;
            });
            assertFalse(revoke.settlement().whenSettled().toCompletableFuture().isDone());

            PendingOperationAwait result = awaitOperation(item ->
                    item.kind() == PendingOperationsSnapshot.Kind.CONSUMER_LEASE
                            && item.targetId().equals(consumer.handleId()));
            PendingOperationsSnapshot snapshot = assertTimeoutPreemptively(
                    Duration.ofMillis(200), runtime.advanced()::pendingOperations);
            PendingOperationsSnapshot.Operation consumerLease =
                    find(snapshot, item -> item.kind() == PendingOperationsSnapshot.Kind.CONSUMER_LEASE
                            && item.targetId().equals(consumer.handleId()));
            assertEquals(PendingOperationsSnapshot.WaitType.LEASE_RELEASE,
                    consumerLease.waitsFor());
            assertTrue(consumerLease.detail().contains("dynamic calls=1"));
            assertTrue(result.operation().detail().contains("dynamic calls=1"));
            PendingOperationsSnapshot.Operation providerLease = find(result.snapshot(),
                    item -> item.kind() == PendingOperationsSnapshot.Kind.PROVIDER_LEASE
                            && item.targetId().equals(provider.registrationId()));
            assertTrue(providerLease.detail().contains("provider leases=1"));

            callbackStage.complete("done");
            assertEquals("done", call.get(10, TimeUnit.SECONDS));
            assertEquals(ComponentState.DISPOSED,
                    consumerDisposed.get(10, TimeUnit.SECONDS));
            revoke.settlement().whenSettled().toCompletableFuture().get(10, TimeUnit.SECONDS);
            awaitNoOperations(item -> item.kind() == PendingOperationsSnapshot.Kind.PROVIDER_LEASE
                    || item.kind() == PendingOperationsSnapshot.Kind.CONSUMER_LEASE);
        } finally {
            callbackStage.complete("done");
        }
    }

    @Test
    void runtimeCloseReportsDrainContextAndLifecycleBoundaries() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CompletableFuture<Void> gate = new CompletableFuture<>();
        MountHandle handle = TestKit.mount(runtime, runtime.root(), "close-gate",
                (context, config) -> context.lifecycle().onCloseAsync(
                        "runtime close disposer", () -> {
                            entered.countDown();
                            return gate;
                        }));
        assertEquals(ComponentState.ACTIVE, handle.whenSettled()
                .toCompletableFuture().get(10, TimeUnit.SECONDS));

        CompletableFuture<Void> closed = runtime.closeAsync().toCompletableFuture();
        assertTrue(entered.await(10, TimeUnit.SECONDS));
        PendingOperationsSnapshot snapshot = assertTimeoutPreemptively(
                Duration.ofMillis(200), runtime.advanced()::pendingOperations);
        assertTrue(snapshot.closeRequested());
        require(snapshot, item -> item.kind() == PendingOperationsSnapshot.Kind.RUNTIME_CLOSE
                && item.waitsFor() == PendingOperationsSnapshot.WaitType.RUNTIME_DRAIN);
        require(snapshot, item -> item.kind() == PendingOperationsSnapshot.Kind.CONTEXT_DISPOSAL
                && item.targetId().equals(runtime.root().contextId())
                && item.waitsFor() == PendingOperationsSnapshot.WaitType.CONTEXT);
        require(snapshot, item -> item.kind() == PendingOperationsSnapshot.Kind.LIFECYCLE_CLEANUP
                && item.detail().equals("async runtime close disposer"));

        gate.complete(null);
        closed.get(10, TimeUnit.SECONDS);
        PendingOperationsSnapshot settled = runtime.advanced().pendingOperations();
        assertTrue(settled.closeRequested());
        assertEquals(List.of(), settled.operations());
    }

    @Test
    void failedCloseDoesNotReportAnActiveRuntimeDrainUntilRetry() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        AtomicReference<Boolean> failFirst = new AtomicReference<>(true);
        MountHandle handle = TestKit.mount(runtime, runtime.root(), "close-retry",
                (context, config) -> context.lifecycle().onClose("retry cleanup", () -> {
                    entered.countDown();
                    if (Boolean.TRUE.equals(failFirst.getAndSet(false))) {
                        throw new IllegalStateException("first failure");
                    }
                }));
        assertEquals(ComponentState.ACTIVE, handle.whenSettled()
                .toCompletableFuture().get(10, TimeUnit.SECONDS));

        CompletableFuture<Void> failed = runtime.closeAsync().toCompletableFuture();
        assertTrue(entered.await(10, TimeUnit.SECONDS));
        assertThrows(java.util.concurrent.ExecutionException.class,
                () -> failed.get(10, TimeUnit.SECONDS));
        PendingOperationsSnapshot failedSnapshot = runtime.advanced().pendingOperations();
        assertTrue(failedSnapshot.closeRequested());
        assertTrue(failedSnapshot.operations().stream().noneMatch(item ->
                item.kind() == PendingOperationsSnapshot.Kind.RUNTIME_CLOSE));

        runtime.closeAsync().toCompletableFuture().get(10, TimeUnit.SECONDS);
        PendingOperationsSnapshot retried = runtime.advanced().pendingOperations();
        assertTrue(retried.closeRequested());
        assertEquals(List.of(), retried.operations());
    }

    @Test
    void snapshotContainsOnlyStableDtoValues() {
        TestKit.provide(runtime, runtime.root(), X, "secret");
        PendingOperationsSnapshot snapshot = runtime.advanced().pendingOperations();
        assertStableDto(snapshot);
        assertFalse(snapshot.render().contains("secret"));
    }

    private PendingOperationAwait awaitOperation(
            Predicate<PendingOperationsSnapshot.Operation> filter)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (true) {
            PendingOperationsSnapshot snapshot = runtime.advanced().pendingOperations();
            PendingOperationsSnapshot.Operation operation =
                    snapshot.operations().stream().filter(filter).findFirst().orElse(null);
            if (operation != null) {
                return new PendingOperationAwait(snapshot, operation);
            }
            if (System.nanoTime() - deadline >= 0) {
                fail("pending operation did not appear: " + snapshot.render());
            }
            Thread.yield();
        }
    }

    private void awaitNoOperations(Predicate<PendingOperationsSnapshot.Operation> filter)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (runtime.advanced().pendingOperations().operations().stream().anyMatch(filter)) {
            if (System.nanoTime() - deadline >= 0) {
                fail("pending operations did not clear: "
                        + runtime.advanced().pendingOperations().render());
            }
            Thread.yield();
        }
    }

    private static PendingOperationsSnapshot.Operation find(
            PendingOperationsSnapshot snapshot,
            Predicate<PendingOperationsSnapshot.Operation> filter) {
        return snapshot.operations().stream().filter(filter).findFirst()
                .orElseThrow(() -> new AssertionError(snapshot.render()));
    }

    private static void require(
            PendingOperationsSnapshot snapshot,
            Predicate<PendingOperationsSnapshot.Operation> filter) {
        find(snapshot, filter);
    }

    private static void assertStableDto(Object value) {
        if (value == null || value instanceof String || value instanceof Number
                || value instanceof Boolean || value instanceof Enum<?>
                || value instanceof Duration) {
            return;
        }
        Class<?> type = value.getClass();
        if (!type.getName().startsWith("io.knotra.PendingOperationsSnapshot")
                && !(value instanceof List<?>)) {
            fail("pending snapshot contains non-DTO value: " + type.getName());
        }
        if (value instanceof List<?> list) {
            list.forEach(PendingOperationsRuntimeTest::assertStableDto);
            return;
        }
        for (Class<?> cursor = type; cursor != null && cursor != Object.class;
                cursor = cursor.getSuperclass()) {
            for (Field field : cursor.getDeclaredFields()) {
                if (field.isSynthetic() || Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                field.setAccessible(true);
                try {
                    assertStableDto(field.get(value));
                } catch (IllegalAccessException error) {
                    throw new AssertionError(error);
                }
            }
        }
    }

    private record PendingOperationAwait(
            PendingOperationsSnapshot snapshot,
            PendingOperationsSnapshot.Operation operation) {
    }
}
