package io.knotra.it;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.example.integration.contract.IntegrationCoordinator;
import io.knotra.ComponentState;
import io.knotra.ConfiguredMountHandle;
import io.knotra.KnotraRuntime;
import io.knotra.MountHandle;
import io.knotra.Publication;
import io.knotra.PublicationChange;
import io.knotra.PublicationState;
import io.knotra.SettlementReport;
import io.knotra.events.EventBus;
import io.knotra.events.EventBusFactory;
import io.knotra.events.EventBusSnapshot;
import io.knotra.loader.KnotraLoader;
import io.knotra.loader.LoaderSnapshot;
import io.knotra.pf4j.ArtifactSnapshot;
import io.knotra.pf4j.ArtifactState;
import io.knotra.pf4j.Pf4jArtifactAdapter;
import io.knotra.RuntimeSnapshot;
import io.knotra.FailureInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

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
            unpublished.awaitSettled(Duration.ofSeconds(10));
            assertEquals(PublicationState.UNPUBLISHED, publication.state());
            assertTrue(publishReport.generation() < updateReport.generation());
            assertTrue(unpublished.generation() > updateReport.generation());
            assertTrue(runtime.root().view().find(String.class).isEmpty());

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
            denyingClasses.assertPure(
                    runtimeSnapshot,
                    artifactSnapshot,
                    loaderSnapshot,
                    busSnapshot,
                    closedBusSnapshot,
                    List.of(publishReport, updateReport),
                    List.of(pluginFailureInfo));
            RetainedGraphScanner.allowingNonPluginClasses(PLUGIN_PACKAGE)
                    .assertPure(unpublished);
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
                    });
        } finally {
            IntegrationCoordinator.clearLoaders();
        }
    }
}
