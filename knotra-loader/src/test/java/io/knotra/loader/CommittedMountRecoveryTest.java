package io.knotra.loader;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import io.knotra.AdvancedRuntime;
import io.knotra.CapabilityKey;
import io.knotra.ComponentFactory;
import io.knotra.ComponentGoal;
import io.knotra.ComponentState;
import io.knotra.ContextHandle;
import io.knotra.ContextInfo;
import io.knotra.ContextState;
import io.knotra.ContextView;
import io.knotra.KnotraRuntime;
import io.knotra.MountHandle;
import io.knotra.MountOptions;
import io.knotra.NoConfig;
import io.knotra.PublicationChange;
import io.knotra.RuntimeSnapshot;
import io.knotra.RuntimeTransaction;
import io.knotra.Settlement;
import io.knotra.SettlementReport;
import io.knotra.TransactionReceipt;

import static org.junit.jupiter.api.Assertions.*;

/**
 * commit 后 settlement 超时/异常路径的确定性覆盖：已提交句柄要么被可靠释放，
 * 要么进入 Loader 记账，绝不成为 runtime 中无人跟踪的孤儿。
 */
final class CommittedMountRecoveryTest {

    private final KnotraRuntime runtime = KnotraRuntime.create();

    @AfterEach
    void tearDown() throws Exception {
        runtime.close();
    }

    @Test
    @Timeout(10)
    void settlementTimeoutDisposesCommittedHandle() throws Exception {
        FakeHandle handle = new FakeHandle();
        handle.dispose.complete(ComponentState.DISPOSED);
        AllocatedMountContext context = new AllocatedMountContext(
                runtimeReturning(new TransactionReceipt<>(
                        handle, new FakeSettlement(new CompletableFuture<>()))),
                new FakeContext(),
                "alpha",
                Duration.ofMillis(50),
                Duration.ofSeconds(2));

        ExecutionException failure = assertThrows(ExecutionException.class, () ->
                context.mountAsync(
                                LoaderTestKit.factory("committed", (activation, config) -> {}),
                                MountOptions.DEFAULT)
                        .toCompletableFuture().get(5, TimeUnit.SECONDS));

        assertInstanceOf(ControlledMountException.class, failure.getCause());
        assertTrue(failure.getCause().getMessage().contains("disposed during recovery"),
                () -> String.valueOf(failure.getCause()));
        assertNull(context.committedHandle());
        assertEquals(1, handle.disposeRequests.get());
    }

    @Test
    @Timeout(10)
    void settlementTimeoutRetainsHandleWhenDisposalNeverSettles() throws Exception {
        FakeHandle handle = new FakeHandle();
        AllocatedMountContext context = new AllocatedMountContext(
                runtimeReturning(new TransactionReceipt<>(
                        handle, new FakeSettlement(new CompletableFuture<>()))),
                new FakeContext(),
                "alpha",
                Duration.ofMillis(50),
                Duration.ofMillis(50));

        ExecutionException failure = assertThrows(ExecutionException.class, () ->
                context.mountAsync(
                                LoaderTestKit.factory("committed", (activation, config) -> {}),
                                MountOptions.DEFAULT)
                        .toCompletableFuture().get(5, TimeUnit.SECONDS));

        assertInstanceOf(ControlledMountException.class, failure.getCause());
        assertTrue(failure.getCause().getMessage().contains("retained for loader bookkeeping"),
                () -> String.valueOf(failure.getCause()));
        assertSame(handle, context.committedHandle());
        assertEquals(1, handle.disposeRequests.get());
    }

    @Test
    @Timeout(10)
    void exceptionalSettlementDisposesCommittedHandle() throws Exception {
        FakeHandle handle = new FakeHandle();
        handle.dispose.complete(ComponentState.DISPOSED);
        CompletableFuture<SettlementReport> settled = new CompletableFuture<>();
        settled.completeExceptionally(new IllegalStateException("core settlement exploded"));
        AllocatedMountContext context = new AllocatedMountContext(
                runtimeReturning(new TransactionReceipt<>(handle, new FakeSettlement(settled))),
                new FakeContext(),
                "alpha",
                Duration.ofSeconds(2),
                Duration.ofSeconds(2));

        ExecutionException failure = assertThrows(ExecutionException.class, () ->
                context.mountAsync(
                                LoaderTestKit.factory("committed", (activation, config) -> {}),
                                MountOptions.DEFAULT)
                        .toCompletableFuture().get(5, TimeUnit.SECONDS));

        assertInstanceOf(ControlledMountException.class, failure.getCause());
        assertTrue(failure.getCause().getMessage().contains("core settlement exploded"),
                () -> String.valueOf(failure.getCause()));
        assertNull(context.committedHandle());
        assertEquals(1, handle.disposeRequests.get());
    }

    @Test
    void unsettledReplacementIsTrackedThenRecoveredByLaterReconcile() throws Exception {
        FactoryRef ref = FactoryRef.of("implementation");
        CompletableFuture<Void> gate = new CompletableFuture<>();
        AtomicInteger newStarts = new AtomicInteger();
        AtomicReference<ComponentFactoryResolver> resolverReference = new AtomicReference<>();
        KnotraLoader loader = KnotraLoader.over(runtime, runtime.root(),
                wanted -> resolverReference.get().resolve(wanted));
        loader.mountTimeoutsForTesting(Duration.ofMillis(50), Duration.ofMillis(80));
        try {
            resolverReference.set(LoaderTestKit.resolver(ref,
                    LoaderTestKit.factory("old", (context, config) -> {})));
            ComponentTree tree = ComponentTree.of(
                    LoaderTestKit.entry("alpha", ref, NoConfig.INSTANCE));
            LoaderTestKit.assertAccepted(loader.reconcile(tree));
            String oldHandle = loader.snapshot().entry("alpha").orElseThrow().handleId();

            resolverReference.set(LoaderTestKit.resolver(ref,
                    LoaderTestKit.factory("new", (context, config) -> {
                        newStarts.incrementAndGet();
                        gate.join();
                    })));
            ReconcileResult blocked = loader.reconcile(tree);
            assertFalse(blocked.converged(), () -> blocked.diagnostics().toString());
            assertTrue(blocked.diagnostics().stream().anyMatch(diagnostic ->
                            diagnostic.code() == LoaderDiagnosticCode.SETTLEMENT_UNSETTLED
                                    && diagnostic.path().equals("alpha")),
                    () -> blocked.diagnostics().toString());

            // 新句柄已提交：进入 Loader 记账并独占槽位，而不是成为孤儿。
            var tracked = loader.snapshot().entry("alpha").orElseThrow();
            assertNotEquals(oldHandle, tracked.handleId());
            assertEquals("new", tracked.componentId());
            assertNotEquals(ComponentState.DISPOSED, tracked.state());
            assertEquals(1, runtime.advanced().snapshot().mounts().stream()
                    .filter(mount -> mount.mountId().equals("alpha"))
                    .count());

            gate.complete(null);
            ReconcileResult recovered = convergeEventually(loader, tree, Duration.ofSeconds(10));
            LoaderTestKit.assertAccepted(recovered);
            assertEquals(ComponentState.ACTIVE,
                    loader.snapshot().entry("alpha").orElseThrow().state());
            assertTrue(newStarts.get() >= 1);
        } finally {
            gate.complete(null);
            loader.close();
        }
    }

    @Test
    void unsettledFirstMountStaysTrackedAndLaterReconcileRecovers() throws Exception {
        FactoryRef ref = FactoryRef.of("gated");
        CompletableFuture<Void> gate = new CompletableFuture<>();
        AtomicInteger starts = new AtomicInteger();
        ComponentFactory<NoConfig> factory = LoaderTestKit.factory("gated", (context, config) -> {
            starts.incrementAndGet();
            gate.join();
        });
        KnotraLoader loader = KnotraLoader.over(runtime, runtime.root(),
                LoaderTestKit.resolver(ref, factory));
        loader.mountTimeoutsForTesting(Duration.ofMillis(50), Duration.ofMillis(80));
        try {
            ComponentTree tree = ComponentTree.of(
                    LoaderTestKit.entry("alpha", ref, NoConfig.INSTANCE));
            ReconcileResult blocked = loader.reconcile(tree);
            assertFalse(blocked.converged(), () -> blocked.diagnostics().toString());
            assertTrue(blocked.diagnostics().stream().anyMatch(diagnostic ->
                            diagnostic.code() == LoaderDiagnosticCode.SETTLEMENT_UNSETTLED
                                    && diagnostic.path().equals("alpha")),
                    () -> blocked.diagnostics().toString());

            // 新增批次同样不回滚已提交句柄：记账保留，后续 reconcile 可恢复。
            var tracked = loader.snapshot().entry("alpha").orElseThrow();
            assertNotEquals(ComponentState.DISPOSED, tracked.state());
            assertEquals(1, runtime.advanced().snapshot().mounts().stream()
                    .filter(mount -> mount.mountId().equals("alpha"))
                    .count());

            gate.complete(null);
            ReconcileResult recovered = convergeEventually(loader, tree, Duration.ofSeconds(10));
            LoaderTestKit.assertAccepted(recovered);
            assertEquals(ComponentState.ACTIVE,
                    loader.snapshot().entry("alpha").orElseThrow().state());
            assertTrue(starts.get() >= 1);
        } finally {
            gate.complete(null);
            loader.close();
        }
    }

    @Test
    void disposedOwnedOrOldChildInSettlementReportIsNotFailure() {
        SettlementReport replacedChild = new SettlementReport(9, List.of(
                new SettlementReport.MountOutcome(
                        "h-old", "old", ComponentState.DISPOSED, List.of()),
                new SettlementReport.MountOutcome(
                        "h-new", "new", ComponentState.ACTIVE, List.of())),
                List.of());
        SettlementReport waitingChild = new SettlementReport(11, List.of(
                new SettlementReport.MountOutcome(
                        "h-owned", "child", ComponentState.WAITING, List.of()),
                new SettlementReport.MountOutcome(
                        "h-new", "new", ComponentState.ACTIVE, List.of())),
                List.of());
        SettlementReport failed = new SettlementReport(10, List.of(
                new SettlementReport.MountOutcome(
                        "h-old", "old", ComponentState.DISPOSED, List.of()),
                new SettlementReport.MountOutcome(
                        "h-new", "new", ComponentState.FAILED, List.of())),
                List.of());

        assertFalse(KnotraLoader.settlementIndicatesFailure(replacedChild));
        assertFalse(KnotraLoader.settlementIndicatesFailure(waitingChild));
        assertFalse(KnotraLoader.settlementIndicatesFailure(null));
        assertTrue(KnotraLoader.settlementIndicatesFailure(failed));
    }

    /** 用短周期重试替代 sleep：等待 runtime 异步完成释放后由 reconcile 自然收敛。 */
    private static ReconcileResult convergeEventually(
            KnotraLoader loader,
            ComponentTree tree,
            Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        ReconcileResult result = loader.reconcile(tree);
        while (!result.converged() && System.nanoTime() < deadline) {
            CompletableFuture<Void> tick = new CompletableFuture<>();
            CompletableFuture.delayedExecutor(10, TimeUnit.MILLISECONDS)
                    .execute(() -> tick.complete(null));
            tick.get(1, TimeUnit.SECONDS);
            result = loader.reconcile(tree);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static KnotraRuntime runtimeReturning(TransactionReceipt<MountHandle> receipt) {
        AdvancedRuntime advanced = new AdvancedRuntime() {
            @Override
            public RuntimeSnapshot snapshot() {
                throw new UnsupportedOperationException();
            }

            @Override
            public <R> TransactionReceipt<R> transact(
                    Function<RuntimeTransaction, R> transaction) {
                return (TransactionReceipt<R>) receipt;
            }

            @Override
            public <T> PublicationChange<T> publication(
                    ContextHandle context, CapabilityKey<T> key, T value) {
                throw new UnsupportedOperationException();
            }

            @Override
            public ContextHandle childContext(ContextHandle parent, String name) {
                throw new UnsupportedOperationException();
            }
        };
        return new KnotraRuntime() {
            @Override
            public String runtimeId() {
                return "fake";
            }

            @Override
            public ContextHandle root() {
                throw new UnsupportedOperationException();
            }

            @Override
            public AdvancedRuntime advanced() {
                return advanced;
            }

            @Override
            public CompletionStage<Void> closeAsync() {
                throw new UnsupportedOperationException();
            }
        };
    }

    private record FakeSettlement(
            CompletableFuture<SettlementReport> future) implements Settlement {

        @Override
        public long generation() {
            return 7;
        }

        @Override
        public CompletionStage<SettlementReport> whenSettled() {
            return future;
        }
    }

    private static final class FakeContext implements ContextHandle {
        @Override
        public String contextId() {
            return "ctx-allocated";
        }

        @Override
        public ContextInfo info() {
            throw new UnsupportedOperationException();
        }

        @Override
        public ContextView view() {
            throw new UnsupportedOperationException();
        }

        @Override
        public ContextState state() {
            return ContextState.ACTIVE;
        }

        @Override
        public CompletionStage<ContextState> disposeAsync() {
            throw new UnsupportedOperationException();
        }
    }

    private static final class FakeHandle implements MountHandle {
        private final CompletableFuture<ComponentState> settled = new CompletableFuture<>();
        private final CompletableFuture<ComponentState> dispose = new CompletableFuture<>();
        private volatile ComponentState state = ComponentState.STARTING;
        private volatile ComponentGoal goal = ComponentGoal.RUNNING;
        private final AtomicInteger disposeRequests = new AtomicInteger();

        @Override
        public String handleId() {
            return "h-committed";
        }

        @Override
        public String mountId() {
            return "alpha";
        }

        @Override
        public String componentId() {
            return "committed";
        }

        @Override
        public String factoryId() {
            return "f-committed";
        }

        @Override
        public String contextId() {
            return "ctx-allocated";
        }

        @Override
        public ComponentState state() {
            return state;
        }

        @Override
        public ComponentGoal goal() {
            return goal;
        }

        @Override
        public long configRevision() {
            return 0;
        }

        @Override
        public CompletionStage<ComponentState> whenSettled() {
            return settled;
        }

        @Override
        public CompletionStage<ComponentState> retryAsync() {
            return dispose;
        }

        @Override
        public CompletionStage<ComponentState> disposeAsync() {
            disposeRequests.incrementAndGet();
            return dispose;
        }
    }
}
