package io.knotra.it;

import java.nio.file.Path;
import java.util.List;

import com.example.integration.contract.IntegrationCoordinator;
import io.knotra.ComponentHandle;
import io.knotra.ComponentOrigin;
import io.knotra.ComponentState;
import io.knotra.KnotraRuntime;
import io.knotra.NoConfig;
import io.knotra.RuntimeSnapshot;
import io.knotra.pf4j.ArtifactFactoryCatalogEntry;
import io.knotra.pf4j.ArtifactFactoryHandle;
import io.knotra.pf4j.ArtifactState;
import io.knotra.pf4j.Pf4jArtifactAdapter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

final class Pf4jArtifactIntegrationTest {

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
    void loadStartDiscoversControlledFactoriesWithoutMountingComponents(
            @TempDir Path pluginsRoot) throws Exception {
        try (Pf4jArtifactAdapter adapter = IntegrationTestKit.adapter(pluginsRoot, runtime)) {
            var snapshot = adapter.loadArtifactAsync(IntegrationTestKit.fixture()).toCompletableFuture().join();

            assertEquals(IntegrationTestKit.ARTIFACT_ID, snapshot.artifactId());
            assertEquals(ArtifactState.ACTIVE, snapshot.state());
            assertEquals("STARTED", snapshot.pf4jState());
            assertTrue(runtime.snapshot().components().isEmpty(),
                    "artifact load/start must never mount components implicitly");
            assertEquals(
                    List.of(
                            "integration-event-consumer",
                            "integration-failing-cleanup",
                            "integration-greeting",
                            "integration-in-flight",
                            "integration-parent"),
                    adapter.factories().list().stream()
                            .map(ArtifactFactoryCatalogEntry::factoryId)
                            .sorted()
                            .toList());
            ArtifactFactoryCatalogEntry greeting = adapter.factories()
                    .find("integration-greeting").orElseThrow();
            assertEquals(String.class.getName(), greeting.configTypeName());
            assertFalse(greeting instanceof ArtifactFactoryHandle<?>);
            assertTrue(adapter.factories().resolve("absent-factory").isEmpty());
            assertTrue(runtime.snapshot().registrations().isEmpty());
        }
    }

    @Test
    void controlledMountNormalizesConfigAndKeepsArtifactOrigin(@TempDir Path pluginsRoot)
            throws Exception {
        try (Pf4jArtifactAdapter adapter = IntegrationTestKit.adapter(pluginsRoot, runtime)) {
            adapter.loadArtifactAsync(IntegrationTestKit.fixture()).toCompletableFuture().join();
            ArtifactFactoryHandle<String> factory = adapter.factories()
                    .resolve("integration-greeting", String.class).orElseThrow();

            ComponentHandle<String> handle = factory.mount(
                    runtime.root(), "greeting", "  hello  ");
            assertEquals(ComponentState.ACTIVE, IntegrationTestKit.settle(handle));

            RuntimeSnapshot.ComponentSnapshot component = runtime.snapshot().components()
                    .stream()
                    .filter(item -> item.handleId().equals(handle.handleId()))
                    .findFirst().orElseThrow();
            assertEquals(ComponentOrigin.Kind.ARTIFACT, component.origin().kind());
            assertEquals(IntegrationTestKit.ARTIFACT_ID, component.origin().sourceId());
            assertEquals("1.0.0", component.origin().version());
            assertEquals("greeting", component.mountId());
            assertEquals("hello", runtime.root().view().require(IntegrationTestKit.VALUE));
            assertEquals(1, adapter.ownership(IntegrationTestKit.ARTIFACT_ID).size());
            assertEquals("decoded", factory.decodeConfig("decoded"));

            adapter.unloadArtifactAsync(IntegrationTestKit.ARTIFACT_ID).toCompletableFuture().join();
            assertTrue(runtime.snapshot().components().isEmpty());
            assertTrue(adapter.ownership(IntegrationTestKit.ARTIFACT_ID).isEmpty());
        }
    }

    @Test
    void artifactChildMountInheritsParentOriginAndDrainsWithTheArtifact(
            @TempDir Path pluginsRoot) throws Exception {
        try (Pf4jArtifactAdapter adapter = IntegrationTestKit.adapter(pluginsRoot, runtime)) {
            adapter.loadArtifactAsync(IntegrationTestKit.fixture()).toCompletableFuture().join();
            ComponentHandle<NoConfig> parent = adapter.factories()
                    .resolve("integration-parent", NoConfig.class).orElseThrow()
                    .mount(runtime.root(), "parent", NoConfig.INSTANCE);
            assertEquals(ComponentState.ACTIVE, IntegrationTestKit.settle(parent));

            RuntimeSnapshot.ComponentSnapshot child = runtime.snapshot().components().stream()
                    .filter(item -> item.mountId().equals("integration-child"))
                    .findFirst().orElseThrow();
            assertEquals(ComponentOrigin.Kind.ARTIFACT, child.origin().kind());
            assertEquals(IntegrationTestKit.ARTIFACT_ID, child.origin().sourceId());
            assertEquals(parent.handleId(), child.parentHandleId());
            assertEquals(2, adapter.ownership(IntegrationTestKit.ARTIFACT_ID).size());

            adapter.unloadArtifactAsync(IntegrationTestKit.ARTIFACT_ID).toCompletableFuture().join();
            assertTrue(runtime.snapshot().components().isEmpty());
        }
    }
}
