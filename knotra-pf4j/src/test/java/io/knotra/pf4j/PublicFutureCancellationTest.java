package io.knotra.pf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import com.example.knotra.contract.CleanupCoordinator;
import com.example.knotra.contract.ControlledGate;
import com.example.knotra.contract.ReferenceVault;
import io.knotra.CapabilityKey;
import io.knotra.ComponentState;
import io.knotra.KnotraRuntime;
import io.knotra.MountHandle;
import io.knotra.PendingOperationsSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pf4j.DefaultPluginManager;
import org.pf4j.PluginState;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 公开 future 必须是内部驱动 future 的观察镜像：调用方 cancel 只结束自己的等待，
 * 内部 load/drain/close 继续收敛，其他观察者不受影响。
 */
final class PublicFutureCancellationTest {

    private static final String ARTIFACT_ID = "knotra-test-plugin";
    private static final Set<String> SHARED_CONTRACTS = Set.of("com.example.knotra.contract");
    private static final CapabilityKey<ControlledGate> GATE =
            CapabilityKey.of("knotra-pf4j-test-gate", ControlledGate.class);

    private final Path fixture = Path.of(
            "target", "fixtures", "knotra-pf4j-0.1.0-SNAPSHOT-fixture.jar")
            .toAbsolutePath().normalize();

    private ArtifactCoordinator coordinator;
    private StartGatedPluginManager pluginManager;

    @AfterEach
    void releaseGates() {
        if (pluginManager != null) {
            pluginManager.releaseStart();
        }
        if (coordinator != null) {
            coordinator.stop();
        }
    }

    @Test
    void cancelledUnloadObserverKeepsInternalDrainVisibleAndRetryDoesNotInheritCancel(
            @TempDir Path pluginsRoot) throws Exception {
        try (KnotraRuntime runtime = KnotraRuntime.create();
             Pf4jArtifactAdapter adapter = adapter(pluginsRoot, runtime)) {
            ControlledGate gate = loadAndMountGatedRoot(adapter, runtime, "mirror-unload");

            CompletableFuture<Void> unload = adapter.unloadArtifactAsync(ARTIFACT_ID)
                    .toCompletableFuture();
            awaitDrainPhase(adapter, PendingOperationsSnapshot.WaitType.COMPONENT);

            assertTrue(unload.cancel(true));
            assertThrows(CancellationException.class, unload::join);

            // 内部 drain 未被取消：pending 诊断仍可见。
            assertTrue(hasDrainRecord(adapter),
                    () -> adapter.pendingOperations().render());

            CompletableFuture<Void> retry = adapter.retryDrainAsync(ARTIFACT_ID)
                    .toCompletableFuture();
            gate.release();
            retry.get(20, TimeUnit.SECONDS);
            assertTrue(retry.isDone() && !retry.isCompletedExceptionally());
            assertTrue(unload.isCancelled());
            assertEquals(ArtifactState.UNLOADED,
                    adapter.artifact(ARTIFACT_ID).orElseThrow().state());
        }
    }

    @Test
    void cancelledObserverDoesNotAffectOtherDrainObservers(@TempDir Path pluginsRoot) throws Exception {
        try (KnotraRuntime runtime = KnotraRuntime.create();
             Pf4jArtifactAdapter adapter = adapter(pluginsRoot, runtime)) {
            ControlledGate gate = loadAndMountGatedRoot(adapter, runtime, "mirror-observers");

            CompletableFuture<Void> driving = adapter.unloadArtifactAsync(ARTIFACT_ID)
                    .toCompletableFuture();
            awaitDrainPhase(adapter, PendingOperationsSnapshot.WaitType.COMPONENT);
            CompletableFuture<Void> survivorOne = adapter.retryDrainAsync(ARTIFACT_ID)
                    .toCompletableFuture();
            CompletableFuture<Void> cancelled = adapter.retryDrainAsync(ARTIFACT_ID)
                    .toCompletableFuture();
            CompletableFuture<Void> survivorTwo = adapter.retryDrainAsync(ARTIFACT_ID)
                    .toCompletableFuture();

            assertTrue(cancelled.cancel(true));
            gate.release();

            driving.get(20, TimeUnit.SECONDS);
            survivorOne.get(20, TimeUnit.SECONDS);
            survivorTwo.get(20, TimeUnit.SECONDS);
            assertTrue(cancelled.isCancelled());
            assertThrows(CancellationException.class, cancelled::join);
            assertEquals(ArtifactState.UNLOADED,
                    adapter.artifact(ARTIFACT_ID).orElseThrow().state());
        }
    }

    @Test
    void cancelledCloseMirrorAllowsSecondCloseToSucceed(@TempDir Path pluginsRoot) throws Exception {
        try (KnotraRuntime runtime = KnotraRuntime.create();
             Pf4jArtifactAdapter adapter = adapter(pluginsRoot, runtime)) {
            ControlledGate gate = loadAndMountGatedRoot(adapter, runtime, "mirror-close");

            CompletableFuture<Void> first = adapter.closeAsync().toCompletableFuture();
            awaitDrainPhase(adapter, PendingOperationsSnapshot.WaitType.COMPONENT);

            assertTrue(first.cancel(true));
            assertThrows(CancellationException.class, first::join);
            assertTrue(adapter.pendingOperations().closeRequested());

            CompletableFuture<Void> second = adapter.closeAsync().toCompletableFuture();
            gate.release();
            second.get(20, TimeUnit.SECONDS);
            assertTrue(first.isCancelled());
            assertEquals(ArtifactState.UNLOADED,
                    adapter.artifact(ARTIFACT_ID).orElseThrow().state());

            // 已成功的内部 close 幂等：后续调用立即成功。
            adapter.closeAsync().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void closeFailureClearsInternalAttemptAndRetryCloseSucceeds(@TempDir Path pluginsRoot) throws Exception {
        try (KnotraRuntime runtime = KnotraRuntime.create();
             Pf4jArtifactAdapter adapter = adapter(pluginsRoot, runtime)) {
            adapter.loadArtifactAsync(pluginsRoot.resolve("plugin.jar"))
                    .toCompletableFuture().join();
            CleanupCoordinator.reset();
            CleanupCoordinator.failNextCleanup();
            MountHandle handle = adapter.factories()
                    .resolveNoConfig("failing-cleanup").orElseThrow()
                    .mount(runtime.root(), "mirror-close-retry");
            handle.whenSettled().toCompletableFuture().get(10, TimeUnit.SECONDS);

            CompletableFuture<Void> failed = adapter.closeAsync().toCompletableFuture();
            assertThrows(CompletionException.class, failed::join);
            assertTrue(failed.isCompletedExceptionally());

            CleanupCoordinator.allowCleanup();
            adapter.closeAsync().toCompletableFuture().get(20, TimeUnit.SECONDS);
            assertEquals(ArtifactState.UNLOADED,
                    adapter.artifact(ARTIFACT_ID).orElseThrow().state());
            assertEquals(ComponentState.DISPOSED, handle.state());
        }
    }

    @Test
    void cancelledLoadObserverLeavesInternalStateConsistent(@TempDir Path pluginsRoot) throws Exception {
        try (KnotraRuntime runtime = KnotraRuntime.create();
             Pf4jArtifactAdapter adapter = adapter(pluginsRoot, runtime)) {
            pluginManager.blockNextStart();
            CompletableFuture<ArtifactSnapshot> first = adapter.loadArtifactAsync(
                    pluginsRoot.resolve("plugin.jar")).toCompletableFuture();
            assertTrue(pluginManager.enteredStart().await(10, TimeUnit.SECONDS));

            assertTrue(first.cancel(true));
            assertThrows(CancellationException.class, first::join);
            pluginManager.releaseStart();

            // 协调器上排队/运行的内部加载不受镜像取消影响，最终 ACTIVE/FAILED 一致。
            ArtifactSnapshot snapshot = adapter.loadArtifactAsync(
                            pluginsRoot.resolve("plugin.jar"))
                    .toCompletableFuture().join();
            assertEquals(ArtifactState.ACTIVE, snapshot.state());
            assertEquals(ArtifactState.ACTIVE,
                    adapter.artifact(ARTIFACT_ID).orElseThrow().state());
        }
    }

    @Test
    void retainedMirrorsDoNotPinPluginClassLoaders(@TempDir Path pluginsRoot) throws Exception {
        ReferenceVault.clear();
        CompletableFuture<ArtifactSnapshot> retainedLoadMirror = null;
        CompletableFuture<Void> retainedCancelledUnload = null;
        CompletableFuture<Void> retainedCompletedRetry = null;
        try (KnotraRuntime runtime = KnotraRuntime.create();
             Pf4jArtifactAdapter adapter = adapter(pluginsRoot, runtime)) {
            retainedLoadMirror = adapter.loadArtifactAsync(pluginsRoot.resolve("plugin.jar"))
                    .toCompletableFuture();
            retainedLoadMirror.join();
            MountHandle handle = adapter.factories()
                    .resolve("alpha", String.class).orElseThrow()
                    .mount(runtime.root(), "mirror-gc", "value");
            handle.whenSettled().toCompletableFuture().get(10, TimeUnit.SECONDS);

            pluginManager.blockNextUnload();
            retainedCancelledUnload = adapter.unloadArtifactAsync(ARTIFACT_ID)
                    .toCompletableFuture();
            awaitDrainPhase(adapter, PendingOperationsSnapshot.WaitType.PF4J_STOP_UNLOAD);
            assertTrue(retainedCancelledUnload.cancel(true));
            retainedCompletedRetry = adapter.retryDrainAsync(ARTIFACT_ID)
                    .toCompletableFuture();
            pluginManager.releaseUnload();
            retainedCompletedRetry.get(20, TimeUnit.SECONDS);
            assertEquals(ArtifactState.UNLOADED,
                    adapter.artifact(ARTIFACT_ID).orElseThrow().state());
        }
        // 镜像仍被测试强引用；插件 ClassLoader 必须已经可回收。
        assertTrue(retainedCancelledUnload.isCancelled());
        assertTrue(retainedCompletedRetry.isDone());
        assertCollectorsReachZero();
    }

    @Test
    void hundredRoundsOfCancelledDrainObserversKeepInternalDrainsAlive(
            @TempDir Path pluginsRoot) throws Exception {
        int rounds = Integer.getInteger("knotra.pf4j.cancel.rounds", 100);
        for (int round = 0; round < rounds; round++) {
            try (KnotraRuntime runtime = KnotraRuntime.create();
                 Pf4jArtifactAdapter adapter = adapter(pluginsRoot, runtime)) {
                ControlledGate gate = loadAndMountGatedRoot(adapter, runtime, "round-" + round);

                CompletableFuture<Void> unload = adapter.unloadArtifactAsync(ARTIFACT_ID)
                        .toCompletableFuture();
                awaitDrainPhase(adapter, PendingOperationsSnapshot.WaitType.COMPONENT);
                assertTrue(unload.cancel(true), "round " + round);
                assertThrows(CancellationException.class, unload::join);
                assertTrue(hasDrainRecord(adapter), "round " + round);

                CompletableFuture<Void> retry = adapter.retryDrainAsync(ARTIFACT_ID)
                        .toCompletableFuture();
                gate.release();
                retry.get(20, TimeUnit.SECONDS);
                assertEquals(ArtifactState.UNLOADED,
                        adapter.artifact(ARTIFACT_ID).orElseThrow().state(),
                        "round " + round);
            }
        }
    }

    private ControlledGate loadAndMountGatedRoot(
            Pf4jArtifactAdapter adapter,
            KnotraRuntime runtime,
            String mountId) throws Exception {
        adapter.loadArtifactAsync(pluginManager.getPluginsRoot().resolve("plugin.jar"))
                .toCompletableFuture().join();
        return loadAndMountGatedRootAlreadyLoaded(adapter, runtime, mountId);
    }

    private ControlledGate loadAndMountGatedRootAlreadyLoaded(
            Pf4jArtifactAdapter adapter,
            KnotraRuntime runtime,
            String mountId) throws Exception {
        MountHandle handle = adapter.factories()
                .resolveNoConfig("async-cleanup").orElseThrow()
                .mount(runtime.root(), mountId);
        assertEquals(ComponentState.ACTIVE,
                handle.whenSettled().toCompletableFuture().get(10, TimeUnit.SECONDS));
        return runtime.root().view().require(GATE);
    }

    private static void awaitDrainPhase(
            Pf4jArtifactAdapter adapter,
            PendingOperationsSnapshot.WaitType waitType) throws Exception {
        Predicate<PendingOperationsSnapshot.Operation> filter = item ->
                item.kind() == PendingOperationsSnapshot.Kind.ARTIFACT_DRAIN
                        && item.targetId().equals(ARTIFACT_ID)
                        && item.waitsFor() == waitType;
        for (int attempt = 0; attempt < 1_000; attempt++) {
            if (adapter.pendingOperations().operations().stream().anyMatch(filter)) {
                return;
            }
            Thread.sleep(10);
        }
        fail(adapter.pendingOperations().render());
    }

    private static boolean hasDrainRecord(Pf4jArtifactAdapter adapter) {
        return adapter.pendingOperations().operations().stream()
                .anyMatch(item -> item.kind() == PendingOperationsSnapshot.Kind.ARTIFACT_DRAIN
                        && item.targetId().equals(ARTIFACT_ID));
    }

    private static void assertCollectorsReachZero() throws Exception {
        for (int attempt = 0; attempt < 100; attempt++) {
            System.gc();
            if (ReferenceVault.liveLoaders() == 0) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(10);
        }
        assertEquals(0L, ReferenceVault.liveLoaders());
    }

    private Pf4jArtifactAdapter adapter(Path pluginsRoot, KnotraRuntime runtime) throws Exception {
        if (!Files.exists(pluginsRoot.resolve("plugin.jar"))) {
            Files.copy(fixture, pluginsRoot.resolve("plugin.jar"));
        }
        KnotraClassLoaderPolicy policy = KnotraClassLoaderPolicy.forHost(SHARED_CONTRACTS);
        coordinator = new ArtifactCoordinator();
        pluginManager = new StartGatedPluginManager(pluginsRoot, policy);
        return new DefaultPf4jArtifactAdapter(pluginManager, coordinator, runtime, policy);
    }

    /** 只在 startPlugin 上提供一次性门，用于把内部加载停在镜像 cancel 之后。 */
    private static final class StartGatedPluginManager extends DefaultPluginManager {
        private final CountDownLatch enteredStart = new CountDownLatch(1);
        private final CountDownLatch enteredUnload = new CountDownLatch(1);
        private volatile CompletableFuture<Void> startGate = new CompletableFuture<>();
        private volatile CompletableFuture<Void> unloadGate = new CompletableFuture<>();
        private volatile boolean startGateEnabled;
        private volatile boolean unloadGateEnabled;

        StartGatedPluginManager(Path pluginsRoot, KnotraClassLoaderPolicy policy) {
            super(pluginsRoot);
            this.pluginLoader = new SharedContractPluginLoader(this, policy);
        }

        @Override
        public PluginState startPlugin(String pluginId) {
            if (startGateEnabled && ARTIFACT_ID.equals(pluginId)) {
                startGateEnabled = false;
                enteredStart.countDown();
                startGate.join();
            }
            return super.startPlugin(pluginId);
        }

        @Override
        public boolean unloadPlugin(String pluginId) {
            if (unloadGateEnabled && ARTIFACT_ID.equals(pluginId)) {
                unloadGateEnabled = false;
                enteredUnload.countDown();
                unloadGate.join();
            }
            return super.unloadPlugin(pluginId);
        }

        void blockNextStart() {
            startGateEnabled = true;
        }

        void blockNextUnload() {
            unloadGateEnabled = true;
        }

        CountDownLatch enteredStart() {
            return enteredStart;
        }

        void releaseStart() {
            CompletableFuture<Void> current = startGate;
            startGate = new CompletableFuture<>();
            current.complete(null);
        }

        void releaseUnload() {
            CompletableFuture<Void> current = unloadGate;
            unloadGate = new CompletableFuture<>();
            current.complete(null);
        }
    }
}
