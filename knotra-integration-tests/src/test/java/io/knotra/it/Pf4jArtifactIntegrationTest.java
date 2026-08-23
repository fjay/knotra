package io.knotra.it;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import io.knotra.ComponentOrigin;
import io.knotra.ComponentState;
import io.knotra.ConfiguredMountHandle;
import io.knotra.KnotraRuntime;
import io.knotra.MountHandle;
import io.knotra.RuntimeSnapshot;
import io.knotra.pf4j.ArtifactFactoryCatalogEntry;
import io.knotra.pf4j.ArtifactFactoryHandle;
import io.knotra.pf4j.ArtifactState;
import io.knotra.pf4j.Pf4jArtifactAdapter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

final class Pf4jArtifactIntegrationTest {

    @RegisterExtension
    private final KnotraIntegrationExtension runtimeExtension =
            KnotraIntegrationExtension.defaults();

    @Test
    void loadStartDiscoversControlledFactoriesWithoutMountingMounts(
            KnotraRuntime runtime, @TempDir Path pluginsRoot)
            throws Exception {
        try (Pf4jArtifactAdapter adapter = IntegrationTestKit.adapter(pluginsRoot, runtime)) {
            var snapshot = adapter.loadArtifactAsync(IntegrationTestKit.fixture()).toCompletableFuture().join();

            assertEquals(IntegrationTestKit.ARTIFACT_ID, snapshot.artifactId());
            assertEquals(ArtifactState.ACTIVE, snapshot.state());
            assertEquals("STARTED", snapshot.pf4jState());
            assertTrue(runtime.advanced().snapshot().mounts().isEmpty(),
                    "artifact load/start must never mount components implicitly");
            assertEquals(
                    List.of(
                            "integration-event-consumer",
                            "integration-failing-cleanup",
                            "integration-failing-start",
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
            assertTrue(adapter.factories().resolve("integration-greeting").orElseThrow()
                    instanceof ArtifactFactoryHandle.Configured<?>);
            assertTrue(adapter.factories().resolveNoConfig("integration-parent").isPresent());
            assertTrue(adapter.factories().resolve("absent-factory").isEmpty());
            assertTrue(runtime.advanced().snapshot().registrations().isEmpty());
        }
    }

    @Test
    void controlledMountNormalizesConfigAndKeepsArtifactOrigin(
            KnotraRuntime runtime, @TempDir Path pluginsRoot)
            throws Exception {
        try (Pf4jArtifactAdapter adapter = IntegrationTestKit.adapter(pluginsRoot, runtime)) {
            adapter.loadArtifactAsync(IntegrationTestKit.fixture()).toCompletableFuture().join();
            ArtifactFactoryHandle.Configured<String> factory = adapter.factories()
                    .resolve("integration-greeting", String.class).orElseThrow();

            ConfiguredMountHandle<String> handle = factory.mount(
                    runtime.root(), "greeting", "  hello  ");
            assertEquals(ComponentState.ACTIVE, handle.awaitSettled(Duration.ofSeconds(30)));
            assertTrue(handle instanceof ConfiguredMountHandle<?>);

            RuntimeSnapshot.MountSnapshot mount = runtime.advanced().snapshot().mounts()
                    .stream()
                    .filter(item -> item.handleId().equals(handle.handleId()))
                    .findFirst().orElseThrow();
            assertEquals(ComponentOrigin.Kind.ARTIFACT, mount.origin().kind());
            assertEquals(IntegrationTestKit.ARTIFACT_ID, mount.origin().sourceId());
            assertEquals("1.0.0", mount.origin().version());
            assertEquals("greeting", mount.mountId());
            assertEquals("hello", runtime.root().view().require(IntegrationTestKit.VALUE));
            assertEquals(1, adapter.ownership(IntegrationTestKit.ARTIFACT_ID).size());
            assertEquals("decoded", factory.decodeConfig("decoded"));

            adapter.unloadArtifactAsync(IntegrationTestKit.ARTIFACT_ID).toCompletableFuture().join();
            assertTrue(runtime.advanced().snapshot().mounts().isEmpty());
            assertTrue(adapter.ownership(IntegrationTestKit.ARTIFACT_ID).isEmpty());
        }
    }

    @Test
    void artifactChildMountInheritsParentOriginAndDrainsWithTheArtifact(
            KnotraRuntime runtime,
            @TempDir Path pluginsRoot) throws Exception {
        try (Pf4jArtifactAdapter adapter = IntegrationTestKit.adapter(pluginsRoot, runtime)) {
            adapter.loadArtifactAsync(IntegrationTestKit.fixture()).toCompletableFuture().join();
            MountHandle parent = adapter.factories()
                    .resolveNoConfig("integration-parent").orElseThrow()
                    .mount(runtime.root(), "parent");
            assertEquals(ComponentState.ACTIVE, parent.awaitSettled(Duration.ofSeconds(30)));
            assertFalse(parent instanceof ConfiguredMountHandle<?>);

            RuntimeSnapshot.MountSnapshot child = runtime.advanced().snapshot().mounts().stream()
                    .filter(item -> item.mountId().equals("integration-child"))
                    .findFirst().orElseThrow();
            assertEquals(ComponentOrigin.Kind.ARTIFACT, child.origin().kind());
            assertEquals(IntegrationTestKit.ARTIFACT_ID, child.origin().sourceId());
            assertEquals(parent.handleId(), child.parentHandleId());
            assertEquals(2, adapter.ownership(IntegrationTestKit.ARTIFACT_ID).size());

            adapter.unloadArtifactAsync(IntegrationTestKit.ARTIFACT_ID).toCompletableFuture().join();
            assertTrue(runtime.advanced().snapshot().mounts().isEmpty());
        }
    }
}
