package io.knotra.pf4j;

import com.example.knotra.contract.CleanupCoordinator;
import com.example.knotra.contract.ControlledGate;
import com.example.knotra.contract.MountCoordinator;
import com.example.knotra.contract.ReferenceVault;
import io.knotra.CapabilityKey;
import io.knotra.Component;
import io.knotra.ComponentDescriptor;
import io.knotra.ComponentFactory;
import io.knotra.ConfiguredMountHandle;
import io.knotra.MountHandle;
import io.knotra.ComponentOrigin;
import io.knotra.ComponentState;
import io.knotra.KnotraRuntime;
import io.knotra.MountOptions;
import io.knotra.RuntimeSnapshot;
import io.knotra.NoConfig;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.MethodName.class)
final class Pf4jArtifactAdapterTest {
    private static final String ARTIFACT_ID = "knotra-test-plugin";
    private static final String DEPENDENT_ID = "knotra-dependent-plugin";
    private static final CapabilityKey<String> VALUE =
            CapabilityKey.of("knotra-pf4j-test-value", String.class);
    private static final CapabilityKey<ControlledGate> GATE =
            CapabilityKey.of("knotra-pf4j-test-gate", ControlledGate.class);

    private final Path fixture = Path.of(
            "target", "fixtures", "knotra-pf4j-0.1.0-SNAPSHOT-fixture.jar")
            .toAbsolutePath().normalize();
    private final Path dependentFixture = Path.of(
            "target", "fixtures", "knotra-pf4j-0.1.0-SNAPSHOT-dependent-fixture.jar")
            .toAbsolutePath().normalize();
    private final Path startFailureFixture = Path.of(
            "target", "fixtures", "knotra-pf4j-0.1.0-SNAPSHOT-start-failure-fixture.jar")
            .toAbsolutePath().normalize();
    private final Path optionalFixture = Path.of(
            "target", "fixtures", "knotra-pf4j-0.1.0-SNAPSHOT-optional-fixture.jar")
            .toAbsolutePath().normalize();

    @Test
    void pf4jStartPublishesCatalogWithoutMountingComponents(@TempDir Path pluginsRoot) throws Exception {
        try (KnotraRuntime runtime = KnotraRuntime.create();
             Pf4jArtifactAdapter adapter = newAdapter(pluginsRoot, runtime)) {
            ArtifactSnapshot snapshot = adapter.loadArtifactAsync(fixture).toCompletableFuture().join();

            assertEquals(ARTIFACT_ID, snapshot.artifactId());
            assertEquals(ArtifactState.ACTIVE, snapshot.state());
            assertEquals("STARTED", snapshot.pf4jState());
            assertTrue(runtime.advanced().snapshot().mounts().isEmpty());
            assertEquals(
                    List.of("alpha", "beta", "async-cleanup", "in-flight",
                            "lost-race",
                            "private-descriptor", "private-provide", "private-child",
                            "parent", "failing-cleanup"),
                    adapter.factories().list().stream()
                            .map(ArtifactFactoryCatalogEntry::factoryId).toList());
            ArtifactFactoryCatalogEntry metadata = adapter.factories().find("alpha").orElseThrow();
            assertEquals(String.class.getName(), metadata.configTypeName());
            assertEquals(fixture.toString(), metadata.artifactPath());
            assertFalse(metadata instanceof ArtifactFactoryHandle);
            assertInstanceOf(ArtifactFactoryHandle.class,
                    adapter.factories().resolve("alpha").orElseThrow());
            assertFalse(snapshot.classLoaderDescription().isBlank());
        }
    }

    @Test
    void factorySurfaceSplitsNoConfigFromConfiguredMount(@TempDir Path pluginsRoot) throws Exception {
        try (KnotraRuntime runtime = KnotraRuntime.create();
             Pf4jArtifactAdapter adapter = newAdapter(pluginsRoot, runtime)) {
            adapter.loadArtifactAsync(fixture).toCompletableFuture().join();

            assertTrue(java.util.Arrays.stream(ArtifactFactoryHandle.class.getMethods())
                    .noneMatch(method -> method.getName().equals("mount")
                            || method.getName().equals("decodeConfig")));
            assertTrue(adapter.factories().resolveNoConfig("alpha").isEmpty());
            ArtifactFactoryHandle.NoConfig parent = adapter.factories()
                    .resolveNoConfig("parent").orElseThrow();
            assertEquals(2, parent.getClass().getMethod(
                    "mount", io.knotra.ContextHandle.class, String.class).getParameterCount());

            ArtifactFactoryHandle.Configured<String> alpha = adapter.factories()
                    .resolve("alpha", String.class).orElseThrow();
            ArtifactOperationException typedNull = assertThrows(
                    ArtifactOperationException.class,
                    () -> alpha.mount(runtime.root(), "typed-null", null));
            assertEquals("mount", typedNull.phase());
            assertTrue(typedNull.getMessage().contains(String.class.getName()),
                    typedNull::getMessage);
            assertTrue(runtime.advanced().snapshot().mounts().isEmpty());
            assertTrue(adapter.ownership(ARTIFACT_ID).isEmpty());
            assertTrue(adapter.diagnostic(ARTIFACT_ID).orElseThrow().lastError().isEmpty());
        }
    }

    @Test
    void pluginPrivateConfigTokenIsRejectedDuringDiscovery(@TempDir Path pluginsRoot) throws Exception {
        System.setProperty("knotra.pf4j.test.exportPrivateConfig", "true");
        try (KnotraRuntime runtime = KnotraRuntime.create();
             Pf4jArtifactAdapter adapter = newAdapter(pluginsRoot, runtime)) {
            CompletionException failure = assertThrows(CompletionException.class, () ->
                    adapter.loadArtifactAsync(fixture).toCompletableFuture().join());

            assertTrue(failure.getCause().getMessage().contains(
                    "plugin-private contract type rejected"), failure::toString);
            assertTrue(adapter.factories().list().isEmpty());
            assertTrue(runtime.advanced().snapshot().mounts().isEmpty());
        } finally {
            System.clearProperty("knotra.pf4j.test.exportPrivateConfig");
        }
    }

    @Test
    void directDependentLoadLoadsRequiredLeafClosure(@TempDir Path pluginsRoot) throws Exception {
        Files.copy(fixture, pluginsRoot.resolve("dependency.jar"));
        Files.copy(dependentFixture, pluginsRoot.resolve("dependent.jar"));
        try (KnotraRuntime runtime = KnotraRuntime.create();
             Pf4jArtifactAdapter adapter = newAdapter(pluginsRoot, runtime)) {
            ArtifactSnapshot dependent = adapter.loadArtifactAsync(
                    pluginsRoot.resolve("dependent.jar")).toCompletableFuture().join();

            assertEquals(DEPENDENT_ID, dependent.artifactId());
            assertEquals(List.of(ARTIFACT_ID), dependent.dependencyArtifactIds());
            assertEquals(ArtifactState.ACTIVE, adapter.artifact(ARTIFACT_ID).orElseThrow().state());
            assertTrue(adapter.factories().resolve("dependent").isPresent());
            assertTrue(adapter.factories().resolve("alpha").isPresent());
            assertTrue(runtime.advanced().snapshot().mounts().isEmpty());
        }
    }

    @Test
    void dependencyLoadedFirstIsReusedByDependentLoad(@TempDir Path pluginsRoot) throws Exception {
        Files.copy(fixture, pluginsRoot.resolve("dependency.jar"));
        Files.copy(dependentFixture, pluginsRoot.resolve("dependent.jar"));
        try (KnotraRuntime runtime = KnotraRuntime.create();
             Pf4jArtifactAdapter adapter = newAdapter(pluginsRoot, runtime)) {
            adapter.loadArtifactAsync(pluginsRoot.resolve("dependency.jar")).toCompletableFuture().join();

            ArtifactSnapshot dependent = adapter.loadArtifactAsync(
                    pluginsRoot.resolve("dependent.jar")).toCompletableFuture().join();

            assertEquals(DEPENDENT_ID, dependent.artifactId());
            assertEquals(ArtifactState.ACTIVE, adapter.artifact(ARTIFACT_ID).orElseThrow().state());
            assertTrue(adapter.factories().resolve("dependent").isPresent());
            assertTrue(adapter.factories().resolve("alpha").isPresent());
        }
    }

    @Test
    void missingRequiredDependencyFailsBeforeAnyLoad(@TempDir Path pluginsRoot) throws Exception {
        Files.copy(dependentFixture, pluginsRoot.resolve("dependent.jar"));
        try (KnotraRuntime runtime = KnotraRuntime.create();
             Pf4jArtifactAdapter adapter = newAdapter(pluginsRoot, runtime)) {
            CompletableFuture<ArtifactSnapshot> load = adapter.loadArtifactAsync(
                    pluginsRoot.resolve("dependent.jar")).toCompletableFuture();

            CompletionException failure = assertThrows(
                    CompletionException.class, load::join);
            ArtifactOperationException structured = assertInstanceOf(
                    ArtifactOperationException.class, failure.getCause());
            assertEquals("load", structured.phase());
            assertTrue(structured.getMessage().contains(ARTIFACT_ID), structured::getMessage);
            assertTrue(adapter.artifacts().isEmpty());
            assertTrue(adapter.factories().list().isEmpty());
            assertTrue(runtime.advanced().snapshot().mounts().isEmpty());
        }
    }

    @Test
    void missingOptionalDependencyDoesNotBlockTarget(@TempDir Path pluginsRoot) throws Exception {
        Files.copy(optionalFixture, pluginsRoot.resolve("optional.jar"));
        try (KnotraRuntime runtime = KnotraRuntime.create();
             Pf4jArtifactAdapter adapter = newAdapter(pluginsRoot, runtime)) {
            ArtifactSnapshot snapshot = adapter.loadArtifactAsync(
                    pluginsRoot.resolve("optional.jar")).toCompletableFuture().join();

            assertEquals("knotra-optional-plugin", snapshot.artifactId());
            assertEquals(ArtifactState.ACTIVE, snapshot.state());
            adapter.unloadArtifactAsync("knotra-optional-plugin").toCompletableFuture().join();
            assertEquals(ArtifactState.UNLOADED,
                    adapter.artifact("knotra-optional-plugin").orElseThrow().state());
        }
    }

    @Test
    void partialStartFailureReversesLoadedDependencies(@TempDir Path pluginsRoot) throws Exception {
        Files.copy(fixture, pluginsRoot.resolve("dependency.jar"));
        Files.copy(startFailureFixture, pluginsRoot.resolve("start-failure.jar"));
        try (KnotraRuntime runtime = KnotraRuntime.create();
             Pf4jArtifactAdapter adapter = newAdapter(pluginsRoot, runtime)) {
            CompletableFuture<ArtifactSnapshot> load = adapter.loadArtifactAsync(
                    pluginsRoot.resolve("start-failure.jar")).toCompletableFuture();

            CompletionException failure = assertThrows(CompletionException.class, load::join);
            assertTrue(failure.getCause().getMessage().contains(
                    "intentional PF4J start failure"), failure::toString);
            assertTrue(adapter.artifacts().isEmpty(), () -> adapter.artifacts().toString());
            assertTrue(adapter.factories().list().isEmpty());
            assertTrue(runtime.advanced().snapshot().mounts().isEmpty());
            ArtifactDiagnostic diagnostic = adapter.diagnostic(
                    "knotra-start-failure-plugin").orElseThrow();
            assertEquals(ArtifactState.FAILED, diagnostic.state());
            assertEquals("load-rollback", diagnostic.transition());
            assertTrue(diagnostic.classLoaderDiagnostics().isEmpty());
        }
    }

    @Test
    void duplicateRepositoryPluginIdFailsBeforeLoad(@TempDir Path pluginsRoot) throws Exception {
        writeManifestJar(pluginsRoot.resolve("v1.jar"), ARTIFACT_ID, "1.0.0", null);
        writeManifestJar(pluginsRoot.resolve("v2.jar"), ARTIFACT_ID, "2.0.0", null);
        writeManifestJar(pluginsRoot.resolve("target.jar"), "target-plugin", "1.0.0", ARTIFACT_ID);
        try (KnotraRuntime runtime = KnotraRuntime.create();
             Pf4jArtifactAdapter adapter = newAdapter(pluginsRoot, runtime)) {
            CompletableFuture<ArtifactSnapshot> load = adapter.loadArtifactAsync(
                    pluginsRoot.resolve("target.jar")).toCompletableFuture();

            CompletionException failure = assertThrows(CompletionException.class, load::join);
            assertTrue(failure.getCause().getMessage().contains(
                    "ambiguous PF4J repository entry"), failure::toString);
            String duplicateMessage = failure.getCause().getMessage();
            assertTrue(duplicateMessage.contains("1.0.0")
                    && duplicateMessage.contains("2.0.0"), duplicateMessage);
            assertTrue(adapter.artifacts().isEmpty());
            assertTrue(adapter.factories().list().isEmpty());
        }
    }

    @Test
    void loadedArtifactConflictsWithRepositoryDuplicate(@TempDir Path pluginsRoot) throws Exception {
        Path directRoot = Files.createTempDirectory("knotra-direct-");
        Path direct = directRoot.resolve("direct-knotra-plugin.jar").normalize();
        Files.copy(fixture, direct);
        try (KnotraRuntime runtime = KnotraRuntime.create();
             Pf4jArtifactAdapter adapter = newAdapter(pluginsRoot, runtime)) {
            adapter.loadArtifactAsync(direct).toCompletableFuture().join();
            Files.copy(fixture, pluginsRoot.resolve("repository-copy.jar"));

            CompletableFuture<ArtifactSnapshot> second = adapter.loadArtifactAsync(
                    pluginsRoot.resolve("repository-copy.jar")).toCompletableFuture();
            CompletionException failure = assertThrows(CompletionException.class, second::join);

            assertTrue(failure.getCause().getMessage().contains(
                    "ambiguous PF4J repository entry"), failure::toString);
            assertEquals(ArtifactState.ACTIVE, adapter.artifact(ARTIFACT_ID).orElseThrow().state());
            assertEquals(1, adapter.artifacts().size());
        } finally {
            Files.deleteIfExists(direct);
            Files.deleteIfExists(directRoot);
        }
    }

    @Test
    void oneFactoryMountsIndependentStableHandles(@TempDir Path pluginsRoot) throws Exception {
        try (KnotraRuntime runtime = KnotraRuntime.create();
             Pf4jArtifactAdapter adapter = newAdapter(pluginsRoot, runtime)) {
            adapter.loadArtifactAsync(fixture).toCompletableFuture().join();
            ArtifactFactoryHandle.Configured<String> alpha = adapter.factories()
                    .resolve("alpha", String.class).orElseThrow();
            io.knotra.ContextHandle firstContext = runtime.advanced().transact(mutation ->
                    mutation.childContext(runtime.root(), "first")).value();
            io.knotra.ContextHandle secondContext = runtime.advanced().transact(mutation ->
                    mutation.childContext(runtime.root(), "second")).value();

            ConfiguredMountHandle<String> first = alpha.mount(firstContext, "one", " one ");
            ConfiguredMountHandle<String> second = alpha.mount(secondContext, "two", "two");

            assertNotEquals(first.handleId(), second.handleId());
            assertEquals(ComponentState.ACTIVE, settle(first));
            assertEquals(ComponentState.ACTIVE, settle(second));
            assertEquals("one", firstContext.view().require(VALUE));
            assertEquals("two", secondContext.view().require(VALUE));
            assertEquals(2, adapter.ownership(ARTIFACT_ID).size());
            assertTrue(adapter.ownership(ARTIFACT_ID).stream()
                    .allMatch(item -> item.factoryId().equals("alpha")));
        }
    }

    @Test
    void configDecodingAndFactoryNormalizationSupportMountAndReconfigure(
            @TempDir Path pluginsRoot) throws Exception {
        try (KnotraRuntime runtime = KnotraRuntime.create();
             Pf4jArtifactAdapter adapter = newAdapter(pluginsRoot, runtime)) {
            adapter.loadArtifactAsync(fixture).toCompletableFuture().join();
            ConfiguredMountHandle<String> handle = adapter.factories()
                    .resolve("alpha", String.class).orElseThrow()
                    .mount(runtime.root(), "configured", " one ");

            assertEquals(ComponentState.ACTIVE, settle(handle));
            assertEquals("one", runtime.root().view().require(VALUE));
            assertEquals(ComponentState.ACTIVE, handle.reconfigureAsync(" two ")
                    .toCompletableFuture().get(10, TimeUnit.SECONDS));
            assertEquals("two", runtime.root().view().require(VALUE));
            assertEquals(2, handle.configRevision());
        }
    }

    @Test
    void invalidReconfigureIsRejectedAndKeepsCurrentActivation(@TempDir Path pluginsRoot) throws Exception {
        try (KnotraRuntime runtime = KnotraRuntime.create();
             Pf4jArtifactAdapter adapter = newAdapter(pluginsRoot, runtime)) {
            adapter.loadArtifactAsync(fixture).toCompletableFuture().join();
            ConfiguredMountHandle<String> handle = adapter.factories()
                    .resolve("alpha", String.class).orElseThrow()
                    .mount(runtime.root(), "configured", "one");
            assertEquals(ComponentState.ACTIVE, settle(handle));

            assertTrue(handle.reconfigureAsync(" ").toCompletableFuture().isCompletedExceptionally());
            assertEquals(ComponentState.ACTIVE, handle.state());
            assertEquals("one", runtime.root().view().require(VALUE));
            assertEquals(1, handle.configRevision());
        }
    }

    @Test
    void activeFactoryHandleDecodesExactConfigType(@TempDir Path pluginsRoot) throws Exception {
        try (KnotraRuntime runtime = KnotraRuntime.create();
             Pf4jArtifactAdapter adapter = newAdapter(pluginsRoot, runtime)) {
            adapter.loadArtifactAsync(fixture).toCompletableFuture().join();
            ArtifactFactoryHandle.Configured<String> alpha = adapter.factories()
                    .resolve("alpha", String.class).orElseThrow();
            ArtifactFactoryHandle.NoConfig parent = adapter.factories()
                    .resolveNoConfig("parent").orElseThrow();

            String decoded = alpha.decodeConfig(java.util.Map.of("value", " one "));
            assertEquals(" one ", decoded);
            assertEquals(String.class, decoded.getClass());
            assertTrue(parent.noConfig());
            assertEquals(String.class, alpha.configType());

            ArtifactFactoryHandle wildcard = adapter.factories()
                    .resolve("alpha").orElseThrow();
            assertTrue(wildcard instanceof ArtifactFactoryHandle);
            ArtifactFactoryCatalogEntry metadata = adapter.factories()
                    .find("alpha").orElseThrow();
            assertFalse(metadata instanceof ArtifactFactoryHandle);
            assertThrows(IllegalArgumentException.class, () ->
                    adapter.factories().resolve("alpha", NoConfig.class));
            assertThrows(ArtifactOperationException.class, () ->
                    alpha.decodeConfig(java.util.Map.of("value", 7)));

            adapter.unloadArtifactAsync(ARTIFACT_ID).toCompletableFuture().join();
            assertThrows(ArtifactOperationException.class, () ->
                    alpha.decodeConfig("value"));
        }
    }

    @Test
    void childMountInheritsArtifactAndIsDisposedByRootDrain(@TempDir Path pluginsRoot) throws Exception {
        try (KnotraRuntime runtime = KnotraRuntime.create();
             Pf4jArtifactAdapter adapter = newAdapter(pluginsRoot, runtime)) {
            adapter.loadArtifactAsync(fixture).toCompletableFuture().join();
            MountHandle parent = adapter.factories()
                    .resolveNoConfig("parent").orElseThrow()
                    .mount(runtime.root(), "parent");
            assertEquals(ComponentState.ACTIVE, settle(parent));

            RuntimeSnapshot.MountSnapshot child = runtime.advanced().snapshot().mounts().stream()
                    .filter(item -> item.mountId().equals("artifact-child"))
                    .findFirst().orElseThrow();
            assertEquals(ComponentOrigin.Kind.ARTIFACT, child.origin().kind());
            assertEquals(ARTIFACT_ID, child.origin().sourceId());
            assertEquals(parent.handleId(), child.parentHandleId());
            assertEquals(2, adapter.ownership(ARTIFACT_ID).size());

            adapter.unloadArtifactAsync(ARTIFACT_ID).toCompletableFuture().join();
            assertTrue(runtime.advanced().snapshot().mounts().isEmpty());
            assertTrue(adapter.ownership(ARTIFACT_ID).isEmpty());
        }
    }

    @Test
    void drainWaitsForInFlightFactoryAndRejectsLateMount(@TempDir Path pluginsRoot) throws Exception {
        MountCoordinator.reset();
        try (KnotraRuntime runtime = KnotraRuntime.create();
             Pf4jArtifactAdapter adapter = newAdapter(pluginsRoot, runtime);
             ExecutorService executor = Executors.newSingleThreadExecutor()) {
            adapter.loadArtifactAsync(fixture).toCompletableFuture().join();
            ArtifactFactoryHandle.NoConfig inFlight = adapter.factories()
                    .resolveNoConfig("in-flight").orElseThrow();

            CompletableFuture<MountHandle> mount = CompletableFuture.supplyAsync(
                    () -> inFlight.mount(runtime.root(), "late"),
                    executor);
            MountCoordinator.entered().get(10, TimeUnit.SECONDS);
            CompletableFuture<Void> unload = adapter.unloadArtifactAsync(ARTIFACT_ID).toCompletableFuture();

            assertEquals(ArtifactState.DRAINING,
                    adapter.artifact(ARTIFACT_ID).orElseThrow().state());
            assertTrue(adapter.factories().list().isEmpty());
            MountCoordinator.releaseCreate();
            assertThrows(CompletionException.class, mount::join);
            unload.toCompletableFuture().join();
            assertEquals(ArtifactState.UNLOADED,
                    adapter.artifact(ARTIFACT_ID).orElseThrow().state());
            assertTrue(runtime.advanced().snapshot().mounts().isEmpty());
        }
    }

    @Test
    void lostMountRemainsOwnedAndCleanupFailureIsRetryable(@TempDir Path pluginsRoot) throws Exception {
        ReferenceVault.clear();
        try {
            for (int iteration = 0; iteration < 10; iteration++) {
                MountCoordinator.reset();
                CleanupCoordinator.reset();
                CleanupCoordinator.failNextCleanup();
                try (KnotraRuntime runtime = KnotraRuntime.create();
                     Pf4jArtifactAdapter adapter = newAdapter(pluginsRoot, runtime);
                     ExecutorService executor = Executors.newSingleThreadExecutor()) {
                    adapter.loadArtifactAsync(fixture).toCompletableFuture().join();
                    ArtifactFactoryHandle.NoConfig factory = adapter.factories()
                            .resolveNoConfig("lost-race").orElseThrow();

                    int attempt = iteration;
                    CompletableFuture<MountHandle> mount =
                            CompletableFuture.supplyAsync(() ->
                                    factory.mount(runtime.root(), "lost-" + attempt),
                                    executor);
                    MountCoordinator.entered().get(10, TimeUnit.SECONDS);
                    CompletableFuture<Void> unload = adapter.unloadArtifactAsync(ARTIFACT_ID).toCompletableFuture();
                    assertEquals(ArtifactState.DRAINING,
                            adapter.artifact(ARTIFACT_ID).orElseThrow().state());

                    MountCoordinator.releaseCreate();
                    MountHandle accepted = null;
                    try {
                        accepted = mount.toCompletableFuture().join();
                    } catch (CompletionException expected) {
                        // 允许拒绝；无论如何均保留所有权。
                    }
                    assertFalse(adapter.ownership(ARTIFACT_ID).isEmpty());
                    assertThrows(CompletionException.class, unload::join);
                    CleanupCoordinator.allowCleanup();
                    ArtifactSnapshot failed = adapter.artifact(ARTIFACT_ID).orElseThrow();
                    assertEquals(ArtifactState.DRAIN_FAILED, failed.state());
                    assertEquals("STARTED", failed.pf4jState());
                    assertTrue(adapter.ownership(ARTIFACT_ID).stream()
                            .anyMatch(ownership -> ownership.state() == ComponentState.FAILED));

                    adapter.retryDrainAsync(ARTIFACT_ID).toCompletableFuture().join();
                    assertEquals(ArtifactState.UNLOADED,
                            adapter.artifact(ARTIFACT_ID).orElseThrow().state());
                    if (accepted != null) {
                        assertEquals(ComponentState.DISPOSED, accepted.state());
                    }
                    assertTrue(runtime.advanced().snapshot().mounts().isEmpty());
                }
            }
            assertCollectorsReachZero();
        } finally {
            ReferenceVault.clear();
        }
    }

    @Test
    void asyncCleanupBlocksDrainUntilRelease(@TempDir Path pluginsRoot) throws Exception {
        try (KnotraRuntime runtime = KnotraRuntime.create();
             Pf4jArtifactAdapter adapter = newAdapter(pluginsRoot, runtime)) {
            adapter.loadArtifactAsync(fixture).toCompletableFuture().join();
            MountHandle handle = adapter.factories()
                    .resolveNoConfig("async-cleanup").orElseThrow()
                    .mount(runtime.root(), "async");
            assertEquals(ComponentState.ACTIVE, settle(handle));
            ControlledGate gate = runtime.root().view().require(GATE);
            assertFalse(gate.disposed());

            CompletableFuture<Void> unload = adapter.unloadArtifactAsync(ARTIFACT_ID).toCompletableFuture();
            assertEquals(ArtifactState.DRAINING,
                    adapter.artifact(ARTIFACT_ID).orElseThrow().state());
            assertEquals("STARTED", adapter.artifact(ARTIFACT_ID).orElseThrow().pf4jState());
            gate.release();
            unload.toCompletableFuture().join();
            assertTrue(gate.disposed());
            assertEquals(ArtifactState.UNLOADED,
                    adapter.artifact(ARTIFACT_ID).orElseThrow().state());
        }
    }

    @Test
    void failedCleanupKeepsArtifactAndRetryDrainCompletesIt(@TempDir Path pluginsRoot) throws Exception {
        try (KnotraRuntime runtime = KnotraRuntime.create();
             Pf4jArtifactAdapter adapter = newAdapter(pluginsRoot, runtime)) {
            adapter.loadArtifactAsync(fixture).toCompletableFuture().join();
            CleanupCoordinator.reset();
            CleanupCoordinator.failNextCleanup();
            MountHandle handle = adapter.factories()
                    .resolveNoConfig("failing-cleanup").orElseThrow()
                    .mount(runtime.root(), "retry-cleanup");
            assertEquals(ComponentState.ACTIVE, settle(handle));

            CompletableFuture<Void> unload = adapter.unloadArtifactAsync(ARTIFACT_ID).toCompletableFuture();
            assertThrows(CompletionException.class, unload::join);
            ArtifactSnapshot failed = adapter.artifact(ARTIFACT_ID).orElseThrow();
            assertEquals(ArtifactState.DRAIN_FAILED, failed.state());
            assertEquals("STARTED", failed.pf4jState());
            assertEquals(ComponentState.FAILED, handle.state());
            assertEquals(io.knotra.ComponentGoal.DISPOSED, handle.goal());

            CleanupCoordinator.allowCleanup();
            adapter.retryDrainAsync(ARTIFACT_ID).toCompletableFuture().join();
            assertEquals(ArtifactState.UNLOADED,
                    adapter.artifact(ARTIFACT_ID).orElseThrow().state());
            assertEquals(ComponentState.DISPOSED, handle.state());
        }
    }

    @Test
    void dependencyUnloadDrainsDependentFirst(@TempDir Path pluginsRoot) throws Exception {
        Files.copy(fixture, pluginsRoot.resolve("dependency.jar"));
        Files.copy(dependentFixture, pluginsRoot.resolve("dependent.jar"));
        try (KnotraRuntime runtime = KnotraRuntime.create();
             Pf4jArtifactAdapter adapter = newAdapter(pluginsRoot, runtime)) {
            adapter.loadArtifactAsync(pluginsRoot.resolve("dependent.jar")).toCompletableFuture().join();

            adapter.unloadArtifactAsync(ARTIFACT_ID).toCompletableFuture().join();

            assertEquals(ArtifactState.UNLOADED,
                    adapter.artifact(DEPENDENT_ID).orElseThrow().state());
            assertEquals(ArtifactState.UNLOADED,
                    adapter.artifact(ARTIFACT_ID).orElseThrow().state());
            assertTrue(adapter.factories().list().isEmpty());
        }
    }

    @Test
    void concurrentDependentDrainsJoinTheSameClosure(@TempDir Path pluginsRoot) throws Exception {
        Files.copy(fixture, pluginsRoot.resolve("dependency.jar"));
        Files.copy(dependentFixture, pluginsRoot.resolve("dependent.jar"));
        try (KnotraRuntime runtime = KnotraRuntime.create();
             Pf4jArtifactAdapter adapter = newAdapter(pluginsRoot, runtime);
             ExecutorService executor = Executors.newFixedThreadPool(2)) {
            adapter.loadArtifactAsync(pluginsRoot.resolve("dependent.jar")).toCompletableFuture().join();

            CompletableFuture<Void> dependencyDrain = CompletableFuture.runAsync(
                    () -> adapter.unloadArtifactAsync(ARTIFACT_ID).toCompletableFuture().join(), executor);
            CompletableFuture<Void> dependentDrain = CompletableFuture.runAsync(
                    () -> adapter.unloadArtifactAsync(DEPENDENT_ID).toCompletableFuture().join(), executor);
            CompletableFuture.allOf(dependencyDrain, dependentDrain)
                    .get(20, TimeUnit.SECONDS);

            assertEquals(ArtifactState.UNLOADED,
                    adapter.artifact(DEPENDENT_ID).orElseThrow().state());
            assertEquals(ArtifactState.UNLOADED,
                    adapter.artifact(ARTIFACT_ID).orElseThrow().state());
            assertTrue(runtime.advanced().snapshot().mounts().isEmpty());
        }
    }

    @Test
    void snapshotOnlyArtifactRootBlocksUnloadAndKeepsClassLoader(@TempDir Path pluginsRoot) throws Exception {
        try (KnotraRuntime runtime = KnotraRuntime.create();
             Pf4jArtifactAdapter adapter = newAdapter(pluginsRoot, runtime)) {
            adapter.loadArtifactAsync(fixture).toCompletableFuture().join();
            MountHandle external = runtime.advanced().transact(transaction -> transaction.mount(
                    runtime.root(),
                    "snapshot-only-root",
                    hostFactory(),
                    new MountOptions(ComponentOrigin.artifact(
                            ARTIFACT_ID, "1.0.0", "host-mounted artifact root")))).value();
            assertEquals(ComponentState.ACTIVE, settle(external));

            CompletableFuture<Void> unload = adapter.unloadArtifactAsync(ARTIFACT_ID).toCompletableFuture();
            CompletionException failure = assertThrows(CompletionException.class, unload::join);
            ArtifactOperationException structured = assertInstanceOf(
                    ArtifactOperationException.class, failure.getCause());
            assertEquals("drain", structured.phase());

            ArtifactSnapshot blocked = adapter.artifact(ARTIFACT_ID).orElseThrow();
            assertEquals(ArtifactState.DRAIN_FAILED, blocked.state());
            assertEquals("STARTED", blocked.pf4jState());
            assertFalse(blocked.classLoaderDescription().isBlank());
            assertTrue(adapter.diagnostic(ARTIFACT_ID).isPresent());

            external.disposeAsync().toCompletableFuture().get(10, TimeUnit.SECONDS);
            adapter.retryDrainAsync(ARTIFACT_ID).toCompletableFuture().join();
            assertEquals(ArtifactState.UNLOADED,
                    adapter.artifact(ARTIFACT_ID).orElseThrow().state());
        }
    }

    @Test
    void staleFactoryHandleCannotMountAfterDrain(@TempDir Path pluginsRoot) throws Exception {
        try (KnotraRuntime runtime = KnotraRuntime.create();
             Pf4jArtifactAdapter adapter = newAdapter(pluginsRoot, runtime)) {
            adapter.loadArtifactAsync(fixture).toCompletableFuture().join();
            ArtifactFactoryHandle.Configured<String> stale = adapter.factories()
                    .resolve("alpha", String.class).orElseThrow();
            adapter.unloadArtifactAsync(ARTIFACT_ID).toCompletableFuture().join();

            ArtifactOperationException failure = assertThrows(
                    ArtifactOperationException.class,
                    () -> stale.mount(runtime.root(), "stale", "value"));
            assertEquals("mount", failure.phase());
            assertTrue(runtime.advanced().snapshot().mounts().isEmpty());
        }
    }

    @Test
    void privateDescriptorIsRejectedBeforeCoreTypeMap(@TempDir Path pluginsRoot) throws Exception {
        try (KnotraRuntime runtime = KnotraRuntime.create();
             Pf4jArtifactAdapter adapter = newAdapter(pluginsRoot, runtime)) {
            adapter.loadArtifactAsync(fixture).toCompletableFuture().join();
            ArtifactFactoryHandle.NoConfig handle = adapter.factories()
                    .resolveNoConfig("private-descriptor").orElseThrow();

            ArtifactOperationException failure = assertThrows(
                    ArtifactOperationException.class,
                    () -> handle.mount(runtime.root(), "private"));
            assertTrue(failure.getMessage().contains("plugin-private"), failure::getMessage);
            assertTrue(runtime.advanced().snapshot().mounts().stream()
                    .noneMatch(item -> item.mountId().equals("private")));
            runtime.advanced().transact(transaction -> transaction.provide(
                    runtime.root(),
                    CapabilityKey.of("plugin-private-contract", String.class),
                    "host-owned"));
        }
    }

    @Test
    void privateDynamicProvideFailsActivationWithoutRegistration(@TempDir Path pluginsRoot) throws Exception {
        try (KnotraRuntime runtime = KnotraRuntime.create();
             Pf4jArtifactAdapter adapter = newAdapter(pluginsRoot, runtime)) {
            adapter.loadArtifactAsync(fixture).toCompletableFuture().join();
            MountHandle handle = adapter.factories()
                    .resolveNoConfig("private-provide").orElseThrow()
                    .mount(runtime.root(), "private-provide");

            assertEquals(ComponentState.FAILED, settle(handle));
            assertTrue(runtime.advanced().snapshot().registrations().isEmpty());
            assertTrue(runtime.root().view().find(
                    CapabilityKey.of("plugin-private-contract", String.class)).isEmpty());
        }
    }

    @Test
    void privateChildContractIsRejectedDuringParentStart(@TempDir Path pluginsRoot) throws Exception {
        try (KnotraRuntime runtime = KnotraRuntime.create();
             Pf4jArtifactAdapter adapter = newAdapter(pluginsRoot, runtime)) {
            adapter.loadArtifactAsync(fixture).toCompletableFuture().join();
            MountHandle parent = adapter.factories()
                    .resolveNoConfig("private-child").orElseThrow()
                    .mount(runtime.root(), "private-parent");

            assertEquals(ComponentState.FAILED, settle(parent));
            assertTrue(runtime.advanced().snapshot().mounts().stream()
                    .noneMatch(item -> item.mountId().equals("private-child")));
        }
    }

    @Test
    void snapshotsAndDiagnosticsContainOnlyStableText(@TempDir Path pluginsRoot) throws Exception {
        try (KnotraRuntime runtime = KnotraRuntime.create();
             Pf4jArtifactAdapter adapter = newAdapter(pluginsRoot, runtime)) {
            adapter.loadArtifactAsync(fixture).toCompletableFuture().join();
            ConfiguredMountHandle<String> stable = adapter.factories()
                    .resolve("alpha", String.class).orElseThrow()
                    .mount(runtime.root(), "stable", "value");
            MountHandle parent = adapter.factories()
                    .resolveNoConfig("parent").orElseThrow()
                    .mount(runtime.root(), "stable-parent");
            assertEquals(ComponentState.ACTIVE, settle(stable));
            assertEquals(ComponentState.ACTIVE, settle(parent));
            ArtifactSnapshot first = adapter.artifact(ARTIFACT_ID).orElseThrow();
            ArtifactDiagnostic diagnostic = adapter.diagnostic(ARTIFACT_ID).orElseThrow();
            assertEquals(first, adapter.artifact(ARTIFACT_ID).orElseThrow());
            assertEquals(diagnostic, adapter.diagnostic(ARTIFACT_ID).orElseThrow());
            String view = first.toString() + diagnostic;
            assertFalse(view.contains("java.lang.Throwable"));
            assertFalse(view.contains("PrivateContract"));
            assertEquals(3, adapter.ownership(ARTIFACT_ID).size());
        }
    }

    @Test
    void coordinatorSubmissionsAreReentrant(@TempDir Path pluginsRoot) throws Exception {
        try (KnotraRuntime runtime = KnotraRuntime.create();
             Pf4jArtifactAdapter adapter = newAdapter(pluginsRoot, runtime)) {
            adapter.loadArtifactAsync(fixture).toCompletableFuture().join();
            DefaultPf4jArtifactAdapter typed = assertInstanceOf(
                    DefaultPf4jArtifactAdapter.class, adapter);

            Integer result = typed.coordinateRead(() ->
                    typed.coordinateRead(() -> adapter.factories().list().size()));

            assertEquals(10, result);
        }
    }

    @Test
    void concurrentCloseIsIdempotentAndDrainsEverything(@TempDir Path pluginsRoot) throws Exception {
        try (KnotraRuntime runtime = KnotraRuntime.create();
             Pf4jArtifactAdapter adapter = newAdapter(pluginsRoot, runtime);
             ExecutorService executor = Executors.newFixedThreadPool(2)) {
            adapter.loadArtifactAsync(fixture).toCompletableFuture().join();
            adapter.factories().resolve("alpha", String.class).orElseThrow()
                    .mount(runtime.root(), "close-me", "value");

            CompletableFuture<Void> first = CompletableFuture.supplyAsync(
                    () -> adapter.closeAsync().toCompletableFuture().join(), executor);
            CompletableFuture<Void> second = CompletableFuture.supplyAsync(
                    () -> adapter.closeAsync().toCompletableFuture().join(), executor);
            CompletableFuture.allOf(first, second).get(20, TimeUnit.SECONDS);

            assertTrue(runtime.advanced().snapshot().mounts().isEmpty());
            assertEquals(ArtifactState.UNLOADED,
                    adapter.artifact(ARTIFACT_ID).orElseThrow().state());
        }
    }

    @Test
    void failedCloseRetriesCleanupOnTheNextAttempt(@TempDir Path pluginsRoot) throws Exception {
        ReferenceVault.clear();
        try {
            for (int iteration = 0; iteration < 10; iteration++) {
                CleanupCoordinator.reset();
                CleanupCoordinator.failNextCleanup();
                try (KnotraRuntime runtime = KnotraRuntime.create();
                     Pf4jArtifactAdapter adapter = newAdapter(pluginsRoot, runtime)) {
                    adapter.loadArtifactAsync(fixture).toCompletableFuture().join();
                    MountHandle handle = adapter.factories()
                            .resolveNoConfig("failing-cleanup").orElseThrow()
                            .mount(runtime.root(), "close-retry-" + iteration);
                    assertEquals(ComponentState.ACTIVE, settle(handle));

                    CompletableFuture<Void> firstClose = adapter.closeAsync().toCompletableFuture();
                    assertThrows(CompletionException.class, firstClose::join);
                    assertEquals(ArtifactState.DRAIN_FAILED,
                            adapter.artifact(ARTIFACT_ID).orElseThrow().state());
                    assertEquals("drain-failed", adapter.diagnostic(ARTIFACT_ID)
                            .orElseThrow().transition());
                    assertFalse(adapter.ownership(ARTIFACT_ID).isEmpty());

                    CleanupCoordinator.allowCleanup();
                    adapter.closeAsync().toCompletableFuture().join();
                    assertEquals(ArtifactState.UNLOADED,
                            adapter.artifact(ARTIFACT_ID).orElseThrow().state());
                    assertEquals(ComponentState.DISPOSED, handle.state());
                    assertTrue(runtime.advanced().snapshot().mounts().isEmpty());
                }
            }
            assertCollectorsReachZero();
        } finally {
            ReferenceVault.clear();
        }
    }

    @Test
    void successfulUnloadReleasesPluginClassLoader(@TempDir Path pluginsRoot) throws Exception {
        ReferenceVault.clear();
        try (KnotraRuntime runtime = KnotraRuntime.create();
             Pf4jArtifactAdapter adapter = newAdapter(pluginsRoot, runtime)) {
            adapter.loadArtifactAsync(fixture).toCompletableFuture().join();
            adapter.factories().resolve("alpha", String.class).orElseThrow()
                    .mount(runtime.root(), "gc", "value");
            adapter.unloadArtifactAsync(ARTIFACT_ID).toCompletableFuture().join();

            assertEquals(ArtifactState.UNLOADED,
                    adapter.artifact(ARTIFACT_ID).orElseThrow().state());
            assertCollectorsReachZero();
        } finally {
            ReferenceVault.clear();
        }
    }

    @Test
    void partialStartFailureReleasesPluginClassLoader(@TempDir Path pluginsRoot) throws Exception {
        ReferenceVault.clear();
        Files.copy(fixture, pluginsRoot.resolve("dependency.jar"));
        Files.copy(startFailureFixture, pluginsRoot.resolve("start-failure.jar"));
        try (KnotraRuntime runtime = KnotraRuntime.create();
             Pf4jArtifactAdapter adapter = newAdapter(pluginsRoot, runtime)) {
            assertThrows(CompletionException.class, () -> adapter.loadArtifactAsync(
                    pluginsRoot.resolve("start-failure.jar")).toCompletableFuture().join());
            assertCollectorsReachZero();
        } finally {
            ReferenceVault.clear();
        }
    }

    @Test
    void closeDrainsOwnedHandlesAndStopsPf4j(@TempDir Path pluginsRoot) throws Exception {
        try (KnotraRuntime runtime = KnotraRuntime.create();
             Pf4jArtifactAdapter adapter = newAdapter(pluginsRoot, runtime)) {
            adapter.loadArtifactAsync(fixture).toCompletableFuture().join();
            adapter.factories().resolve("alpha", String.class).orElseThrow()
                    .mount(runtime.root(), "drain-on-close", "value");

            adapter.closeAsync().toCompletableFuture().join();

            assertEquals(ArtifactState.UNLOADED,
                    adapter.artifact(ARTIFACT_ID).orElseThrow().state());
            assertEquals("UNLOADED", adapter.artifact(ARTIFACT_ID).orElseThrow().pf4jState());
            assertTrue(runtime.advanced().snapshot().mounts().isEmpty());
            assertTrue(adapter.ownership(ARTIFACT_ID).isEmpty());
        }
    }

    @Test
    void malformedArtifactFailsInStructuredWay(@TempDir Path pluginsRoot) throws Exception {
        Path malformed = pluginsRoot.resolve("malformed.jar");
        Files.writeString(malformed, "not a jar", StandardCharsets.UTF_8);
        try (KnotraRuntime runtime = KnotraRuntime.create();
             Pf4jArtifactAdapter adapter = newAdapter(pluginsRoot, runtime)) {
            CompletionException failure = assertThrows(CompletionException.class, () ->
                    adapter.loadArtifactAsync(malformed).toCompletableFuture().join());

            ArtifactOperationException structured = assertInstanceOf(
                    ArtifactOperationException.class, failure.getCause());
            assertEquals("load", structured.phase());
            assertTrue(adapter.factories().list().isEmpty());
        }
    }

    @Test
    void unloadUnknownArtifactIsStructured(@TempDir Path pluginsRoot) throws Exception {
        try (KnotraRuntime runtime = KnotraRuntime.create();
             Pf4jArtifactAdapter adapter = newAdapter(pluginsRoot, runtime)) {
            CompletionException failure = assertThrows(CompletionException.class, () ->
                    adapter.unloadArtifactAsync("absent").toCompletableFuture().join());

            ArtifactOperationException structured = assertInstanceOf(
                    ArtifactOperationException.class, failure.getCause());
            assertEquals("absent", structured.artifactId());
            assertEquals("unload", structured.phase());
        }
    }

    @Test
    void artifactIdCanBeLoadedAgainAfterSuccessfulUnload(@TempDir Path pluginsRoot) throws Exception {
        try (KnotraRuntime runtime = KnotraRuntime.create();
             Pf4jArtifactAdapter adapter = newAdapter(pluginsRoot, runtime)) {
            ArtifactSnapshot first = adapter.loadArtifactAsync(fixture).toCompletableFuture().join();
            adapter.unloadArtifactAsync(ARTIFACT_ID).toCompletableFuture().join();
            ArtifactSnapshot second = adapter.loadArtifactAsync(fixture).toCompletableFuture().join();

            assertEquals(first.artifactId(), second.artifactId());
            assertEquals(ArtifactState.ACTIVE, second.state());
            assertEquals(10, adapter.factories().list().size());
        }
    }
    @Test
    void adapterCloseConvergesWhenRuntimeCloseAlreadyOwnsTheDisposal(
            @TempDir Path pluginsRoot) throws Exception {
        try (KnotraRuntime runtime = KnotraRuntime.create();
             Pf4jArtifactAdapter adapter = newAdapter(pluginsRoot, runtime)) {
            adapter.loadArtifactAsync(fixture).toCompletableFuture().join();
            MountHandle handle = adapter.factories()
                    .resolveNoConfig("async-cleanup").orElseThrow()
                    .mount(runtime.root(), "runtime-owned");
            assertEquals(ComponentState.ACTIVE, settle(handle));
            ControlledGate gate = runtime.root().view().require(GATE);

            CompletableFuture<Void> runtimeClose =
                    runtime.closeAsync().toCompletableFuture();
            for (int attempt = 0;
                    attempt < 1000 && handle.state() != ComponentState.STOPPING;
                    attempt++) {
                tick();
            }
            assertEquals(ComponentState.STOPPING, handle.state());

            CompletableFuture<Void> adapterClose = adapter.closeAsync().toCompletableFuture();
        gate.release();

            runtimeClose.get(30, TimeUnit.SECONDS);
            adapterClose.get(30, TimeUnit.SECONDS);
            assertEquals(ComponentState.DISPOSED, handle.state());
            assertEquals(ArtifactState.UNLOADED,
                    adapter.artifact(ARTIFACT_ID).orElseThrow().state());
        }
    }

    private static ComponentFactory<NoConfig> hostFactory() {
        return new ComponentFactory<>() {
            @Override
            public String factoryId() {
                return "host-artifact-root-factory";
            }

            @Override
            public Component<NoConfig> create() {
                return new Component<>() {
                    @Override
                    public ComponentDescriptor descriptor() {
                        return ComponentDescriptor.named("host-artifact-root");
                    }

                    @Override
                    public void start(
                            io.knotra.ActivationContext context,
                            NoConfig config) {
                    }
                };
            }
        };
    }

    private static Pf4jArtifactAdapter newAdapter(Path pluginsRoot, KnotraRuntime runtime) {
        return Pf4jArtifactAdapter.create(
                pluginsRoot,
                runtime,
                Set.of("com.example.knotra.contract"));
    }

    private static ComponentState settle(MountHandle handle) throws Exception {
        return handle.whenSettled().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    private static void assertCollectorsReachZero() throws Exception {
        for (int attempt = 0; attempt < 100; attempt++) {
            System.gc();
            if (ReferenceVault.liveLoaders() == 0) {
                return;
            }
            tick();
        }
        assertEquals(0L, ReferenceVault.liveLoaders());
    }

    private static void tick() throws Exception {
        CompletableFuture<Void> future = new CompletableFuture<>();
        CompletableFuture.delayedExecutor(10, TimeUnit.MILLISECONDS)
                .execute(() -> future.complete(null));
        future.get(100, TimeUnit.MILLISECONDS);
    }

    private static void writeManifestJar(
            Path path,
            String pluginId,
            String version,
            String dependencies) throws Exception {
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.putValue("Plugin-Id", pluginId);
        attributes.putValue("Plugin-Version", version);
        if (dependencies != null && !dependencies.isBlank()) {
            attributes.putValue("Plugin-Dependencies", dependencies);
        }
        try (OutputStream output = Files.newOutputStream(path);
             JarOutputStream jar = new JarOutputStream(output, manifest)) {
            // 仅包含 Manifest 的 fixture 足以用于测试仓库解析失败。
        }
    }
}
