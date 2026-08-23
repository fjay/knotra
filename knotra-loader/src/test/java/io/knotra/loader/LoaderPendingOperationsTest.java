package io.knotra.loader;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import io.knotra.ComponentFactory;
import io.knotra.ConfigDecoder;
import io.knotra.ContextState;
import io.knotra.KnotraRuntime;
import io.knotra.MountHandle;
import io.knotra.MountOptions;
import io.knotra.NoConfig;
import io.knotra.PendingOperationsSnapshot;
import io.knotra.PendingOperationsSnapshot.Kind;
import io.knotra.PendingOperationsSnapshot.WaitType;
import io.knotra.TransactionReceipt;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pending Operations v1 的行为覆盖：mount 策略执行（USER_CALLBACK）与 mount settlement
 * （COMPONENT）两个等待点、各阶段可见、runtime-owned 等待保留原路径、释放后清空、失败按身份
 * 清理、读取无副作用且不被协调器阻塞、ticker 负值钳制、追踪值不引用非稳定对象。
 */
final class LoaderPendingOperationsTest {

    private final KnotraRuntime runtime = KnotraRuntime.create();

    @AfterEach
    void tearDown() throws Exception {
        runtime.close();
    }

    @Test
    @Timeout(15)
    void queuedCloseBehindBlockedReconcileIsVisible() throws Exception {
        FactoryRef ref = FactoryRef.of("blocked");
        CompletableFuture<Void> gate = new CompletableFuture<>();
        ComponentFactory<NoConfig> factory = LoaderTestKit.factory(
                "blocked", (context, config) -> gate.join());
        KnotraLoader loader = KnotraLoader.over(runtime, runtime.root(),
                LoaderTestKit.resolver(ref, factory));
        try {
            var reconcile = loader.reconcileAsync(ComponentTree.of(
                    LoaderTestKit.entry("alpha", ref, NoConfig.INSTANCE)));
            awaitOperation(loader, "type=reconcile phase=mount-settlement");

            var close = loader.closeAsync();
            PendingOperationsSnapshot snapshot = loader.pendingOperations();

            var queuedClose = requireOperation(snapshot, "type=close phase=queued");
            assertEquals(Kind.LOADER_OPERATION, queuedClose.kind());
            assertEquals(WaitType.COORDINATOR, queuedClose.waitsFor());
            assertEquals(loader.loaderId(), queuedClose.targetId());
            assertTrue(snapshot.closeRequested());

            var blockedReconcile = requireOperation(snapshot, "type=reconcile phase=mount-settlement");
            assertEquals(WaitType.COMPONENT, blockedReconcile.waitsFor());
            assertEquals(coreHandleId("alpha"), blockedReconcile.targetId());
            assertTrue(blockedReconcile.detail().contains("path=alpha"), blockedReconcile::detail);

            gate.complete(null);
            LoaderTestKit.assertAccepted(reconcile.toCompletableFuture().get(10, TimeUnit.SECONDS));
            close.toCompletableFuture().get(10, TimeUnit.SECONDS);

            PendingOperationsSnapshot drained = loader.pendingOperations();
            assertTrue(drained.operations().isEmpty(), drained::render);
            assertTrue(drained.closeRequested());
        } finally {
            gate.complete(null);
            loader.close();
        }
    }

    @Test
    @Timeout(15)
    void runningPreparePhaseIsVisible() throws Exception {
        CompletableFuture<Void> gate = new CompletableFuture<>();
        KnotraLoader loader = KnotraLoader.over(runtime, runtime.root(), wanted -> {
            gate.join();
            return fixedResolver().resolve(wanted);
        });
        try {
            var reconcile = loader.reconcileAsync(ComponentTree.of(
                    LoaderTestKit.entry("alpha", FactoryRef.of("prepare"), NoConfig.INSTANCE)));
            PendingOperationsSnapshot snapshot = awaitOperation(loader, "phase=prepare");

            var operation = requireOperation(snapshot, "type=reconcile phase=prepare");
            assertEquals(WaitType.COORDINATOR, operation.waitsFor());
            assertEquals(loader.loaderId(), operation.targetId());

            gate.complete(null);
            LoaderTestKit.assertAccepted(reconcile.toCompletableFuture().get(10, TimeUnit.SECONDS));
            assertTrue(loader.pendingOperations().operations().isEmpty());
        } finally {
            gate.complete(null);
            loader.close();
        }
    }

    @Test
    @Timeout(15)
    void mountExecutionPhaseReportsBlockedStrategyAsUserCallback() throws Exception {
        FactoryRef ref = FactoryRef.of("strategy-gated");
        CompletableFuture<Void> strategyGate = new CompletableFuture<>();
        ResolvedFactory direct = ResolvedFactory.of(
                FactoryIdentity.of("strategy-gated", "", "direct"),
                LoaderTestKit.factory("strategy-gated", (context, config) -> {}));
        ResolvedFactory gatedStrategy = new ResolvedFactory(
                direct.identity(),
                ResolvedFactory.FactoryKind.PLAIN,
                null,
                (context, config) -> {
                    strategyGate.join();
                    return direct.mountStrategy().mountAsync(context, config);
                },
                ReconfigureStrategy.unsupportedPlain());
        KnotraLoader loader = KnotraLoader.over(runtime, runtime.root(),
                wanted -> Optional.of(gatedStrategy));
        try {
            var reconcile = loader.reconcileAsync(ComponentTree.of(
                    LoaderTestKit.entry("alpha", ref, NoConfig.INSTANCE)));

            PendingOperationsSnapshot snapshot = awaitOperation(
                    loader, "type=reconcile phase=mount-execution");
            var operation = requireOperation(snapshot, "phase=mount-execution");
            assertEquals(Kind.LOADER_OPERATION, operation.kind());
            assertEquals(WaitType.USER_CALLBACK, operation.waitsFor());
            assertEquals("alpha", operation.targetId());
            assertTrue(operation.detail().contains("path=alpha"), operation::detail);

            strategyGate.complete(null);
            LoaderTestKit.assertAccepted(reconcile.toCompletableFuture().get(10, TimeUnit.SECONDS));
            assertTrue(loader.pendingOperations().operations().isEmpty(),
                    () -> loader.pendingOperations().render());
        } finally {
            strategyGate.complete(null);
            loader.close();
        }
    }

    @Test
    @Timeout(15)
    // factory 阻塞使 receipt settlement 无法收敛：loader 阻塞在 awaitSettled，
    // 与阻塞在策略体（mount-execution/USER_CALLBACK）是两个不同等待点。
    void mountSettlementPhaseReportsPathAndComponentWait() throws Exception {
        FactoryRef ref = FactoryRef.of("gated");
        CompletableFuture<Void> gate = new CompletableFuture<>();
        ComponentFactory<NoConfig> factory = LoaderTestKit.factory(
                "gated", (context, config) -> gate.join());
        KnotraLoader loader = KnotraLoader.over(runtime, runtime.root(),
                LoaderTestKit.resolver(ref, factory));
        try {
            var reconcile = loader.reconcileAsync(ComponentTree.of(
                    LoaderTestKit.entry("alpha", ref, NoConfig.INSTANCE)));
            PendingOperationsSnapshot snapshot = awaitOperation(
                    loader, "type=reconcile phase=mount-settlement");

            var operation = requireOperation(snapshot, "phase=mount-settlement");
            assertEquals(Kind.LOADER_OPERATION, operation.kind());
            assertEquals(WaitType.COMPONENT, operation.waitsFor());
            assertEquals(coreHandleId("alpha"), operation.targetId());
            assertTrue(operation.detail().contains("path=alpha"), operation::detail);
            assertTrue(operation.detail().contains("id=alpha"), operation::detail);
            assertFalse(operation.age().isNegative());

            gate.complete(null);
            LoaderTestKit.assertAccepted(reconcile.toCompletableFuture().get(10, TimeUnit.SECONDS));
            assertTrue(loader.pendingOperations().operations().isEmpty(),
                    () -> loader.pendingOperations().render());
        } finally {
            gate.complete(null);
            loader.close();
        }
    }

    @Test
    @Timeout(15)
    void reconfigurePhaseCorrelatesWithCoreHandleId() throws Exception {
        FactoryRef ref = FactoryRef.of("reconfigure-gated");
        CompletableFuture<Void> gate = new CompletableFuture<>();
        ComponentFactory<String> factory = LoaderTestKit.factory(
                "reconfigure-gated", (context, config) -> {});
        ResolvedFactory direct = ResolvedFactory.of(
                FactoryIdentity.of("reconfigure-gated", "", "direct"),
                factory,
                ConfigDecoder.typed(String.class));
        ResolvedFactory gatedReconfigure = new ResolvedFactory(
                direct.identity(),
                ResolvedFactory.FactoryKind.CONFIGURED,
                direct.configDecoder(),
                direct.mountStrategy(),
                (handle, typedConfig) -> {
                    gate.join();
                    return direct.reconfigureStrategy().reconfigureAsync(handle, typedConfig);
                });
        KnotraLoader loader = KnotraLoader.over(runtime, runtime.root(),
                wanted -> Optional.of(gatedReconfigure));
        try {
            LoaderTestKit.assertAccepted(loader.reconcile(ComponentTree.of(
                    LoaderTestKit.entry("alpha", ref, "first"))));
            String handleId = coreHandleId("alpha");

            var update = loader.reconcileAsync(ComponentTree.of(
                    LoaderTestKit.entry("alpha", ref, "second")));
            PendingOperationsSnapshot snapshot = awaitOperation(
                    loader, "type=reconcile phase=reconfigure");

            var operation = requireOperation(snapshot, "phase=reconfigure");
            assertEquals(Kind.LOADER_OPERATION, operation.kind());
            assertEquals(WaitType.COMPONENT, operation.waitsFor());
            assertEquals(handleId, operation.targetId());
            assertTrue(operation.detail().contains("path=alpha"), operation::detail);
            assertTrue(operation.detail().contains("id=" + handleId), operation::detail);

            gate.complete(null);
            LoaderTestKit.assertAccepted(update.toCompletableFuture().get(10, TimeUnit.SECONDS));
            assertEquals(2, loader.snapshot().entry("alpha").orElseThrow().configRevision());
            assertTrue(loader.pendingOperations().operations().isEmpty(),
                    () -> loader.pendingOperations().render());
        } finally {
            gate.complete(null);
            loader.close();
        }
    }

    @Test
    @Timeout(15)
    void disposeHandlePhaseReportsHandleIdentity() throws Exception {
        FactoryRef ref = FactoryRef.of("implementation");
        CompletableFuture<Void> cleanupGate = new CompletableFuture<>();
        AtomicReference<ComponentFactoryResolver> resolverReference = new AtomicReference<>();
        KnotraLoader loader = KnotraLoader.over(runtime, runtime.root(),
                wanted -> resolverReference.get().resolve(wanted));
        try {
            resolverReference.set(LoaderTestKit.resolver(ref,
                    LoaderTestKit.factory("old", (context, config) ->
                            context.lifecycle().onClose("cleanup", cleanupGate::join))));
            ComponentTree tree = ComponentTree.of(
                    LoaderTestKit.entry("alpha", ref, NoConfig.INSTANCE));
            LoaderTestKit.assertAccepted(loader.reconcile(tree));
            String oldHandle = loader.snapshot().entry("alpha").orElseThrow().handleId();

            resolverReference.set(LoaderTestKit.resolver(ref,
                    LoaderTestKit.factory("new", (context, config) -> {})));
            var replacement = loader.reconcileAsync(tree);
            PendingOperationsSnapshot snapshot = awaitOperation(
                    loader, "type=reconcile phase=dispose-handle");

            var operation = requireOperation(snapshot, "phase=dispose-handle");
            assertEquals(WaitType.COMPONENT, operation.waitsFor());
            assertEquals(oldHandle, operation.targetId());
            assertTrue(operation.detail().contains("id=" + oldHandle), operation::detail);

            cleanupGate.complete(null);
            LoaderTestKit.assertAccepted(replacement.toCompletableFuture().get(10, TimeUnit.SECONDS));
            assertTrue(loader.pendingOperations().operations().isEmpty());
        } finally {
            cleanupGate.complete(null);
            loader.close();
        }
    }

    @Test
    @Timeout(15)
    void disposeContextPhaseReportsContextIdentity() throws Exception {
        FactoryRef ref = FactoryRef.of("cleanup");
        CompletableFuture<Void> cleanupGate = new CompletableFuture<>();
        ComponentFactory<NoConfig> factory = LoaderTestKit.factory(
                "cleanup", (context, config) ->
                        context.lifecycle().onClose("cleanup", cleanupGate::join));
        KnotraLoader loader = KnotraLoader.over(runtime, runtime.root(),
                LoaderTestKit.resolver(ref, factory));
        try {
            LoaderTestKit.assertAccepted(loader.reconcile(ComponentTree.of(
                    LoaderTestKit.entry("alpha", ref, NoConfig.INSTANCE))));
            String contextId = loader.snapshot().entry("alpha").orElseThrow().contextId();

            var close = loader.closeAsync();
            PendingOperationsSnapshot snapshot = awaitOperation(
                    loader, "type=close phase=dispose-context");

            var operation = requireOperation(snapshot, "phase=dispose-context");
            assertEquals(WaitType.CONTEXT, operation.waitsFor());
            assertEquals(contextId, operation.targetId());
            assertTrue(operation.detail().contains("id=" + contextId), operation::detail);
            assertTrue(snapshot.closeRequested());

            cleanupGate.complete(null);
            close.toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertTrue(loader.pendingOperations().operations().isEmpty());
            assertTrue(loader.pendingOperations().closeRequested());
        } finally {
            cleanupGate.complete(null);
            loader.close();
        }
    }

    @Test
    @Timeout(15)
    void runtimeOwnedHandleWaitReportsOriginalPathAndHandleId() throws Exception {
        CompletableFuture<Void> cleanupGate = new CompletableFuture<>();
        ComponentFactory<NoConfig> factory = LoaderTestKit.factory(
                "runtime-owned", (context, config) ->
                        context.lifecycle().onClose("cleanup", cleanupGate::join));
        TransactionReceipt<MountHandle> receipt = runtime.advanced().transact(transaction ->
                transaction.mount(runtime.root(), "alpha", factory, MountOptions.DEFAULT));
        MountHandle handle = receipt.value();
        receipt.awaitSettled(Duration.ofSeconds(10));
        String handleId = handle.handleId();

        var runtimeClose = runtime.closeAsync();
        awaitRootDisposing();

        LoaderOperationTracker tracker = new LoaderOperationTracker(System::nanoTime);
        LoaderOperationTracker.Operation operation = tracker.begin("close", "loader-test");
        LoaderDisposer disposer = new LoaderDisposer(
                runtime, runtime.root(), new LoaderStateStore(), () -> LoaderTimeouts.DEFAULTS);
        List<LoaderDiagnostic> diagnostics = new ArrayList<>();
        CompletableFuture<Boolean> disposed = CompletableFuture.supplyAsync(() ->
                disposer.disposeHandle("alpha", handle, diagnostics, operation));
        try {
            PendingOperationsSnapshot snapshot = awaitOperation(
                    tracker, "type=close phase=runtime-owned");
            var pending = requireOperation(snapshot, "phase=runtime-owned");
            assertEquals(Kind.LOADER_OPERATION, pending.kind());
            assertEquals(WaitType.RUNTIME_DRAIN, pending.waitsFor());
            assertEquals(handleId, pending.targetId());
            assertEquals(1, countOccurrences(pending.detail(), "path=alpha"), pending::detail);
            assertEquals(1, countOccurrences(pending.detail(), "id=" + handleId), pending::detail);

            cleanupGate.complete(null);
            assertTrue(disposed.get(10, TimeUnit.SECONDS));
            runtimeClose.toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertTrue(diagnostics.isEmpty(), diagnostics::toString);
        } finally {
            cleanupGate.complete(null);
            disposed.get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    @Timeout(15)
    void failedCloseIsRemovedByIdentityAndKeepsDiagnostics() throws Exception {
        FactoryRef ref = FactoryRef.of("flaky");
        AtomicBoolean failOnce = new AtomicBoolean(true);
        ComponentFactory<NoConfig> factory = LoaderTestKit.factory(
                "flaky", (context, config) ->
                        context.lifecycle().onClose("cleanup", () -> {
                            if (failOnce.getAndSet(false)) {
                                throw new IllegalStateException("cleanup failed");
                            }
                        }));
        KnotraLoader loader = KnotraLoader.over(runtime, runtime.root(),
                LoaderTestKit.resolver(ref, factory));
        try {
            LoaderTestKit.assertAccepted(loader.reconcile(ComponentTree.of(
                    LoaderTestKit.entry("alpha", ref, NoConfig.INSTANCE))));
            assertFalse(loader.pendingOperations().closeRequested());

            assertInstanceOf(IllegalStateException.class, assertThrows(
                            java.util.concurrent.CompletionException.class, loader::close)
                    .getCause());

            PendingOperationsSnapshot failed = loader.pendingOperations();
            assertTrue(failed.operations().isEmpty(), failed::render);
            assertTrue(failed.closeRequested());
            assertTrue(loader.snapshot().diagnostics().stream()
                            .anyMatch(diagnostic -> diagnostic.code() == LoaderDiagnosticCode.TEARDOWN_FAILED),
                    () -> loader.snapshot().diagnostics().toString());

            loader.close();
            assertTrue(loader.pendingOperations().operations().isEmpty());
            assertTrue(loader.pendingOperations().closeRequested());
        } finally {
            loader.close();
        }
    }

    @Test
    @Timeout(15)
    void hundredConcurrentReadersDoNotBlockOnCoordinator() throws Exception {
        FactoryRef ref = FactoryRef.of("readers");
        CompletableFuture<Void> gate = new CompletableFuture<>();
        ComponentFactory<NoConfig> factory = LoaderTestKit.factory(
                "readers", (context, config) -> gate.join());
        KnotraLoader loader = KnotraLoader.over(runtime, runtime.root(),
                LoaderTestKit.resolver(ref, factory));
        ExecutorService readers = Executors.newFixedThreadPool(8);
        try {
            var reconcile = loader.reconcileAsync(ComponentTree.of(
                    LoaderTestKit.entry("alpha", ref, NoConfig.INSTANCE)));
            awaitOperation(loader, "phase=mount-settlement");

            List<CompletableFuture<PendingOperationsSnapshot>> reads = new ArrayList<>();
            for (int index = 0; index < 100; index++) {
                reads.add(CompletableFuture.supplyAsync(loader::pendingOperations, readers));
            }
            CompletableFuture.allOf(reads.toArray(CompletableFuture[]::new))
                    .get(5, TimeUnit.SECONDS);
            for (CompletableFuture<PendingOperationsSnapshot> read : reads) {
                assertEquals(1, read.getNow(null).operations().size());
            }

            gate.complete(null);
            LoaderTestKit.assertAccepted(reconcile.toCompletableFuture().get(10, TimeUnit.SECONDS));
        } finally {
            gate.complete(null);
            readers.shutdown();
            assertTrue(readers.awaitTermination(10, TimeUnit.SECONDS));
            loader.close();
        }
    }

    @Test
    @Timeout(15)
    void negativeOrReversedTickerValuesStillRenderNonNegativeAges() throws Exception {
        AtomicLong clock = new AtomicLong(-100_000);
        KnotraLoader loader = KnotraLoader.over(runtime, runtime.root(),
                fixedResolver(), clock::incrementAndGet);
        try {
            var reconcile = loader.reconcileAsync(ComponentTree.of(
                    LoaderTestKit.entry("alpha", FactoryRef.of("prepare"), NoConfig.INSTANCE)));
            PendingOperationsSnapshot snapshot = loader.pendingOperations();
            for (var operation : snapshot.operations()) {
                assertFalse(operation.age().isNegative(), operation::detail);
            }
            reconcile.toCompletableFuture().get(10, TimeUnit.SECONDS);
        } finally {
            loader.close();
        }

        AtomicLong reversedClock = new AtomicLong(-100_000);
        LoaderOperationTracker tracker =
                new LoaderOperationTracker(reversedClock::incrementAndGet);
        LoaderOperationTracker.Operation operation = tracker.begin("reconcile", "loader-1");
        // 直接驱动负值与回退时钟：age 只允许钳制为 0，绝不出现负 Duration。
        operation.phase(LoaderOperationTracker.Phase.MOUNT_SETTLEMENT,
                "alpha", "alpha", "", Duration.ofSeconds(1));
        assertFalse(tracker.snapshot(-200_000, false).operations().getFirst()
                .age().isNegative());
        operation.complete();
        assertTrue(tracker.snapshot(-200_000, false).operations().isEmpty());
    }

    @Test
    @Timeout(15)
    void trackerStoresOnlyStableValuesAndDtoDoesNotPinReferences() throws Exception {
        AtomicLong clock = new AtomicLong();
        LoaderOperationTracker tracker = new LoaderOperationTracker(clock::incrementAndGet);
        LoaderOperationTracker.Operation operation = tracker.begin("reconcile", "loader-1");
        operation.phase(LoaderOperationTracker.Phase.RUNTIME_OWNED,
                "h-1", "alpha", "h-1", Duration.ofSeconds(1));
        clock.addAndGet(500_000_000L);

        for (LoaderOperationTracker.Recorded recorded : tracker.recordedForTesting()) {
            for (Field field : LoaderOperationTracker.Recorded.class.getDeclaredFields()) {
                if (field.getType().isPrimitive()) {
                    continue;
                }
                field.setAccessible(true);
                Object value = field.get(recorded);
                assertTrue(value instanceof String || value instanceof Enum,
                        () -> field.getName() + " stored " + value.getClass());
            }
        }

        PendingOperationsSnapshot snapshot = tracker.snapshot(clock.get(), false);
        var rendered = snapshot.operations().getFirst();
        assertEquals(Kind.LOADER_OPERATION, rendered.kind());
        assertEquals(WaitType.RUNTIME_DRAIN, rendered.waitsFor());
        assertTrue(rendered.detail().contains("deadline-remaining=500ms"),
                rendered::detail);
        for (Field field : PendingOperationsSnapshot.Operation.class.getDeclaredFields()) {
            field.setAccessible(true);
            Object value = field.get(rendered);
            assertTrue(value instanceof String || value instanceof Enum || value instanceof Duration,
                    () -> field.getName() + " exposed " + value.getClass());
        }
        operation.complete();
    }

    private static ComponentFactoryResolver fixedResolver() {
        FactoryRef ref = FactoryRef.of("prepare");
        return LoaderTestKit.resolver(ref,
                LoaderTestKit.factory("prepare", (context, config) -> {}));
    }

    /** Loader 视角外的对照值：直接从 core runtime snapshot 读取挂载句柄 ID。 */
    private String coreHandleId(String mountId) {
        return runtime.advanced().snapshot().mounts().stream()
                .filter(mount -> mount.mountId().equals(mountId))
                .findFirst()
                .orElseThrow()
                .handleId();
    }

    private static PendingOperationsSnapshot awaitOperation(
            KnotraLoader loader,
            String detailFragment) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        PendingOperationsSnapshot last = loader.pendingOperations();
        while (System.nanoTime() < deadline) {
            last = loader.pendingOperations();
            if (last.operations().stream()
                    .anyMatch(operation -> operation.detail().contains(detailFragment))) {
                return last;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("operation not observed: " + detailFragment
                + "\n" + last.render());
    }

    private static PendingOperationsSnapshot awaitOperation(
            LoaderOperationTracker tracker,
            String detailFragment) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        PendingOperationsSnapshot last = tracker.snapshot(System.nanoTime(), false);
        while (System.nanoTime() < deadline) {
            last = tracker.snapshot(System.nanoTime(), false);
            if (last.operations().stream()
                    .anyMatch(operation -> operation.detail().contains(detailFragment))) {
                return last;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("operation not observed: " + detailFragment
                + "\n" + last.render());
    }

    private void awaitRootDisposing() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        ContextState last = runtime.root().state();
        while (last != ContextState.DISPOSING && last != ContextState.DISPOSED) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("root did not enter disposal: " + last);
            }
            Thread.sleep(10);
            last = runtime.root().state();
        }
    }

    private static int countOccurrences(String detail, String fragment) {
        int count = 0;
        int index = 0;
        while ((index = detail.indexOf(fragment, index)) >= 0) {
            count++;
            index += fragment.length();
        }
        return count;
    }

    private static PendingOperationsSnapshot.Operation requireOperation(
            PendingOperationsSnapshot snapshot,
            String detailFragment) {
        return snapshot.operations().stream()
                .filter(operation -> operation.detail().contains(detailFragment))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "missing " + detailFragment + "\n" + snapshot.render()));
    }
}
