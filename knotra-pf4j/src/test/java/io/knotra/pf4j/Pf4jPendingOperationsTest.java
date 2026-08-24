package io.knotra.pf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Predicate;

import com.example.knotra.contract.CleanupCoordinator;
import com.example.knotra.contract.ControlledGate;
import com.example.knotra.contract.MountCoordinator;
import com.example.knotra.contract.ReferenceVault;
import io.knotra.ComponentState;
import io.knotra.KnotraRuntime;
import io.knotra.MountHandle;
import io.knotra.PendingOperationsSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pf4j.DefaultPluginManager;

import static org.junit.jupiter.api.Assertions.*;

final class Pf4jPendingOperationsTest {

    private static final String ARTIFACT_ID = "knotra-test-plugin";
    private static final Set<String> SHARED_CONTRACTS = Set.of("com.example.knotra.contract");

    private final Path fixture = Path.of(
            "target", "fixtures", "knotra-pf4j-0.1.0-SNAPSHOT-fixture.jar")
            .toAbsolutePath().normalize();

    private ArtifactCoordinator coordinator;
    private ControlledPluginManager pluginManager;

    @AfterEach
    void stopCoordinator() {
        if (coordinator != null) {
            coordinator.stop();
        }
        if (pluginManager != null) {
            pluginManager.releaseGate();
        }
    }

    @Test
    void mountGateReportsInFlightMountAndAgesFromZero(@TempDir Path pluginsRoot) throws Exception {
        AtomicLong clock = new AtomicLong(-10);
        try (KnotraRuntime runtime = KnotraRuntime.create();
             Pf4jArtifactAdapter adapter = adapter(pluginsRoot, runtime, clock);
             ExecutorService executor = Executors.newSingleThreadExecutor()) {
            adapter.loadArtifactAsync(pluginsRoot.resolve("plugin.jar")).toCompletableFuture().join();
            MountCoordinator.reset();
            var factory = adapter.factories().resolveNoConfig("in-flight").orElseThrow();
            CompletableFuture<MountHandle> mount = CompletableFuture.supplyAsync(
                    () -> factory.mount(runtime.root(), "pending-mount"), executor);
            MountCoordinator.entered().get(10, TimeUnit.SECONDS);

            PendingOperationsSnapshot first = adapter.pendingOperations();
            PendingOperationsSnapshot.Operation operation = require(first, item ->
                    item.kind() == PendingOperationsSnapshot.Kind.ARTIFACT_MOUNT
                            && item.targetId().equals(ARTIFACT_ID));
            assertEquals(PendingOperationsSnapshot.WaitType.MOUNTS_IN_FLIGHT, operation.waitsFor());
            assertEquals(Duration.ZERO, operation.age());
            assertTrue(operation.detail().contains("mountsInFlight=1"), operation.detail());

            clock.incrementAndGet();
            PendingOperationsSnapshot second = adapter.pendingOperations();
            assertEquals(Duration.ofNanos(1), require(second, item ->
                    item.kind() == PendingOperationsSnapshot.Kind.ARTIFACT_MOUNT
                            && item.targetId().equals(ARTIFACT_ID)).age());
            MountCoordinator.releaseCreate();
            assertEquals(ComponentState.ACTIVE,
                    mount.join().whenSettled().toCompletableFuture().get(10, TimeUnit.SECONDS));
            awaitNoOperations(adapter, item -> item.kind() == PendingOperationsSnapshot.Kind.ARTIFACT_MOUNT);
        }
    }

    @Test
    void drainReportsWaitingForMountsInFlight(@TempDir Path pluginsRoot) throws Exception {
        AtomicLong clock = new AtomicLong(-10);
        try (KnotraRuntime runtime = KnotraRuntime.create();
             Pf4jArtifactAdapter adapter = adapter(pluginsRoot, runtime, clock);
             ExecutorService executor = Executors.newSingleThreadExecutor()) {
            adapter.loadArtifactAsync(pluginsRoot.resolve("plugin.jar")).toCompletableFuture().join();
            MountCoordinator.reset();
            var factory = adapter.factories().resolveNoConfig("in-flight").orElseThrow();
            CompletableFuture<MountHandle> mount = CompletableFuture.supplyAsync(
                    () -> factory.mount(runtime.root(), "blocked-mount"), executor);
            MountCoordinator.entered().get(10, TimeUnit.SECONDS);
            CompletableFuture<Void> unload = adapter.unloadArtifactAsync(ARTIFACT_ID)
                    .toCompletableFuture();
            PendingOperationsSnapshot snapshot = awaitOperation(adapter, item ->
                    item.kind() == PendingOperationsSnapshot.Kind.ARTIFACT_DRAIN
                            && item.waitsFor() == PendingOperationsSnapshot.WaitType.MOUNTS_IN_FLIGHT);
            PendingOperationsSnapshot.Operation drain = require(snapshot, item ->
                    item.kind() == PendingOperationsSnapshot.Kind.ARTIFACT_DRAIN
                            && item.targetId().equals(ARTIFACT_ID));
            assertEquals(PendingOperationsSnapshot.WaitType.MOUNTS_IN_FLIGHT, drain.waitsFor());
            assertTrue(drain.detail().contains("phase=wait-mounts"), drain.detail());
            assertTrue(drain.detail().contains("closureIds=[" + ARTIFACT_ID + "]"), drain.detail());

            MountCoordinator.releaseCreate();
            assertThrows(CompletionException.class, mount::join);
            unload.join();
            awaitNoOperations(adapter, item -> item.kind() == PendingOperationsSnapshot.Kind.ARTIFACT_DRAIN);
        }
    }

    @Test
    void drainReportsRootDisposalAndCloseRequest(@TempDir Path pluginsRoot) throws Exception {
        try (KnotraRuntime runtime = KnotraRuntime.create();
             Pf4jArtifactAdapter adapter = adapter(pluginsRoot, runtime, new AtomicLong())) {
            adapter.loadArtifactAsync(pluginsRoot.resolve("plugin.jar")).toCompletableFuture().join();
            MountHandle handle = adapter.factories()
                    .resolveNoConfig("async-cleanup").orElseThrow()
                    .mount(runtime.root(), "pending-root");
            handle.whenSettled().toCompletableFuture().get(10, TimeUnit.SECONDS);
            ControlledGate gate = runtime.root().view().require(
                    io.knotra.CapabilityKey.of("knotra-pf4j-test-gate", ControlledGate.class));

            CompletableFuture<Void> close = adapter.closeAsync().toCompletableFuture();
            try {
                PendingOperationsSnapshot snapshot = awaitOperation(adapter, item ->
                        item.kind() == PendingOperationsSnapshot.Kind.ARTIFACT_DRAIN
                                && item.targetId().equals(ARTIFACT_ID)
                                && item.waitsFor() == PendingOperationsSnapshot.WaitType.COMPONENT
                                && item.detail().contains("phase=dispose-roots"));
                PendingOperationsSnapshot.Operation drain = require(snapshot, item ->
                        item.kind() == PendingOperationsSnapshot.Kind.ARTIFACT_DRAIN
                                && item.targetId().equals(ARTIFACT_ID)
                                && item.waitsFor() == PendingOperationsSnapshot.WaitType.COMPONENT
                                && item.detail().contains("phase=dispose-roots"));
                assertTrue(snapshot.closeRequested());
                assertTrue(drain.detail().contains(handle.handleId()), drain.detail());
            } finally {
                gate.release();
            }
            close.get(20, TimeUnit.SECONDS);
            awaitNoOperations(adapter, operation -> true);
            PendingOperationsSnapshot settled = adapter.pendingOperations();
            assertTrue(settled.closeRequested());
            assertTrue(settled.operations().isEmpty(), settled::render);
        }
    }

    @Test
    void drainReportsStopUnloadAndReturnsWhileCoordinatorIsBusy(@TempDir Path pluginsRoot) throws Exception {
        AtomicLong clock = new AtomicLong(-10);
        try (KnotraRuntime runtime = KnotraRuntime.create();
             Pf4jArtifactAdapter adapter = adapter(pluginsRoot, runtime, clock)) {
            adapter.loadArtifactAsync(pluginsRoot.resolve("plugin.jar")).toCompletableFuture().join();
            MountHandle handle = adapter.factories()
                    .resolveNoConfig("async-cleanup").orElseThrow()
                    .mount(runtime.root(), "stop-unload-root");
            handle.whenSettled().toCompletableFuture().get(10, TimeUnit.SECONDS);
            ControlledGate gate = runtime.root().view().require(
                    io.knotra.CapabilityKey.of("knotra-pf4j-test-gate", ControlledGate.class));
            pluginManager.blockNextUnload();
            CompletableFuture<Void> unload = adapter.unloadArtifactAsync(ARTIFACT_ID)
                    .toCompletableFuture();
            gate.release();
            assertTrue(pluginManager.enteredUnload().await(10, TimeUnit.SECONDS));

            PendingOperationsSnapshot snapshot = assertTimeoutPreemptively(
                    Duration.ofSeconds(2), adapter::pendingOperations);
            PendingOperationsSnapshot.Operation drain = require(snapshot, item ->
                    item.kind() == PendingOperationsSnapshot.Kind.ARTIFACT_DRAIN
                            && item.waitsFor() == PendingOperationsSnapshot.WaitType.PF4J_STOP_UNLOAD);
            assertEquals(ARTIFACT_ID, drain.targetId());
            assertTrue(drain.detail().contains("phase=stop-unload"), drain.detail());
            assertPureDto(snapshot);

            clock.incrementAndGet();
            assertEquals(Duration.ofNanos(1), require(adapter.pendingOperations(), item ->
                    item.kind() == PendingOperationsSnapshot.Kind.ARTIFACT_DRAIN).age());

            pluginManager.releaseGate();
            unload.get(20, TimeUnit.SECONDS);
            awaitNoOperations(adapter, item -> item.kind() == PendingOperationsSnapshot.Kind.ARTIFACT_DRAIN);
        }
    }

    @Test
    void failedDrainClearsTrackingAndRetrySucceeds(@TempDir Path pluginsRoot) throws Exception {
        try (KnotraRuntime runtime = KnotraRuntime.create();
             Pf4jArtifactAdapter adapter = adapter(pluginsRoot, runtime, new AtomicLong())) {
            adapter.loadArtifactAsync(pluginsRoot.resolve("plugin.jar")).toCompletableFuture().join();
            CleanupCoordinator.reset();
            CleanupCoordinator.failNextCleanup();
            MountHandle handle = adapter.factories()
                    .resolveNoConfig("failing-cleanup").orElseThrow()
                    .mount(runtime.root(), "failed-drain");
            handle.whenSettled().toCompletableFuture().get(10, TimeUnit.SECONDS);

            CompletableFuture<Void> unload = adapter.unloadArtifactAsync(ARTIFACT_ID)
                    .toCompletableFuture();
            assertThrows(CompletionException.class, unload::join);
            awaitNoOperations(adapter, item -> item.kind() == PendingOperationsSnapshot.Kind.ARTIFACT_DRAIN);
            assertEquals(ComponentState.FAILED, handle.state());
            assertTrue(adapter.diagnostic(ARTIFACT_ID).isPresent());

            CleanupCoordinator.allowCleanup();
            adapter.retryDrainAsync(ARTIFACT_ID).toCompletableFuture().join();
            assertTrue(adapter.pendingOperations().operations().isEmpty());
        }
    }

    @Test
    void concurrentReadersDoNotUseTheBusyCoordinator(@TempDir Path pluginsRoot) throws Exception {
        AtomicLong clock = new AtomicLong(-10);
        try (KnotraRuntime runtime = KnotraRuntime.create();
             Pf4jArtifactAdapter adapter = adapter(pluginsRoot, runtime, clock);
             ExecutorService executor = Executors.newFixedThreadPool(8)) {
            adapter.loadArtifactAsync(pluginsRoot.resolve("plugin.jar")).toCompletableFuture().join();
            MountHandle handle = adapter.factories()
                    .resolveNoConfig("async-cleanup").orElseThrow()
                    .mount(runtime.root(), "concurrent-root");
            handle.whenSettled().toCompletableFuture().get(10, TimeUnit.SECONDS);
            ControlledGate gate = runtime.root().view().require(
                    io.knotra.CapabilityKey.of("knotra-pf4j-test-gate", ControlledGate.class));
            pluginManager.blockNextUnload();
            CompletableFuture<Void> unload = adapter.unloadArtifactAsync(ARTIFACT_ID)
                    .toCompletableFuture();
            gate.release();
            assertTrue(pluginManager.enteredUnload().await(10, TimeUnit.SECONDS));

            List<CompletableFuture<PendingOperationsSnapshot>> reads = new ArrayList<>();
            for (int index = 0; index < 32; index++) {
                reads.add(CompletableFuture.supplyAsync(adapter::pendingOperations, executor));
            }
            for (CompletableFuture<PendingOperationsSnapshot> read : reads) {
                PendingOperationsSnapshot snapshot = assertTimeoutPreemptively(
                        Duration.ofSeconds(2), () -> read.get(10, TimeUnit.SECONDS));
                require(snapshot, item -> item.kind() == PendingOperationsSnapshot.Kind.ARTIFACT_DRAIN);
            }

            pluginManager.releaseGate();
            unload.get(20, TimeUnit.SECONDS);
        }
    }

    @Test
    void coordinatorMonitorReportsOnlyPureMetadata(@TempDir Path pluginsRoot) throws Exception {
        AtomicLong clock = new AtomicLong(-10);
        coordinator = new ArtifactCoordinator(clock::get);
        CountDownLatch entered = new CountDownLatch(1);
        CompletableFuture<Void> release = new CompletableFuture<>();
        CompletableFuture<Void> first = coordinator.submit("blocked", () -> {
            entered.countDown();
            return release.join();
        });
        assertTrue(entered.await(10, TimeUnit.SECONDS));
        CompletableFuture<String> queued = coordinator.submit("queued", () -> "queued");

        List<ArtifactCoordinator.CoordinatorOperation> pending = coordinator.pendingOperations();
        assertEquals(2, pending.size());
        clock.incrementAndGet();
        List<ArtifactCoordinator.CoordinatorOperation> aged = coordinator.pendingOperations();
        assertEquals(2, aged.size());
        assertTrue(aged.stream().anyMatch(item ->
                item.running()
                && item.target().equals("blocked")
                && item.age().equals(Duration.ofNanos(1))));
        assertTrue(aged.stream().anyMatch(item ->
                !item.running()
                && item.target().equals("queued")
                && !item.age().isNegative()));

        release.complete(null);
        first.get(10, TimeUnit.SECONDS);
        assertEquals("queued", queued.get(10, TimeUnit.SECONDS));
        for (int attempt = 0; attempt < 100
                && !coordinator.pendingOperations().isEmpty(); attempt++) {
            Thread.sleep(10);
        }
        assertTrue(coordinator.pendingOperations().isEmpty());
    }

    @Test
    void pendingOperationsIncludesCoordinatorQueueMetadata(@TempDir Path pluginsRoot) throws Exception {
        AtomicLong clock = new AtomicLong(-10);
        try (KnotraRuntime runtime = KnotraRuntime.create();
             Pf4jArtifactAdapter adapter = adapter(pluginsRoot, runtime, clock)) {
            adapter.loadArtifactAsync(pluginsRoot.resolve("plugin.jar")).toCompletableFuture().join();

            CountDownLatch entered = new CountDownLatch(1);
            CompletableFuture<Void> release = new CompletableFuture<>();
            CompletableFuture<Void> blocked = coordinator.submit("blocked", () -> {
                entered.countDown();
                return release.join();
            });
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            CompletableFuture<String> queued = coordinator.submit("queued", () -> "queued");

            PendingOperationsSnapshot snapshot = assertTimeoutPreemptively(
                    Duration.ofSeconds(2), adapter::pendingOperations);
            PendingOperationsSnapshot.Operation running = require(snapshot, item ->
                    item.kind() == PendingOperationsSnapshot.Kind.ARTIFACT_DRAIN
                            && item.waitsFor() == PendingOperationsSnapshot.WaitType.COORDINATOR
                            && item.targetId().endsWith("/blocked"));
            assertTrue(running.targetId().startsWith("pf4j-adapter-"), running.targetId());
            assertTrue(running.detail().contains("state=running"), running.detail());
            PendingOperationsSnapshot.Operation waiting = require(snapshot, item ->
                    item.kind() == PendingOperationsSnapshot.Kind.ARTIFACT_DRAIN
                            && item.waitsFor() == PendingOperationsSnapshot.WaitType.COORDINATOR
                            && item.targetId().endsWith("/queued"));
            assertTrue(waiting.detail().contains("state=queued"), waiting.detail());
            assertPureDto(snapshot);

            clock.incrementAndGet();
            assertEquals(Duration.ofNanos(1), require(adapter.pendingOperations(), item ->
                    item.kind() == PendingOperationsSnapshot.Kind.ARTIFACT_DRAIN
                            && item.targetId().endsWith("/blocked")).age());

            release.complete(null);
            blocked.get(10, TimeUnit.SECONDS);
            assertEquals("queued", queued.get(10, TimeUnit.SECONDS));
            awaitNoOperations(adapter, item ->
                    item.kind() == PendingOperationsSnapshot.Kind.ARTIFACT_DRAIN
                            && item.targetId().contains("/"));
        }
    }

    @Test
    void coordinatorMonitorDeduplicatesAgainstTrackedDrainRecords(@TempDir Path pluginsRoot) throws Exception {
        try (KnotraRuntime runtime = KnotraRuntime.create();
             Pf4jArtifactAdapter adapter = adapter(pluginsRoot, runtime, new AtomicLong())) {
            adapter.loadArtifactAsync(pluginsRoot.resolve("plugin.jar")).toCompletableFuture().join();

            CountDownLatch entered = new CountDownLatch(1);
            CompletableFuture<Void> release = new CompletableFuture<>();
            CompletableFuture<Void> blocked = coordinator.submit("blocked", () -> {
                entered.countDown();
                return release.join();
            });
            assertTrue(entered.await(10, TimeUnit.SECONDS));

            CompletableFuture<Void> unload = adapter.unloadArtifactAsync(ARTIFACT_ID)
                    .toCompletableFuture();
            PendingOperationsSnapshot snapshot = awaitOperation(adapter, item ->
                    item.kind() == PendingOperationsSnapshot.Kind.ARTIFACT_DRAIN
                            && item.targetId().equals(ARTIFACT_ID));
            PendingOperationsSnapshot.Operation drain = require(snapshot, item ->
                    item.kind() == PendingOperationsSnapshot.Kind.ARTIFACT_DRAIN
                            && item.targetId().equals(ARTIFACT_ID));
            assertEquals(PendingOperationsSnapshot.WaitType.COORDINATOR, drain.waitsFor());
            assertTrue(drain.detail().contains("phase=schedule-coordinator"), drain.detail());
            // drain 自身的协调器等待与 monitor 记录不得对同一操作重复上报。
            assertEquals(1, snapshot.operations().stream()
                    .filter(item -> item.targetId().equals(ARTIFACT_ID)).count());
            assertEquals(1, snapshot.operations().stream()
                    .filter(item -> item.targetId().endsWith("/blocked")).count());

            release.complete(null);
            blocked.get(10, TimeUnit.SECONDS);
            unload.get(20, TimeUnit.SECONDS);
            awaitNoOperations(adapter, item ->
                    item.kind() == PendingOperationsSnapshot.Kind.ARTIFACT_DRAIN
                            && item.targetId().equals(ARTIFACT_ID));
        }
    }

    @Test
    void drainReportsQueuedStopUnloadWhileCoordinatorIsBlocked(@TempDir Path pluginsRoot) throws Exception {
        AtomicLong clock = new AtomicLong(-10);
        try (KnotraRuntime runtime = KnotraRuntime.create();
             Pf4jArtifactAdapter adapter = adapter(pluginsRoot, runtime, clock)) {
            adapter.loadArtifactAsync(pluginsRoot.resolve("plugin.jar")).toCompletableFuture().join();
            MountHandle handle = adapter.factories()
                    .resolveNoConfig("async-cleanup").orElseThrow()
                    .mount(runtime.root(), "queued-stop-unload");
            handle.whenSettled().toCompletableFuture().get(10, TimeUnit.SECONDS);
            ControlledGate gate = runtime.root().view().require(
                    io.knotra.CapabilityKey.of("knotra-pf4j-test-gate", ControlledGate.class));

            CompletableFuture<Void> unload = adapter.unloadArtifactAsync(ARTIFACT_ID)
                    .toCompletableFuture();
            awaitOperation(adapter, item ->
                    item.kind() == PendingOperationsSnapshot.Kind.ARTIFACT_DRAIN
                            && item.detail().contains("phase=dispose-roots"));

            // 组件清理期间协调器空闲；先占住它，释放组件门后 stop-unload 只能排队。
            CountDownLatch entered = new CountDownLatch(1);
            CompletableFuture<Void> release = new CompletableFuture<>();
            CompletableFuture<Void> blocked = coordinator.submit("blocked", () -> {
                entered.countDown();
                return release.join();
            });
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            gate.release();

            PendingOperationsSnapshot snapshot = awaitOperation(adapter, item ->
                    item.kind() == PendingOperationsSnapshot.Kind.ARTIFACT_DRAIN
                            && item.targetId().equals(ARTIFACT_ID)
                            && item.waitsFor() == PendingOperationsSnapshot.WaitType.COORDINATOR
                            && item.detail().contains("phase=schedule-stop-unload"));
            PendingOperationsSnapshot.Operation drain = require(snapshot, item ->
                    item.kind() == PendingOperationsSnapshot.Kind.ARTIFACT_DRAIN
                            && item.targetId().equals(ARTIFACT_ID));
            assertEquals(PendingOperationsSnapshot.WaitType.COORDINATOR, drain.waitsFor());
            assertTrue(drain.detail().contains("phase=schedule-stop-unload"), drain.detail());
            assertEquals(1, snapshot.operations().stream()
                    .filter(item -> item.targetId().equals(ARTIFACT_ID)).count());
            assertPureDto(snapshot);

            release.complete(null);
            blocked.get(10, TimeUnit.SECONDS);
            unload.get(20, TimeUnit.SECONDS);
            awaitNoOperations(adapter, item ->
                    item.kind() == PendingOperationsSnapshot.Kind.ARTIFACT_DRAIN
                            && item.targetId().equals(ARTIFACT_ID));
        }
    }

    @Test
    void closeDoesNotClearStillPendingRetryDrainRecords(@TempDir Path pluginsRoot) throws Exception {
        try (KnotraRuntime runtime = KnotraRuntime.create();
             Pf4jArtifactAdapter adapter = adapter(pluginsRoot, runtime, new AtomicLong());
             ExecutorService executor = Executors.newFixedThreadPool(4)) {
            adapter.loadArtifactAsync(pluginsRoot.resolve("plugin.jar")).toCompletableFuture().join();
            MountHandle handle = adapter.factories()
                    .resolveNoConfig("async-cleanup").orElseThrow()
                    .mount(runtime.root(), "close-retry-root");
            handle.whenSettled().toCompletableFuture().get(10, TimeUnit.SECONDS);
            ControlledGate gate = runtime.root().view().require(
                    io.knotra.CapabilityKey.of("knotra-pf4j-test-gate", ControlledGate.class));

            CompletableFuture<Void> close = adapter.closeAsync().toCompletableFuture();
            awaitOperation(adapter, item ->
                    item.kind() == PendingOperationsSnapshot.Kind.ARTIFACT_DRAIN
                            && item.targetId().equals(ARTIFACT_ID)
                            && item.detail().contains("phase=dispose-roots"));

            // close 排空期间并发 retry 复用同一 drain；每条记录只随自己的 result 清理。
            List<CompletableFuture<Void>> retries = new ArrayList<>();
            for (int index = 0; index < 3; index++) {
                retries.add(CompletableFuture.supplyAsync(
                                () -> adapter.retryDrainAsync(ARTIFACT_ID).toCompletableFuture(), executor)
                        .thenCompose(Function.identity()));
            }
            awaitOperationCount(adapter, item ->
                    item.kind() == PendingOperationsSnapshot.Kind.ARTIFACT_DRAIN
                            && item.targetId().equals(ARTIFACT_ID), 4);

            PendingOperationsSnapshot blocked = adapter.pendingOperations();
            assertTrue(blocked.closeRequested());
            assertEquals(1, blocked.operations().stream()
                    .filter(item -> item.kind() == PendingOperationsSnapshot.Kind.ARTIFACT_DRAIN
                            && item.targetId().equals(ARTIFACT_ID)
                            && item.waitsFor() == PendingOperationsSnapshot.WaitType.COMPONENT)
                    .count());
            assertEquals(3, blocked.operations().stream()
                    .filter(item -> item.kind() == PendingOperationsSnapshot.Kind.ARTIFACT_DRAIN
                            && item.targetId().equals(ARTIFACT_ID)
                            && item.waitsFor() == PendingOperationsSnapshot.WaitType.COORDINATOR)
                    .count());

            gate.release();
            close.get(20, TimeUnit.SECONDS);
            for (CompletableFuture<Void> retry : retries) {
                retry.get(10, TimeUnit.SECONDS);
            }
            awaitNoOperations(adapter, item ->
                    item.kind() == PendingOperationsSnapshot.Kind.ARTIFACT_DRAIN
                            && item.targetId().equals(ARTIFACT_ID));
        }
    }

    @Test
    void pendingSnapshotDoesNotPreventPluginClassLoaderCollection(@TempDir Path pluginsRoot) throws Exception {
        ReferenceVault.clear();
        AtomicLong clock = new AtomicLong(-10);
        try (KnotraRuntime runtime = KnotraRuntime.create();
             Pf4jArtifactAdapter adapter = adapter(pluginsRoot, runtime, clock)) {
            adapter.loadArtifactAsync(pluginsRoot.resolve("plugin.jar")).toCompletableFuture().join();
            var handle = adapter.factories()
                    .resolve("alpha", String.class).orElseThrow()
                    .mount(runtime.root(), "gc-root", "value");
            handle.whenSettled().toCompletableFuture().get(10, TimeUnit.SECONDS);
            pluginManager.blockNextUnload();
            CompletableFuture<Void> unload = adapter.unloadArtifactAsync(ARTIFACT_ID)
                    .toCompletableFuture();
            assertTrue(pluginManager.enteredUnload().await(10, TimeUnit.SECONDS));
            PendingOperationsSnapshot snapshot = adapter.pendingOperations();
            assertPureDto(snapshot);
            pluginManager.releaseGate();
            unload.get(20, TimeUnit.SECONDS);
        }
        assertCollectorsReachZero();
    }

    private Pf4jArtifactAdapter adapter(
            Path pluginsRoot,
            KnotraRuntime runtime,
            AtomicLong clock) throws Exception {
        Files.copy(fixture, pluginsRoot.resolve("plugin.jar"));
        KnotraClassLoaderPolicy policy = KnotraClassLoaderPolicy.forHost(SHARED_CONTRACTS);
        coordinator = new ArtifactCoordinator(clock::get);
        pluginManager = new ControlledPluginManager(pluginsRoot, policy);
        return new DefaultPf4jArtifactAdapter(
                pluginManager, coordinator, runtime, policy, clock::get);
    }

    private static PendingOperationsSnapshot.Operation require(
            PendingOperationsSnapshot snapshot,
            Predicate<PendingOperationsSnapshot.Operation> filter) {
        return snapshot.operations().stream()
                .filter(filter)
                .findFirst()
                .orElseThrow(() -> new AssertionError(snapshot.render()));
}

    private static PendingOperationsSnapshot awaitOperation(
            Pf4jArtifactAdapter adapter,
            Predicate<PendingOperationsSnapshot.Operation> filter) throws Exception {
        for (int attempt = 0; attempt < 1_000; attempt++) {
            PendingOperationsSnapshot snapshot = adapter.pendingOperations();
            if (snapshot.operations().stream().anyMatch(filter)) {
                return snapshot;
            }
            Thread.sleep(10);
        }
        throw new AssertionError(adapter.pendingOperations().render());
    }

    private static void awaitNoOperations(
            Pf4jArtifactAdapter adapter,
            Predicate<PendingOperationsSnapshot.Operation> filter) throws Exception {
        for (int attempt = 0; attempt < 1_000; attempt++) {
            if (adapter.pendingOperations().operations().stream().noneMatch(filter)) {
                return;
            }
            Thread.sleep(10);
        }
        fail(adapter.pendingOperations().render());
    }

    private static void awaitOperationCount(
            Pf4jArtifactAdapter adapter,
            Predicate<PendingOperationsSnapshot.Operation> filter,
            int expected) throws Exception {
        for (int attempt = 0; attempt < 1_000; attempt++) {
            if (adapter.pendingOperations().operations().stream().filter(filter).count() == expected) {
                return;
            }
            Thread.sleep(10);
        }
        fail(adapter.pendingOperations().render());
    }

    private static void assertPureDto(PendingOperationsSnapshot snapshot) {
        for (PendingOperationsSnapshot.Operation operation : snapshot.operations()) {
            assertNotNull(operation.targetId());
            assertNotNull(operation.waitsFor());
            assertNotNull(operation.kind());
            assertNotNull(operation.age());
            assertNotNull(operation.detail());
            assertFalse(operation.detail().contains("ClassLoader"), operation.detail());
            assertFalse(operation.detail().contains("java.lang."), operation.detail());
        }
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

    private static final class ControlledPluginManager extends DefaultPluginManager {
        private final CountDownLatch enteredUnload = new CountDownLatch(1);
        private volatile CompletableFuture<Void> gate = new CompletableFuture<>();
        private volatile boolean gateEnabled;

        private ControlledPluginManager(Path pluginsRoot, KnotraClassLoaderPolicy policy) {
            super(pluginsRoot);
            this.pluginLoader = new SharedContractPluginLoader(this, policy);
        }

        @Override
        public boolean unloadPlugin(String pluginId) {
            if (gateEnabled && ARTIFACT_ID.equals(pluginId)) {
                gateEnabled = false;
                enteredUnload.countDown();
                gate.join();
            }
            return super.unloadPlugin(pluginId);
        }

        private void blockNextUnload() {
            gateEnabled = true;
        }
        private CountDownLatch enteredUnload() {
            return enteredUnload;
        }

        private void releaseGate() {
            CompletableFuture<Void> current = gate;
            gate = new CompletableFuture<>();
            current.complete(null);
        }
    }
}
