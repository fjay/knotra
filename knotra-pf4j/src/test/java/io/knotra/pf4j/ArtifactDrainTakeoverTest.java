package io.knotra.pf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import com.example.knotra.contract.CleanupCoordinator;
import com.example.knotra.contract.ControlledGate;
import io.knotra.CapabilityKey;
import io.knotra.ComponentState;
import io.knotra.DiagnosticCode;
import io.knotra.KnotraRuntime;
import io.knotra.MountHandle;
import io.knotra.PendingOperationsSnapshot;
import io.knotra.RuntimeDiagnostic;
import io.knotra.TransactionRejectedException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pf4j.DefaultPluginManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * runtime.close 与 adapter.close 并发时 drain 的接管识别回归。
 *
 * <p>旧实现用根 Context 快照状态判断 runtime 是否 closing：closing 标志在 closeAsync
 * 入口置位，根 Context DISPOSING 稍后才发布，窗口内 disposeAsync 被 TransactionRejected
 * 拒绝时接管分支不生效，adapter close 把竞态误报为普通失败。</p>
 */
final class ArtifactDrainTakeoverTest {

    private static final String ARTIFACT_ID = "knotra-test-plugin";
    private static final Set<String> SHARED_CONTRACTS = Set.of("com.example.knotra.contract");
    private static final CapabilityKey<ControlledGate> GATE =
            CapabilityKey.of("knotra-pf4j-test-gate", ControlledGate.class);

    private final Path fixture = Path.of(
            "target", "fixtures", "knotra-pf4j-0.1.0-SNAPSHOT-fixture.jar")
            .toAbsolutePath().normalize();

    private ArtifactCoordinator coordinator;

    @Test
    void runtimeCloseRejectionIsIdentifiedExactly() {
        TransactionRejectedException closing = new TransactionRejectedException(
                List.of(new RuntimeDiagnostic(
                        DiagnosticCode.INVALID_LIFECYCLE_OPERATION,
                        "runtime",
                        "runtime is closing")));
        assertTrue(ArtifactDrainService.isRuntimeCloseRejection(closing));
        assertTrue(ArtifactDrainService.isRuntimeCloseRejection(
                new CompletionException(closing)));

        assertFalse(ArtifactDrainService.isRuntimeCloseRejection(
                new TransactionRejectedException(List.of(new RuntimeDiagnostic(
                        DiagnosticCode.INVALID_LIFECYCLE_OPERATION,
                        "publication",
                        "publication is UNPUBLISHED; cannot unpublish")))));
        assertFalse(ArtifactDrainService.isRuntimeCloseRejection(
                new TransactionRejectedException(List.of(new RuntimeDiagnostic(
                        DiagnosticCode.INVALID_MOUNT_ID,
                        "runtime",
                        "runtime is closing")))));
        assertFalse(ArtifactDrainService.isRuntimeCloseRejection(
                new TransactionRejectedException(List.of(new RuntimeDiagnostic(
                        DiagnosticCode.INVALID_MOUNT_ID,
                        "runtime",
                        "runtime is closing")))));
        assertFalse(ArtifactDrainService.isRuntimeCloseRejection(
                new IllegalStateException("runtime is closing")));
        assertFalse(ArtifactDrainService.isRuntimeCloseRejection(null));

        TransactionRejectedException disowned = new TransactionRejectedException(
                List.of(new RuntimeDiagnostic(
                        DiagnosticCode.INVALID_LIFECYCLE_OPERATION,
                        "handle-1",
                        "java.lang.IllegalArgumentException: "
                                + "component handle does not belong to this runtime")));
        assertFalse(ArtifactDrainService.isRuntimeCloseRejection(disowned));
        assertTrue(ArtifactDrainService.isHandleDisownedRejection(disowned));
        assertTrue(ArtifactDrainService.isHandleDisownedRejection(
                new CompletionException(disowned)));
        assertFalse(ArtifactDrainService.isHandleDisownedRejection(
                new TransactionRejectedException(List.of(new RuntimeDiagnostic(
                        DiagnosticCode.INVALID_LIFECYCLE_OPERATION,
                        "registration-1",
                        "registration handle does not belong to this runtime")))));
        assertFalse(ArtifactDrainService.isHandleDisownedRejection(
                new IllegalStateException(
                        "component handle does not belong to this runtime")));
        assertFalse(ArtifactDrainService.isHandleDisownedRejection(null));
    }

    @Test
    void adapterCloseWaitsForRuntimeOwnedDisposalAndKeepsPendingRecords(
            @TempDir Path pluginsRoot) throws Exception {
        try (KnotraRuntime runtime = KnotraRuntime.create();
             Pf4jArtifactAdapter adapter = adapter(pluginsRoot, runtime)) {
            adapter.loadArtifactAsync(pluginsRoot.resolve("plugin.jar"))
                    .toCompletableFuture().join();
            MountHandle handle = adapter.factories()
                    .resolveNoConfig("async-cleanup").orElseThrow()
                    .mount(runtime.root(), "takeover-root");
            handle.whenSettled().toCompletableFuture().get(10, TimeUnit.SECONDS);
            ControlledGate gate = runtime.root().view().require(GATE);

            CompletableFuture<Void> runtimeClose = runtime.closeAsync().toCompletableFuture();
            try {
                // 等到 runtime.close 确实接管了 gate 清理，再让 adapter 开始排空。
                awaitCleanupEntry(runtime);
                PendingOperationsSnapshot coreWhileOwned =
                        assertTimeoutPreemptively(Duration.ofSeconds(1),
                                () -> runtime.advanced().pendingOperations());
                assertTrue(coreWhileOwned.closeRequested(), coreWhileOwned::render);

                CompletableFuture<Void> adapterClose =
                        adapter.closeAsync().toCompletableFuture();
                PendingOperationsSnapshot blocked =
                        awaitDrain(adapter, handle.handleId(), adapterClose);
                assertTrue(blocked.closeRequested(), blocked::render);
                PendingOperationsSnapshot coreWhileBlocked =
                        assertTimeoutPreemptively(Duration.ofSeconds(1),
                                () -> runtime.advanced().pendingOperations());
                assertTrue(coreWhileBlocked.closeRequested(), coreWhileBlocked::render);

                gate.release();
                adapterClose.get(20, TimeUnit.SECONDS);
                runtimeClose.get(20, TimeUnit.SECONDS);
                assertEquals(ArtifactState.UNLOADED,
                        adapter.artifact(ARTIFACT_ID).orElseThrow().state());
                assertEquals(ComponentState.DISPOSED, handle.state());
            } finally {
                gate.release();
            }
            awaitNoDrainOperations(adapter);
        }
    }

    @Test
    void failedRuntimeOwnedCleanupStillPropagates(@TempDir Path pluginsRoot)
            throws Exception {
        try (KnotraRuntime runtime = KnotraRuntime.create();
             Pf4jArtifactAdapter adapter = adapter(pluginsRoot, runtime)) {
            adapter.loadArtifactAsync(pluginsRoot.resolve("plugin.jar"))
                    .toCompletableFuture().join();
            CleanupCoordinator.reset();
            MountHandle handle = adapter.factories()
                    .resolveNoConfig("failing-cleanup").orElseThrow()
                    .mount(runtime.root(), "failed-takeover");
            assertEquals(ComponentState.ACTIVE,
                    handle.whenSettled().toCompletableFuture().get(10, TimeUnit.SECONDS));

            CleanupCoordinator.failNextCleanup();
            CompletableFuture<Void> runtimeClose = runtime.closeAsync().toCompletableFuture();
            awaitState(handle, ComponentState.FAILED);
            assertThrows(CompletionException.class, runtimeClose::join);

            // runtime 接管的清理失败后，适配器 drain 的重试失败必须照常传播。
            CleanupCoordinator.failNextCleanup();
            CompletableFuture<Void> adapterClose = adapter.closeAsync().toCompletableFuture();
            assertThrows(CompletionException.class, adapterClose::join);
            assertEquals(ArtifactState.DRAIN_FAILED,
                    adapter.artifact(ARTIFACT_ID).orElseThrow().state());
            assertEquals(ComponentState.FAILED, handle.state());
            awaitNoDrainOperations(adapter);

            // 恢复清理后重试必须收敛为真实 DISPOSED，而不是把失败伪装成成功。
            CleanupCoordinator.allowCleanup();
            adapter.closeAsync().toCompletableFuture().get(20, TimeUnit.SECONDS);
            runtime.closeAsync().toCompletableFuture().get(20, TimeUnit.SECONDS);
            assertEquals(ArtifactState.UNLOADED,
                    adapter.artifact(ARTIFACT_ID).orElseThrow().state());
            assertEquals(ComponentState.DISPOSED, handle.state());
        } finally {
            CleanupCoordinator.allowCleanup();
        }
    }

    private Pf4jArtifactAdapter adapter(Path pluginsRoot, KnotraRuntime runtime)
            throws Exception {
        Files.copy(fixture, pluginsRoot.resolve("plugin.jar"));
        KnotraClassLoaderPolicy policy = KnotraClassLoaderPolicy.forHost(SHARED_CONTRACTS);
        coordinator = new ArtifactCoordinator(System::nanoTime);
        DefaultPluginManager pluginManager = new DefaultPluginManager(pluginsRoot) {
            {
                this.pluginLoader = new SharedContractPluginLoader(this, policy);
            }
        };
        return new DefaultPf4jArtifactAdapter(
                pluginManager, coordinator, runtime, policy, System::nanoTime);
    }

    private static void awaitCleanupEntry(KnotraRuntime runtime)
            throws Exception {
        for (int attempt = 0; attempt < 1_000; attempt++) {
            PendingOperationsSnapshot snapshot = runtime.advanced().pendingOperations();
            boolean found = snapshot.operations().stream().anyMatch(operation ->
                    operation.kind() == PendingOperationsSnapshot.Kind.LIFECYCLE_CLEANUP
                            && operation.detail().contains("async gate"));
            if (found) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError(runtime.advanced().pendingOperations().render());
    }

    private static PendingOperationsSnapshot awaitDrain(
            Pf4jArtifactAdapter adapter,
            String handleId,
            CompletableFuture<Void> close) throws Exception {
        for (int attempt = 0; attempt < 1_000; attempt++) {
            PendingOperationsSnapshot snapshot = adapter.pendingOperations();
            if (snapshot.operations().stream().anyMatch(drainVisible(handleId))) {
                return snapshot;
            }
            if (close.isDone()) {
                try {
                    close.join();
                    throw new AssertionError(
                            "adapter close converged before drain was observed: "
                                    + snapshot.render());
                } catch (CompletionException failure) {
                    AssertionError error = new AssertionError(
                            "adapter close failed before drain was observed: "
                                    + snapshot.render(),
                            failure.getCause());
                    throw error;
                }
            }
            Thread.sleep(10);
        }
        throw new AssertionError(adapter.pendingOperations().render());
    }

    private static Predicate<PendingOperationsSnapshot.Operation> drainVisible(String handleId) {
        return operation -> operation.kind() == PendingOperationsSnapshot.Kind.ARTIFACT_DRAIN
                && operation.targetId().equals(ARTIFACT_ID)
                && operation.detail().contains(handleId);
    }

    private static void awaitNoDrainOperations(Pf4jArtifactAdapter adapter)
            throws Exception {
        for (int attempt = 0; attempt < 1_000; attempt++) {
            if (adapter.pendingOperations().operations().isEmpty()) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError(adapter.pendingOperations().render());
    }

    private static void awaitState(MountHandle handle, ComponentState state)
            throws Exception {
        for (int attempt = 0; attempt < 1_000; attempt++) {
            if (handle.state() == state) {
                return;
            }
            Thread.sleep(10);
        }
        assertEquals(state, handle.state());
    }
}
