package io.knotra.pf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import io.knotra.AdvancedRuntime;
import io.knotra.CapabilityKey;
import io.knotra.ComponentGoal;
import io.knotra.ComponentState;
import io.knotra.ContextHandle;
import io.knotra.ContextInfo;
import io.knotra.ContextState;
import io.knotra.ContextView;
import io.knotra.DiagnosticCode;
import io.knotra.KnotraRuntime;
import io.knotra.MountHandle;
import io.knotra.PendingOperationsSnapshot;
import io.knotra.PublicationChange;
import io.knotra.RuntimeDiagnostic;
import io.knotra.RuntimeSnapshot;
import io.knotra.Settlement;
import io.knotra.SettlementReport;
import io.knotra.TransactionReceipt;
import io.knotra.TransactionRejectedException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pf4j.DefaultPluginManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * runtime 接管后的终态等待回归：P0 纯观察 whenSettled 在 close 结构发布前只能拿到
 * 旧视图中间态，适配器必须把它视为「等待 owner」而不是终态判定。
 *
 * <p>句柄与 runtime 均为脚本化桩：不依赖真实并发窗口，第一次 whenSettled 确定性返回
 * 中间态，runtime.closeAsync future 由测试持门控制，可分别验证 pending 保持、
 * 成功收敛、失败传播、终态复核与「已结算不等待全局 close」五条路径。</p>
 */
final class ArtifactDrainRuntimeCloseBridgeTest {

    private static final String ARTIFACT_ID = "knotra-test-plugin";
    private static final Set<String> SHARED_CONTRACTS = Set.of("com.example.knotra.contract");

    private final Path fixture = Path.of(
            "target", "fixtures", "knotra-pf4j-0.1.0-SNAPSHOT-fixture.jar")
            .toAbsolutePath().normalize();

    @Test
    void intermediateObservationKeepsClosePendingUntilRuntimeCloseSucceeds(
            @TempDir Path pluginsRoot) throws Exception {
        ScriptedRuntime runtime = new ScriptedRuntime();
        Pf4jArtifactAdapter adapter = adapter(pluginsRoot, runtime);
        try {
            ScriptedHandle handle = mountScriptedRoot(
                    pluginsRoot, runtime, adapter, ComponentState.STOPPING);
            runtime.closeRequested.set(true);
            handle.firstObservation = ComponentState.ACTIVE;

            CompletableFuture<Void> adapterClose =
                    adapter.closeAsync().toCompletableFuture();
            assertTrue(runtime.closeAsyncCalled.await(10, TimeUnit.SECONDS),
                    "fallback must reach the runtime close owner signal");
            assertFalse(adapterClose.isDone(),
                    "adapter close must stay pending while runtime close is gated");
            PendingOperationsSnapshot pending = adapter.pendingOperations();
            assertTrue(pending.closeRequested(), pending::render);
            assertTrue(pending.operations().stream().anyMatch(operation ->
                            operation.kind() == PendingOperationsSnapshot.Kind.ARTIFACT_DRAIN
                                    && operation.waitsFor()
                                    == PendingOperationsSnapshot.WaitType.RUNTIME_DRAIN
                                    && operation.detail().contains("phase=wait-runtime-close")),
                    pending::render);

            handle.state.set(ComponentState.DISPOSED);
            runtime.closeFuture.complete(null);
            adapterClose.get(10, TimeUnit.SECONDS);
            assertEquals(ArtifactState.UNLOADED,
                    adapter.artifact(ARTIFACT_ID).orElseThrow().state());
            assertTrue(adapter.pendingOperations().operations().isEmpty(),
                    () -> adapter.pendingOperations().render());
        } finally {
            closeAdapter(adapter);
        }
    }

    @Test
    void intermediateObservationPropagatesRuntimeCloseFailure(
            @TempDir Path pluginsRoot) throws Exception {
        ScriptedRuntime runtime = new ScriptedRuntime();
        Pf4jArtifactAdapter adapter = adapter(pluginsRoot, runtime);
        ScriptedHandle handle = null;
        try {
            handle = mountScriptedRoot(
                    pluginsRoot, runtime, adapter, ComponentState.ACTIVE);
            runtime.closeRequested.set(true);
            handle.firstObservation = ComponentState.ACTIVE;

            CompletableFuture<Void> adapterClose =
                    adapter.closeAsync().toCompletableFuture();
            assertTrue(runtime.closeAsyncCalled.await(10, TimeUnit.SECONDS));
            assertFalse(adapterClose.isDone());

            IllegalStateException closeFailure =
                    new IllegalStateException("scripted runtime close failure");
            runtime.closeFuture.completeExceptionally(closeFailure);
            CompletionException failure =
                    assertThrows(CompletionException.class, adapterClose::join);
            assertEquals(closeFailure, failure.getCause());
            assertEquals(ArtifactState.DRAIN_FAILED,
                    adapter.artifact(ARTIFACT_ID).orElseThrow().state());
            assertEquals(ComponentState.ACTIVE, handle.state.get());
        } finally {
            if (handle != null) {
                handle.state.set(ComponentState.DISPOSED);
                handle.goal = ComponentGoal.DISPOSED;
            }
            closeAdapter(adapter);
        }
    }

    @Test
    void runtimeCloseSuccessWithNonDisposedHandleIsReportedAsFailure(
            @TempDir Path pluginsRoot) throws Exception {
        ScriptedRuntime runtime = new ScriptedRuntime();
        Pf4jArtifactAdapter adapter = adapter(pluginsRoot, runtime);
        ScriptedHandle handle = null;
        try {
            handle = mountScriptedRoot(
                    pluginsRoot, runtime, adapter, ComponentState.STOPPING);
            runtime.closeRequested.set(true);
            handle.firstObservation = ComponentState.STOPPING;

            CompletableFuture<Void> adapterClose =
                    adapter.closeAsync().toCompletableFuture();
            assertTrue(runtime.closeAsyncCalled.await(10, TimeUnit.SECONDS));

            handle.state.set(ComponentState.FAILED);
            handle.goal = ComponentGoal.DISPOSED;
            runtime.closeFuture.complete(null);
            CompletionException failure =
                    assertThrows(CompletionException.class, adapterClose::join);
            assertTrue(failure.getCause() instanceof ArtifactOperationException operation
                            && operation.getMessage().contains("did not reach DISPOSED"),
                    () -> String.valueOf(failure.getCause()));
            assertEquals(ArtifactState.DRAIN_FAILED,
                    adapter.artifact(ARTIFACT_ID).orElseThrow().state());
        } finally {
            if (handle != null) {
                handle.state.set(ComponentState.DISPOSED);
                handle.goal = ComponentGoal.DISPOSED;
            }
            closeAdapter(adapter);
        }
    }

    @Test
    void settledObservationSkipsRuntimeCloseWaitAndAvoidsOwnerCycle(
            @TempDir Path pluginsRoot) throws Exception {
        ScriptedRuntime runtime = new ScriptedRuntime();
        Pf4jArtifactAdapter adapter = adapter(pluginsRoot, runtime);
        try {
            ScriptedHandle handle = mountScriptedRoot(
                    pluginsRoot, runtime, adapter, ComponentState.STOPPING);
            runtime.closeRequested.set(true);
            // close 结构已发布且过渡已收敛的等价形态：whenSettled 直接给出终态。
            handle.firstObservation = ComponentState.DISPOSED;

            adapter.closeAsync().toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertEquals(0, runtime.closeAsyncCalls.get(),
                    "settled observation must not wait for global runtime close");
            assertEquals(ArtifactState.UNLOADED,
                    adapter.artifact(ARTIFACT_ID).orElseThrow().state());
        } finally {
            closeAdapter(adapter);
        }
    }

    private ScriptedHandle mountScriptedRoot(
            Path pluginsRoot,
            ScriptedRuntime runtime,
            Pf4jArtifactAdapter adapter,
            ComponentState initialState) {
        adapter.loadArtifactAsync(pluginsRoot.resolve("plugin.jar"))
                .toCompletableFuture().join();
        ScriptedHandle handle = new ScriptedHandle(initialState);
        runtime.nextMount = handle;
        adapter.factories()
                .resolveNoConfig("async-cleanup")
                .orElseThrow()
                .mount(runtime.root(), "scripted-root");
        return handle;
    }

    private Pf4jArtifactAdapter adapter(Path pluginsRoot, ScriptedRuntime runtime)
            throws Exception {
        Files.copy(fixture, pluginsRoot.resolve("plugin.jar"));
        KnotraClassLoaderPolicy policy = KnotraClassLoaderPolicy.forHost(SHARED_CONTRACTS);
        ArtifactCoordinator coordinator = new ArtifactCoordinator(System::nanoTime);
        DefaultPluginManager pluginManager = new DefaultPluginManager(pluginsRoot) {
            {
                this.pluginLoader = new SharedContractPluginLoader(this, policy);
            }
        };
        return new DefaultPf4jArtifactAdapter(
                pluginManager, coordinator, runtime, policy, System::nanoTime);
    }

    private static void closeAdapter(Pf4jArtifactAdapter adapter) {
        adapter.closeAsync().toCompletableFuture().join();
    }

    /** 句柄桩：第一次 whenSettled 返回脚本化观察值，之后返回当前状态。 */
    private static final class ScriptedHandle implements MountHandle {
        final AtomicReference<ComponentState> state;
        volatile ComponentGoal goal = ComponentGoal.RUNNING;
        volatile ComponentState firstObservation = ComponentState.DISPOSED;
        private final AtomicBoolean observed = new AtomicBoolean();
        private final String id = "scripted-handle-1";

        ScriptedHandle(ComponentState initialState) {
            this.state = new AtomicReference<>(initialState);
        }

        @Override
        public String handleId() {
            return id;
        }

        @Override
        public String mountId() {
            return "scripted-root";
        }

        @Override
        public String componentId() {
            return "scripted-component";
        }

        @Override
        public String factoryId() {
            return "async-cleanup";
        }

        @Override
        public String contextId() {
            return "scripted-root";
        }

        @Override
        public ComponentState state() {
            return state.get();
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
        public CompletableFuture<ComponentState> whenSettled() {
            if (observed.compareAndSet(false, true)) {
                // 结算即发布终态视图；DISPOSED 观察同时推进本地状态读数。
                if (firstObservation == ComponentState.DISPOSED) {
                    state.set(ComponentState.DISPOSED);
                }
                return CompletableFuture.completedFuture(firstObservation);
            }
            return CompletableFuture.completedFuture(state.get());
        }

        @Override
        public CompletableFuture<ComponentState> retryAsync() {
            state.set(ComponentState.DISPOSED);
            return CompletableFuture.completedFuture(ComponentState.DISPOSED);
        }

        @Override
        public CompletableFuture<ComponentState> disposeAsync() {
            return CompletableFuture.failedFuture(runtimeClosingRejection());
        }
    }

    /** runtime 桩：首次 closeAsync 由测试持门，后续重试立即收敛以便清理。 */
    private static final class ScriptedRuntime implements KnotraRuntime {
        final CompletableFuture<Void> closeFuture = new CompletableFuture<>();
        final AtomicBoolean closeRequested = new AtomicBoolean();
        final CountDownLatch closeAsyncCalled = new CountDownLatch(1);
        final AtomicInteger closeAsyncCalls = new AtomicInteger();
        volatile ScriptedHandle nextMount;
        private final AtomicBoolean firstCloseServed = new AtomicBoolean();
        private final ScriptedAdvanced advanced = new ScriptedAdvanced(this);
        private final ContextHandle root = new ScriptedContext();

        @Override
        public String runtimeId() {
            return "scripted-runtime";
        }

        @Override
        public ContextHandle root() {
            return root;
        }

        @Override
        public AdvancedRuntime advanced() {
            return advanced;
        }

        @Override
        public CompletableFuture<Void> closeAsync() {
            closeAsyncCalls.incrementAndGet();
            closeAsyncCalled.countDown();
            return firstCloseServed.compareAndSet(false, true)
                    ? closeFuture
                    : CompletableFuture.completedFuture(null);
        }
    }

    private static final class ScriptedAdvanced implements AdvancedRuntime {
        private final ScriptedRuntime runtime;

        ScriptedAdvanced(ScriptedRuntime runtime) {
            this.runtime = runtime;
        }

        @Override
        public RuntimeSnapshot snapshot() {
            return new RuntimeSnapshot(0, List.of(), List.of(), List.of(),
                    List.of(), List.of(), List.of());
        }

        @Override
        public PendingOperationsSnapshot pendingOperations() {
            return new PendingOperationsSnapshot(
                    runtime.closeRequested.get(), List.of(), 0);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <R> TransactionReceipt<R> transact(
                Function<io.knotra.RuntimeTransaction, R> transaction) {
            return new TransactionReceipt<>(
                    (R) runtime.nextMount, completedSettlement());
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

        private static Settlement completedSettlement() {
            return new Settlement() {
                @Override
                public long generation() {
                    return 0;
                }

                @Override
                public CompletableFuture<SettlementReport> whenSettled() {
                    return CompletableFuture.completedFuture(
                            new SettlementReport(0, List.of(), List.of()));
                }
            };
        }
    }

    private static final class ScriptedContext implements ContextHandle {
        @Override
        public String contextId() {
            return "scripted-root";
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
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<ContextState> disposeAsync() {
            throw new UnsupportedOperationException();
        }
    }

    private static TransactionRejectedException runtimeClosingRejection() {
        return new TransactionRejectedException(List.of(new RuntimeDiagnostic(
                DiagnosticCode.INVALID_LIFECYCLE_OPERATION,
                "scripted-runtime",
                "runtime is closing")));
    }
}
