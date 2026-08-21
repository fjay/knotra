package io.knotra.it;

import java.nio.file.Path;
import java.util.List;

import com.example.integration.contract.IntegrationCoordinator;
import io.knotra.ComponentHandle;
import io.knotra.ComponentState;
import io.knotra.KnotraRuntime;
import io.knotra.NoConfig;
import io.knotra.RuntimeSnapshot;
import io.knotra.events.EventBus;
import io.knotra.events.EventBusFactory;
import io.knotra.events.EventBusSnapshot;
import io.knotra.loader.ComponentEntry;
import io.knotra.loader.ComponentTree;
import io.knotra.loader.CompositeComponentFactoryResolver;
import io.knotra.loader.FactoryRef;
import io.knotra.loader.KnotraLoader;
import io.knotra.loader.LoaderSnapshot;
import io.knotra.loader.ReconcileResult;
import io.knotra.pf4j.ArtifactSnapshot;
import io.knotra.pf4j.ArtifactState;
import io.knotra.pf4j.Pf4jArtifactAdapter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

final class SnapshotClassLoaderIntegrationTest {

    private KnotraRuntime runtime;

    @BeforeEach
    void setUp() {
        IntegrationCoordinator.reset();
        IntegrationCoordinator.clearLoaders();
        runtime = KnotraRuntime.create();
    }

    @AfterEach
    void tearDown() throws Exception {
        IntegrationTestKit.drainIntegrations();
        runtime.close();
    }

    @Test
    void retainedSnapshotsDoNotPinThePluginClassLoader(@TempDir Path pluginsRoot)
            throws Exception {
        try (Pf4jArtifactAdapter adapter = IntegrationTestKit.adapter(pluginsRoot, runtime)) {
            adapter.loadArtifact(IntegrationTestKit.fixture()).join();
            ComponentHandle<String> greeting = adapter.resolver()
                    .resolve("integration-greeting", String.class).orElseThrow()
                    .mount(runtime.rootContext(), "greeting", "hello");
            ComponentHandle<NoConfig> parent = adapter.resolver()
                    .resolve("integration-parent", NoConfig.class).orElseThrow()
                    .mount(runtime.rootContext(), "parent", NoConfig.INSTANCE);
            assertEquals(ComponentState.ACTIVE, IntegrationTestKit.settle(greeting));
            assertEquals(ComponentState.ACTIVE, IntegrationTestKit.settle(parent));

            ComponentHandle<NoConfig> busProvider = runtime.mutate(mutation -> mutation.mount(
                    runtime.rootContext(), "bus", new EventBusFactory(), NoConfig.INSTANCE))
                    .value();
            assertEquals(ComponentState.ACTIVE, IntegrationTestKit.settle(busProvider));
            EventBus bus = runtime.context()
                    .find(io.knotra.events.EventCapabilities.EVENT_BUS).orElseThrow();

            KnotraLoader loader = KnotraLoader.over(
                    runtime,
                    runtime.rootContext(),
                    CompositeComponentFactoryResolver.of(ref ->
                            IntegrationTestKit.bridge(adapter).apply(ref)));
            ReconcileResult reconcile = loader.reconcile(ComponentTree.of(
                    ComponentEntry.of(
                            "snapshot-entry",
                            FactoryRef.of("integration-greeting"),
                            "snapshot")));
            assertTrue(reconcile.converged(), () -> reconcile.diagnostics().toString());
            loader.close();

            RuntimeSnapshot runtimeSnapshot = runtime.snapshot();
            ArtifactSnapshot artifactSnapshot = adapter.artifact(
                    IntegrationTestKit.ARTIFACT_ID).orElseThrow();
            EventBusSnapshot busSnapshot = bus.snapshot();
            LoaderSnapshot loaderSnapshot = loader.snapshot();

            adapter.unloadArtifact(IntegrationTestKit.ARTIFACT_ID).join();
            busProvider.dispose().toCompletableFuture().get(10, java.util.concurrent.TimeUnit
                    .SECONDS);
            assertEquals(ArtifactState.UNLOADED, adapter.artifact(
                    IntegrationTestKit.ARTIFACT_ID).orElseThrow().state());

            List<?> retained = List.of(
                    runtimeSnapshot, artifactSnapshot, busSnapshot, loaderSnapshot);
            assertEquals(4, retained.size());
            assertTrue(artifactSnapshot.artifactId().equals(IntegrationTestKit.ARTIFACT_ID));
            assertFalse(busSnapshot.closed());

            await().atMost(java.time.Duration.ofSeconds(30))
                    .pollInterval(java.time.Duration.ofMillis(50))
                    .untilAsserted(() -> {
                        System.gc();
                        assertEquals(0, IntegrationCoordinator.liveLoaders(),
                                "snapshots must not retain the plugin class loader");
                    });
        } finally {
            IntegrationCoordinator.clearLoaders();
        }
    }
}
