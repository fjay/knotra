package io.knotra.it;

import java.lang.ref.Reference;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import com.example.integration.contract.IntegrationCoordinator;
import io.knotra.ComponentDescriptor;
import io.knotra.ComponentState;
import io.knotra.ConfiguredMountHandle;
import io.knotra.KnotraRuntime;
import io.knotra.MountFactory;
import io.knotra.MountHandle;
import io.knotra.Publication;
import io.knotra.PublicationChange;
import io.knotra.PublicationState;
import io.knotra.SettlementReport;
import io.knotra.events.EventBus;
import io.knotra.events.EventBusFactory;
import io.knotra.events.EventBusSnapshot;
import io.knotra.loader.ComponentEntry;
import io.knotra.loader.ComponentTree;
import io.knotra.loader.KnotraLoader;
import io.knotra.loader.ReconcileResult;
import io.knotra.loader.LoaderSnapshot;
import io.knotra.pf4j.ArtifactSnapshot;
import io.knotra.pf4j.ArtifactState;
import io.knotra.pf4j.Pf4jArtifactAdapter;
import io.knotra.RuntimeSnapshot;
import io.knotra.FailureInfo;
import io.knotra.PendingOperationsSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

@org.junit.jupiter.api.parallel.ResourceLock(IntegrationTestKit.INTEGRATION_COORDINATOR_LOCK)
final class SnapshotClassLoaderIntegrationTest {

    private static final String PLUGIN_PACKAGE = "com.example.integration.plugin.";

    @RegisterExtension
    private final KnotraIntegrationExtension runtimeExtension =
            KnotraIntegrationExtension.withConfig(SnapshotClassLoaderFixture::config);

    @Test
    void retainedStableGraphsDoNotPinThePluginClassLoaderAfterExplicitTeardown(
            KnotraRuntime runtime,
            @TempDir Path pluginsRoot) throws Exception {
        try (Pf4jArtifactAdapter adapter =
                     SnapshotClassLoaderFixture.loadAdapter(pluginsRoot, runtime)) {
            ConfiguredMountHandle<String> greeting =
                    SnapshotClassLoaderFixture.mountGreeting(adapter, runtime);
            MountHandle parent = SnapshotClassLoaderFixture.mountParent(adapter, runtime);
            MountHandle busProvider = runtime.mount("bus", new EventBusFactory());
            assertEquals(ComponentState.ACTIVE, greeting.awaitSettled(Duration.ofSeconds(30)));
            assertEquals(ComponentState.ACTIVE, parent.awaitSettled(Duration.ofSeconds(30)));
            assertEquals(ComponentState.ACTIVE, busProvider.awaitSettled(Duration.ofSeconds(30)));
            EventBus bus = runtime.root().view().require(io.knotra.events.EventCapabilities.EVENT_BUS);

            KnotraLoader loader = SnapshotClassLoaderFixture.loader(adapter, runtime);
            SnapshotClassLoaderFixture.reconcileGreeting(loader);

            MountHandle pluginFailure =
                    SnapshotClassLoaderFixture.mountFailingStart(adapter, runtime);
            assertEquals(ComponentState.FAILED, pluginFailure.awaitSettled(Duration.ofSeconds(30)));

            PublicationChange<String> published = runtime.publish(String.class, "one");
            SettlementReport publishReport = published.awaitSettled(Duration.ofSeconds(10));
            Publication<String> publication = published.publication();
            PublicationChange<String> updated = publication.update("two");
            SettlementReport updateReport = updated.awaitSettled(Duration.ofSeconds(10));
            PublicationChange<String> unpublished = publication.unpublish();
            SettlementReport unpublishedReport = unpublished.awaitSettled(Duration.ofSeconds(10));
            assertEquals(PublicationState.UNPUBLISHED, publication.state());
            assertTrue(publishReport.generation() < updateReport.generation());
            assertTrue(unpublished.generation() > updateReport.generation());
            assertTrue(runtime.root().view().find(String.class).isEmpty());

            // 阻塞期间采集四份 pending DTO：释放、卸载与 close 之后继续持有它们。
            // 插件工厂在 create() 阻塞覆盖 loader/adapter；宿主工厂在 start() 阻塞覆盖 core。
            CompletableFuture<ReconcileResult> gatedReconcile = loader.reconcileAsync(
                            ComponentTree.of(ComponentEntry.of(
                                    "gated", SnapshotClassLoaderFixture.IN_FLIGHT)))
                    .toCompletableFuture();
            IntegrationCoordinator.mountEntered().get(10, TimeUnit.SECONDS);
            CountDownLatch startEntered = new CountDownLatch(1);
            CompletableFuture<Void> startGate = new CompletableFuture<>();
            MountHandle pendingCapture = runtime.mount("pending-capture", MountFactory.of(
                    "pending-capture",
                    ComponentDescriptor.named("pending-capture"),
                    context -> {
                        startEntered.countDown();
                        startGate.join();
                    }));
            assertTrue(startEntered.await(10, TimeUnit.SECONDS));
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                requireOperation(loader.pendingOperations(), item ->
                        item.kind() == PendingOperationsSnapshot.Kind.LOADER_OPERATION
                                && item.targetId().equals("gated"));
                requireOperation(adapter.pendingOperations(), item ->
                        item.kind() == PendingOperationsSnapshot.Kind.ARTIFACT_MOUNT
                                && item.targetId().equals(IntegrationTestKit.ARTIFACT_ID));
                requireOperation(runtime.advanced().pendingOperations(), item ->
                        item.kind() == PendingOperationsSnapshot.Kind.COMPONENT_TRANSITION
                                && item.targetId().equals(pendingCapture.handleId()));
            });
            PendingOperationsSnapshot[] retainedPending = {
                    assertTimeout(
                            Duration.ofSeconds(1), () -> runtime.advanced().pendingOperations()),
                    assertTimeout(
                            Duration.ofSeconds(1), loader::pendingOperations),
                    assertTimeout(
                            Duration.ofSeconds(1), adapter::pendingOperations),
                    assertTimeout(
                            Duration.ofSeconds(1), bus::pendingOperations)
            };

            startGate.complete(null);
            assertEquals(ComponentState.ACTIVE,
                    pendingCapture.awaitSettled(Duration.ofSeconds(30)));
            assertEquals(ComponentState.DISPOSED, pendingCapture.disposeAsync()
                    .toCompletableFuture().get(10, TimeUnit.SECONDS));
            IntegrationCoordinator.releaseMount();
            ReconcileResult gatedResult = gatedReconcile.get(30, TimeUnit.SECONDS);
            assertTrue(gatedResult.converged(), () -> gatedResult.diagnostics().toString());

            RuntimeSnapshot runtimeSnapshot = runtime.advanced().snapshot();
            LoaderSnapshot loaderSnapshot = loader.snapshot();
            EventBusSnapshot busSnapshot = bus.snapshot();
            FailureInfo pluginFailureInfo =
                    SnapshotClassLoaderFixture.failureInfo(runtime, pluginFailure);
            assertEquals("java.lang.IllegalStateException", pluginFailureInfo.exceptionType());
            assertEquals("intentional plugin activation failure", pluginFailureInfo.message());
            assertFalse(pluginFailureInfo.stackTrace().isEmpty());
            assertTrue(pluginFailureInfo.stackTrace().stream()
                    .anyMatch(frame -> frame.contains(
                            "com.example.integration.plugin.IntegrationRuntimeComponentProvider")),
                    () -> pluginFailureInfo.stackTrace().toString());

            loader.close();
            adapter.unloadArtifactAsync(IntegrationTestKit.ARTIFACT_ID)
                    .toCompletableFuture().get(30, TimeUnit.SECONDS);
            busProvider.disposeAsync().toCompletableFuture().get(10, TimeUnit.SECONDS);
            EventBusSnapshot closedBusSnapshot = bus.snapshot();
            assertTrue(closedBusSnapshot.closed());
            assertEquals(ArtifactState.UNLOADED, adapter.artifact(
                    IntegrationTestKit.ARTIFACT_ID).orElseThrow().state());
            assertTrue(runtime.advanced().snapshot().mounts().isEmpty());
            ArtifactSnapshot artifactSnapshot = adapter.artifact(
                    IntegrationTestKit.ARTIFACT_ID).orElseThrow();
            runtime.close();

            RetainedGraphScanner denyingClasses =
                    RetainedGraphScanner.denyingClasses(PLUGIN_PACKAGE);
            // Scanner 先扫，GC 断言之后仍要读取同一批 DTO，消除 JIT 局部死亡假阳性。
            denyingClasses.assertPure(
                    runtimeSnapshot,
                    artifactSnapshot,
                    loaderSnapshot,
                    busSnapshot,
                    closedBusSnapshot,
                    retainedPending[0],
                    retainedPending[1],
                    retainedPending[2],
                    retainedPending[3],
                    List.of(publishReport, updateReport),
                    List.of(pluginFailureInfo));
            RetainedGraphScanner.allowingNonPluginClasses(PLUGIN_PACKAGE)
                    .assertPure(unpublishedReport);
            assertTrue(runtimeSnapshot.mounts().stream()
                    .anyMatch(mount -> mount.mountId().equals("plugin-failure")));
            assertEquals(IntegrationTestKit.ARTIFACT_ID, artifactSnapshot.artifactId());
            assertFalse(busSnapshot.closed(), "the pre-dispose snapshot is immutable");
            assertTrue(closedBusSnapshot.closed(), "the post-dispose snapshot records closure");

            greeting = null;
            parent = null;
            busProvider = null;
            bus = null;

            await().atMost(Duration.ofSeconds(30))
                    .pollInterval(Duration.ofMillis(50))
                    .untilAsserted(() -> {
                        System.gc();
                        assertEquals(0, IntegrationCoordinator.liveLoaders(),
                                "retained stable DTOs must not retain the plugin class loader");
                        Reference.reachabilityFence(retainedPending);
                    });
            for (PendingOperationsSnapshot snapshot : retainedPending) {
                assertFalse(snapshot.render().isBlank(), snapshot::render);
                assertNotNull(snapshot.operations(), snapshot::render);
            }
            Reference.reachabilityFence(retainedPending);
        } finally {
            IntegrationCoordinator.clearLoaders();
        }
    }

    private static PendingOperationsSnapshot.Operation requireOperation(
            PendingOperationsSnapshot snapshot,
            Predicate<PendingOperationsSnapshot.Operation> filter) {
        return snapshot.operations().stream()
                .filter(filter)
                .findFirst()
                .orElseThrow(() -> new AssertionError(snapshot.render()));
    }
}
