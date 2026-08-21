package io.knotra.pf4j;

import com.example.knotra.contract.CleanupCoordinator;
import com.example.knotra.contract.ControlledGate;
import com.example.knotra.contract.MountCoordinator;
import com.example.knotra.contract.ReferenceVault;
import io.knotra.CapabilityKey;
import io.knotra.Component;
import io.knotra.ComponentDescriptor;
import io.knotra.ComponentFactory;
import io.knotra.ComponentHandle;
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
            ArtifactSnapshot snapshot = adapter.loadArtifact(fixture).join();

            assertEquals(ARTIFACT_ID, snapshot.artifactId());
            assertEquals(ArtifactState.ACTIVE, snapshot.state());
            assertEquals("STARTED", snapshot.pf4jState());
            assertTrue(runtime.snapshot().components().isEmpty());
            assertEquals(
                    List.of("alpha", "beta", "async-cleanup", "in-flight",
                            "lost-race",
                            "private-descriptor", "private-provide", "private-child",
                            "parent", "failing-cleanup"),
                    adapter.factoryCatalog().stream()
                            .map(ArtifactFactoryCatalogEntry::factoryId).toList());
            ArtifactFactoryCatalogEntry alpha = adapter.resolver().resolve("alpha").orElseThrow();
            assertEquals(String.class.getName(), alpha.configTypeName());
            assertEquals(fixture.toString(), alpha.artifactPath());
            assertFalse(alpha instanceof ArtifactFactoryHandle<?>);
            assertFalse(snapshot.classLoaderDescription().isBlank());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void typedAndRawNullConfigAreRejectedBeforeCreate(@TempDir Path pluginsRoot) throws Exception {
        try (KnotraRuntime runtime = KnotraRuntime.create();
             Pf4jArtifactAdapter adapter = newAdapter(pluginsRoot, runtime)) {
            adapter.loadArtifact(fixture).join();
            ArtifactFactoryHandle<NoConfig> parent = adapter.resolver()
                    .resolve("parent", NoConfig.class).orElseThrow();

            ArtifactOperationException typedNull = assertThrows(
                    ArtifactOperationException.class,
                    () -> parent.mount(runtime.rootContext(), "typed-null", null));
            assertEquals("mount", typedNull.phase());
            assertTrue(typedNull.getMessage().contains(NoConfig.class.getName()),
                    typedNull::getMessage);
            assertTrue(typedNull.getMessage().contains("NoConfig.INSTANCE"),
                    typedNull::getMessage);
            assertTrue(runtime.snapshot().components().isEmpty());

            ArtifactFactoryHandle raw = parent;
            ArtifactOperationException rawNull = assertThrows(
                    ArtifactOperationException.class,
                    () -> raw.mount(runtime.rootContext(), "raw-null", null));
            assertEquals("mount", rawNull.phase());
            assertTrue(rawNull.getMessage().contains("NoConfig.INSTANCE"),
                    rawNull::getMessage);
            assertTrue(runtime.snapshot().components().isEmpty());

            ArtifactOperationException wrongType = assertThrows(
                    ArtifactOperationException.class,
                    () -> raw.mount(runtime.rootContext(), "raw-cast", Integer.valueOf(7)));
            assertEquals("mount", wrongType.phase());
            assertTrue(wrongType.getMessage().contains(NoConfig.class.getName()),
                    wrongType::getMessage);
            assertTrue(wrongType.getMessage().contains(Integer.class.getName()),
                    wrongType::getMessage);

            assertTrue(runtime.snapshot().components().isEmpty());
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
                    adapter.loadArtifact(fixture).join());

            assertTrue(failure.getCause().getMessage().contains(
                    "plugin-private contract type rejected"), failure::toString);
            assertTrue(adapter.factoryCatalog().isEmpty());
            assertTrue(runtime.snapshot().components().isEmpty());
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
            ArtifactSnapshot dependent = adapter.loadArtifact(
                    pluginsRoot.resolve("dependent.jar")).join();

            assertEquals(DEPENDENT_ID, dependent.artifactId());
            assertEquals(List.of(ARTIFACT_ID), dependent.dependencyArtifactIds());
            assertEquals(ArtifactState.ACTIVE, adapter.artifact(ARTIFACT_ID).orElseThrow().state());
            assertTrue(adapter.resolver().resolve("dependent").isPresent());
            assertTrue(adapter.resolver().resolve("alpha").isPresent());
            assertTrue(runtime.snapshot().components().isEmpty());
        }
    }

    @Test
    void dependencyLoadedFirstIsReusedByDependentLoad(@TempDir Path pluginsRoot) throws Exception {
        Files.copy(fixture, pluginsRoot.resolve("dependency.jar"));
        Files.copy(dependentFixture, pluginsRoot.resolve("dependent.jar"));
        try (KnotraRuntime runtime = KnotraRuntime.create();
             Pf4jArtifactAdapter adapter = newAdapter(pluginsRoot, runtime)) {
            adapter.loadArtifact(pluginsRoot.resolve("dependency.jar")).join();

            ArtifactSnapshot dependent = adapter.loadArtifact(
                    pluginsRoot.resolve("dependent.jar")).join();

            assertEquals(DEPENDENT_ID, dependent.artifactId());
            assertEquals(ArtifactState.ACTIVE, adapter.artifact(ARTIFACT_ID).orElseThrow().state());
            assertTrue(adapter.resolver().resolve("dependent").isPresent());
            assertTrue(adapter.resolver().resolve("alpha").isPresent());
        }
    }

    @Test
    void missingRequiredDependencyFailsBeforeAnyLoad(@TempDir Path pluginsRoot) throws Exception {
        Files.copy(dependentFixture, pluginsRoot.resolve("dependent.jar"));
        try (KnotraRuntime runtime = KnotraRuntime.create();
             Pf4jArtifactAdapter adapter = newAdapter(pluginsRoot, runtime)) {
            CompletableFuture<ArtifactSnapshot> load = adapter.loadArtifact(
                    pluginsRoot.resolve("dependent.jar"));

            CompletionException failure = assertThrows(
                    CompletionException.class, load::join);
            ArtifactOperationException structured = assertInstanceOf(
                    ArtifactOperationException.class, failure.getCause());
            assertEquals("load", structured.phase());
            assertTrue(structured.getMessage().contains(ARTIFACT_ID), structured::getMessage);
            assertTrue(adapter.artifacts().isEmpty());
            assertTrue(adapter.factoryCatalog().isEmpty());
            assertTrue(runtime.snapshot().components().isEmpty());
        }
    }

    @Test
    void missingOptionalDependencyDoesNotBlockTarget(@TempDir Path pluginsRoot) throws Exception {
        Files.copy(optionalFixture, pluginsRoot.resolve("optional.jar"));
        try (KnotraRuntime runtime = KnotraRuntime.create();
             Pf4jArtifactAdapter adapter = newAdapter(pluginsRoot, runtime)) {
            ArtifactSnapshot snapshot = adapter.loadArtifact(
                    pluginsRoot.resolve("optional.jar")).join();

            assertEquals("knotra-optional-plugin", snapshot.artifactId());
            assertEquals(ArtifactState.ACTIVE, snapshot.state());
            adapter.unloadArtifact("knotra-optional-plugin").join();
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
            CompletableFuture<ArtifactSnapshot> load = adapter.loadArtifact(
                    pluginsRoot.resolve("start-failure.jar"));

            CompletionException failure = assertThrows(CompletionException.class, load::join);
            assertTrue(failure.getCause().getMessage().contains(
                    "intentional PF4J start failure"), failure::toString);
            assertTrue(adapter.artifacts().isEmpty(), () -> adapter.artifacts().toString());
            assertTrue(adapter.factoryCatalog().isEmpty());
            assertTrue(runtime.snapshot().components().isEmpty());
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
            CompletableFuture<ArtifactSnapshot> load = adapter.loadArtifact(
                    pluginsRoot.resolve("target.jar"));

            CompletionException failure = assertThrows(CompletionException.class, load::join);
            assertTrue(failure.getCause().getMessage().contains(
                    "ambiguous PF4J repository entry"), failure::toString);
            String duplicateMessage = failure.getCause().getMessage();
            assertTrue(duplicateMessage.contains("1.0.0")
                    && duplicateMessage.contains("2.0.0"), duplicateMessage);
            assertTrue(adapter.artifacts().isEmpty());
            assertTrue(adapter.factoryCatalog().isEmpty());
        }
    }

    @Test
    void loadedArtifactConflictsWithRepositoryDuplicate(@TempDir Path pluginsRoot) throws Exception {
        Path directRoot = Files.createTempDirectory("knotra-direct-");
        Path direct = directRoot.resolve("direct-knotra-plugin.jar").normalize();
        Files.copy(fixture, direct);
        try (KnotraRuntime runtime = KnotraRuntime.create();
             Pf4jArtifactAdapter adapter = newAdapter(pluginsRoot, runtime)) {
            adapter.loadArtifact(direct).join();
            Files.copy(fixture, pluginsRoot.resolve("repository-copy.jar"));

            CompletableFuture<ArtifactSnapshot> second = adapter.loadArtifact(
                    pluginsRoot.resolve("repository-copy.jar"));
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
            adapter.loadArtifact(fixture).join();
            ArtifactFactoryHandle<String> alpha = adapter.resolver()
                    .resolve("alpha", String.class).orElseThrow();
            io.knotra.ContextHandle firstContext = runtime.mutate(mutation ->
                    mutation.childContext(runtime.rootContext(), "first")).value();
            io.knotra.ContextHandle secondContext = runtime.mutate(mutation ->
                    mutation.childContext(runtime.rootContext(), "second")).value();

            ComponentHandle<String> first = alpha.mount(firstContext, "one", " one ");
            ComponentHandle<String> second = alpha.mount(secondContext, "two", "two");

            assertNotEquals(first.handleId(), second.handleId());
            assertEquals(ComponentState.ACTIVE, settle(first));
            assertEquals(ComponentState.ACTIVE, settle(second));
            assertEquals("one", firstContext.context().require(VALUE));
            assertEquals("two", secondContext.context().require(VALUE));
            assertEquals(2, adapter.ownership(ARTIFACT_ID).size());
            assertTrue(adapter.ownership(ARTIFACT_ID).stream()
                    .allMatch(item -> item.factoryId().equals("alpha")));
        }
    }

    @Test
    void configSchemaNormalizesMountAndReconfigure(@TempDir Path pluginsRoot) throws Exception {
        try (KnotraRuntime runtime = KnotraRuntime.create();
             Pf4jArtifactAdapter adapter = newAdapter(pluginsRoot, runtime)) {
            adapter.loadArtifact(fixture).join();
            ComponentHandle<String> handle = adapter.resolver()
                    .resolve("alpha", String.class).orElseThrow()
                    .mount(runtime.rootContext(), "configured", " one ");

            assertEquals(ComponentState.ACTIVE, settle(handle));
            assertEquals("one", runtime.context().require(VALUE));
            assertEquals(ComponentState.ACTIVE, handle.reconfigure(" two ")
                    .toCompletableFuture().get(10, TimeUnit.SECONDS));
            assertEquals("two", runtime.context().require(VALUE));
            assertEquals(2, handle.configRevision());
        }
    }

    @Test
    void invalidReconfigureIsRejectedAndKeepsCurrentActivation(@TempDir Path pluginsRoot) throws Exception {
        try (KnotraRuntime runtime = KnotraRuntime.create();
             Pf4jArtifactAdapter adapter = newAdapter(pluginsRoot, runtime)) {
            adapter.loadArtifact(fixture).join();
            ComponentHandle<String> handle = adapter.resolver()
                    .resolve("alpha", String.class).orElseThrow()
                    .mount(runtime.rootContext(), "configured", "one");
            assertEquals(ComponentState.ACTIVE, settle(handle));

            assertTrue(handle.reconfigure(" ").toCompletableFuture().isCompletedExceptionally());
            assertEquals(ComponentState.ACTIVE, handle.state());
            assertEquals("one", runtime.context().require(VALUE));
            assertEquals(1, handle.configRevision());
        }
    }

    @Test
    void activeFactoryHandleExposesOnlyTypeCheckedConfigSchema(@TempDir Path pluginsRoot) throws Exception {
        try (KnotraRuntime runtime = KnotraRuntime.create();
             Pf4jArtifactAdapter adapter = newAdapter(pluginsRoot, runtime)) {
            adapter.loadArtifact(fixture).join();
            ArtifactFactoryHandle<String> alpha = adapter.resolver()
                    .resolve("alpha", String.class).orElseThrow();
            ArtifactFactoryHandle<NoConfig> parent = adapter.resolver()
                    .resolve("parent", NoConfig.class).orElseThrow();

            var schema = alpha.configSchema().orElseThrow();
            assertEquals("one", schema.validate(" one "));
            assertTrue(parent.configSchema().isEmpty());
            assertEquals(String.class, alpha.configType());
            assertThrows(IllegalArgumentException.class, () ->
                    adapter.resolver().resolve("alpha", NoConfig.class));

            adapter.unloadArtifact(ARTIFACT_ID).join();
            assertThrows(ArtifactOperationException.class, () ->
                    alpha.configSchema());
        }
    }

    @Test
    void childMountInheritsArtifactAndIsDisposedByRootDrain(@TempDir Path pluginsRoot) throws Exception {
        try (KnotraRuntime runtime = KnotraRuntime.create();
             Pf4jArtifactAdapter adapter = newAdapter(pluginsRoot, runtime)) {
            adapter.loadArtifact(fixture).join();
            ComponentHandle<NoConfig> parent = adapter.resolver()
                    .resolve("parent", NoConfig.class).orElseThrow()
                    .mount(runtime.rootContext(), "parent", NoConfig.INSTANCE);
            assertEquals(ComponentState.ACTIVE, settle(parent));

            RuntimeSnapshot.ComponentSnapshot child = runtime.snapshot().components().stream()
                    .filter(item -> item.mountId().equals("artifact-child"))
                    .findFirst().orElseThrow();
            assertEquals(ComponentOrigin.Kind.ARTIFACT, child.origin().kind());
            assertEquals(ARTIFACT_ID, child.origin().sourceId());
            assertEquals(parent.handleId(), child.parentHandleId());
            assertEquals(2, adapter.ownership(ARTIFACT_ID).size());

            adapter.unloadArtifact(ARTIFACT_ID).join();
            assertTrue(runtime.snapshot().components().isEmpty());
            assertTrue(adapter.ownership(ARTIFACT_ID).isEmpty());
        }
    }

    @Test
    void drainWaitsForInFlightFactoryAndRejectsLateMount(@TempDir Path pluginsRoot) throws Exception {
        MountCoordinator.reset();
        try (KnotraRuntime runtime = KnotraRuntime.create();
             Pf4jArtifactAdapter adapter = newAdapter(pluginsRoot, runtime);
             ExecutorService executor = Executors.newSingleThreadExecutor()) {
            adapter.loadArtifact(fixture).join();
            ArtifactFactoryHandle<NoConfig> inFlight = adapter.resolver()
                    .resolve("in-flight", NoConfig.class).orElseThrow();

            CompletableFuture<ComponentHandle<NoConfig>> mount = CompletableFuture.supplyAsync(
                    () -> inFlight.mount(runtime.rootContext(), "late", NoConfig.INSTANCE),
                    executor);
            MountCoordinator.entered().get(10, TimeUnit.SECONDS);
            CompletableFuture<Void> unload = adapter.unloadArtifact(ARTIFACT_ID);

            assertEquals(ArtifactState.DRAINING,
                    adapter.artifact(ARTIFACT_ID).orElseThrow().state());
            assertTrue(adapter.factoryCatalog().isEmpty());
            MountCoordinator.releaseCreate();
            assertThrows(CompletionException.class, mount::join);
            unload.join();
            assertEquals(ArtifactState.UNLOADED,
                    adapter.artifact(ARTIFACT_ID).orElseThrow().state());
            assertTrue(runtime.snapshot().components().isEmpty());
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
                    adapter.loadArtifact(fixture).join();
                    ArtifactFactoryHandle<NoConfig> factory = adapter.resolver()
                            .resolve("lost-race", NoConfig.class).orElseThrow();

                    int attempt = iteration;
                    CompletableFuture<ComponentHandle<NoConfig>> mount =
                            CompletableFuture.supplyAsync(() ->
                                    factory.mount(runtime.rootContext(),
                                            "lost-" + attempt,
                                            NoConfig.INSTANCE),
                                    executor);
                    MountCoordinator.entered().get(10, TimeUnit.SECONDS);
                    CompletableFuture<Void> unload = adapter.unloadArtifact(ARTIFACT_ID);
                    assertEquals(ArtifactState.DRAINING,
                            adapter.artifact(ARTIFACT_ID).orElseThrow().state());

                    MountCoordinator.releaseCreate();
                    ComponentHandle<NoConfig> accepted = null;
                    try {
                        accepted = mount.join();
                    } catch (CompletionException expected) {
                        // Rejection is allowed; ownership is retained either way.
                    }
                    assertFalse(adapter.ownership(ARTIFACT_ID).isEmpty());
                    assertThrows(CompletionException.class, unload::join);
                    CleanupCoordinator.allowCleanup();
                    ArtifactSnapshot failed = adapter.artifact(ARTIFACT_ID).orElseThrow();
                    assertEquals(ArtifactState.DRAIN_FAILED, failed.state());
                    assertEquals("STARTED", failed.pf4jState());
                    assertTrue(adapter.ownership(ARTIFACT_ID).stream()
                            .anyMatch(ownership -> ownership.state() == ComponentState.FAILED));

                    adapter.retryDrain(ARTIFACT_ID).join();
                    assertEquals(ArtifactState.UNLOADED,
                            adapter.artifact(ARTIFACT_ID).orElseThrow().state());
                    if (accepted != null) {
                        assertEquals(ComponentState.DISPOSED, accepted.state());
                    }
                    assertTrue(runtime.snapshot().components().isEmpty());
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
            adapter.loadArtifact(fixture).join();
            ComponentHandle<NoConfig> handle = adapter.resolver()
                    .resolve("async-cleanup", NoConfig.class).orElseThrow()
                    .mount(runtime.rootContext(), "async", NoConfig.INSTANCE);
            assertEquals(ComponentState.ACTIVE, settle(handle));
            ControlledGate gate = runtime.context().require(GATE);
            assertFalse(gate.disposed());

            CompletableFuture<Void> unload = adapter.unloadArtifact(ARTIFACT_ID);
            assertEquals(ArtifactState.DRAINING,
                    adapter.artifact(ARTIFACT_ID).orElseThrow().state());
            assertEquals("STARTED", adapter.artifact(ARTIFACT_ID).orElseThrow().pf4jState());
            gate.release();
            unload.join();
            assertTrue(gate.disposed());
            assertEquals(ArtifactState.UNLOADED,
                    adapter.artifact(ARTIFACT_ID).orElseThrow().state());
        }
    }

    @Test
    void failedCleanupKeepsArtifactAndRetryDrainCompletesIt(@TempDir Path pluginsRoot) throws Exception {
        try (KnotraRuntime runtime = KnotraRuntime.create();
             Pf4jArtifactAdapter adapter = newAdapter(pluginsRoot, runtime)) {
            adapter.loadArtifact(fixture).join();
            CleanupCoordinator.reset();
            CleanupCoordinator.failNextCleanup();
            ComponentHandle<NoConfig> handle = adapter.resolver()
                    .resolve("failing-cleanup", NoConfig.class).orElseThrow()
                    .mount(runtime.rootContext(), "retry-cleanup", NoConfig.INSTANCE);
            assertEquals(ComponentState.ACTIVE, settle(handle));

            CompletableFuture<Void> unload = adapter.unloadArtifact(ARTIFACT_ID);
            assertThrows(CompletionException.class, unload::join);
            ArtifactSnapshot failed = adapter.artifact(ARTIFACT_ID).orElseThrow();
            assertEquals(ArtifactState.DRAIN_FAILED, failed.state());
            assertEquals("STARTED", failed.pf4jState());
            assertEquals(ComponentState.FAILED, handle.state());
            assertEquals(io.knotra.ComponentGoal.DISPOSED, handle.goal());

            CleanupCoordinator.allowCleanup();
            adapter.retryDrain(ARTIFACT_ID).join();
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
            adapter.loadArtifact(pluginsRoot.resolve("dependent.jar")).join();

            adapter.unloadArtifact(ARTIFACT_ID).join();

            assertEquals(ArtifactState.UNLOADED,
                    adapter.artifact(DEPENDENT_ID).orElseThrow().state());
            assertEquals(ArtifactState.UNLOADED,
                    adapter.artifact(ARTIFACT_ID).orElseThrow().state());
            assertTrue(adapter.factoryCatalog().isEmpty());
        }
    }

    @Test
    void concurrentDependentDrainsJoinTheSameClosure(@TempDir Path pluginsRoot) throws Exception {
        Files.copy(fixture, pluginsRoot.resolve("dependency.jar"));
        Files.copy(dependentFixture, pluginsRoot.resolve("dependent.jar"));
        try (KnotraRuntime runtime = KnotraRuntime.create();
             Pf4jArtifactAdapter adapter = newAdapter(pluginsRoot, runtime);
             ExecutorService executor = Executors.newFixedThreadPool(2)) {
            adapter.loadArtifact(pluginsRoot.resolve("dependent.jar")).join();

            CompletableFuture<Void> dependencyDrain = CompletableFuture.runAsync(
                    () -> adapter.unloadArtifact(ARTIFACT_ID).join(), executor);
            CompletableFuture<Void> dependentDrain = CompletableFuture.runAsync(
                    () -> adapter.unloadArtifact(DEPENDENT_ID).join(), executor);
            CompletableFuture.allOf(dependencyDrain, dependentDrain)
                    .get(20, TimeUnit.SECONDS);

            assertEquals(ArtifactState.UNLOADED,
                    adapter.artifact(DEPENDENT_ID).orElseThrow().state());
            assertEquals(ArtifactState.UNLOADED,
                    adapter.artifact(ARTIFACT_ID).orElseThrow().state());
            assertTrue(runtime.snapshot().components().isEmpty());
        }
    }

    @Test
    void snapshotOnlyArtifactRootBlocksUnloadAndKeepsClassLoader(@TempDir Path pluginsRoot) throws Exception {
        try (KnotraRuntime runtime = KnotraRuntime.create();
             Pf4jArtifactAdapter adapter = newAdapter(pluginsRoot, runtime)) {
            adapter.loadArtifact(fixture).join();
            var mount = runtime.mutate(mutation -> mutation.mount(
                    runtime.rootContext(),
                    "snapshot-only-root",
                    hostFactory(),
                    NoConfig.INSTANCE,
                    new MountOptions(ComponentOrigin.artifact(
                            ARTIFACT_ID, "1.0.0", "host-mounted artifact root"))));
            assertTrue(mount.committed(), () -> mount.diagnostics().toString());
            ComponentHandle<NoConfig> external = mount.value();
            assertEquals(ComponentState.ACTIVE, settle(external));

            CompletableFuture<Void> unload = adapter.unloadArtifact(ARTIFACT_ID);
            CompletionException failure = assertThrows(CompletionException.class, unload::join);
            ArtifactOperationException structured = assertInstanceOf(
                    ArtifactOperationException.class, failure.getCause());
            assertEquals("drain", structured.phase());

            ArtifactSnapshot blocked = adapter.artifact(ARTIFACT_ID).orElseThrow();
            assertEquals(ArtifactState.DRAIN_FAILED, blocked.state());
            assertEquals("STARTED", blocked.pf4jState());
            assertFalse(blocked.classLoaderDescription().isBlank());
            assertTrue(adapter.diagnostic(ARTIFACT_ID).isPresent());

            external.dispose().toCompletableFuture().get(10, TimeUnit.SECONDS);
            adapter.retryDrain(ARTIFACT_ID).join();
            assertEquals(ArtifactState.UNLOADED,
                    adapter.artifact(ARTIFACT_ID).orElseThrow().state());
        }
    }

    @Test
    void staleFactoryHandleCannotMountAfterDrain(@TempDir Path pluginsRoot) throws Exception {
        try (KnotraRuntime runtime = KnotraRuntime.create();
             Pf4jArtifactAdapter adapter = newAdapter(pluginsRoot, runtime)) {
            adapter.loadArtifact(fixture).join();
            ArtifactFactoryHandle<String> stale = adapter.resolver()
                    .resolve("alpha", String.class).orElseThrow();
            adapter.unloadArtifact(ARTIFACT_ID).join();

            ArtifactOperationException failure = assertThrows(
                    ArtifactOperationException.class,
                    () -> stale.mount(runtime.rootContext(), "stale", "value"));
            assertEquals("mount", failure.phase());
            assertTrue(runtime.snapshot().components().isEmpty());
        }
    }

    @Test
    void privateDescriptorIsRejectedBeforeCoreTypeMap(@TempDir Path pluginsRoot) throws Exception {
        try (KnotraRuntime runtime = KnotraRuntime.create();
             Pf4jArtifactAdapter adapter = newAdapter(pluginsRoot, runtime)) {
            adapter.loadArtifact(fixture).join();
            ArtifactFactoryHandle<NoConfig> handle = adapter.resolver()
                    .resolve("private-descriptor", NoConfig.class).orElseThrow();

            ArtifactOperationException failure = assertThrows(
                    ArtifactOperationException.class,
                    () -> handle.mount(runtime.rootContext(), "private", NoConfig.INSTANCE));
            assertTrue(failure.getMessage().contains("plugin-private"), failure::getMessage);
            assertTrue(runtime.snapshot().components().stream()
                    .noneMatch(item -> item.mountId().equals("private")));
            var hostRegistration = runtime.mutate(mutation -> mutation.provide(
                    runtime.rootContext(),
                    CapabilityKey.of("plugin-private-contract", String.class),
                    "host-owned"));
            assertTrue(hostRegistration.committed(), () -> hostRegistration.diagnostics().toString());
        }
    }

    @Test
    void privateDynamicProvideFailsActivationWithoutRegistration(@TempDir Path pluginsRoot) throws Exception {
        try (KnotraRuntime runtime = KnotraRuntime.create();
             Pf4jArtifactAdapter adapter = newAdapter(pluginsRoot, runtime)) {
            adapter.loadArtifact(fixture).join();
            ComponentHandle<NoConfig> handle = adapter.resolver()
                    .resolve("private-provide", NoConfig.class).orElseThrow()
                    .mount(runtime.rootContext(), "private-provide", NoConfig.INSTANCE);

            assertEquals(ComponentState.FAILED, settle(handle));
            assertTrue(runtime.snapshot().registrations().isEmpty());
            assertTrue(runtime.context().find(
                    CapabilityKey.of("plugin-private-contract", String.class)).isEmpty());
        }
    }

    @Test
    void privateChildContractIsRejectedDuringParentStart(@TempDir Path pluginsRoot) throws Exception {
        try (KnotraRuntime runtime = KnotraRuntime.create();
             Pf4jArtifactAdapter adapter = newAdapter(pluginsRoot, runtime)) {
            adapter.loadArtifact(fixture).join();
            ComponentHandle<NoConfig> parent = adapter.resolver()
                    .resolve("private-child", NoConfig.class).orElseThrow()
                    .mount(runtime.rootContext(), "private-parent", NoConfig.INSTANCE);

            assertEquals(ComponentState.FAILED, settle(parent));
            assertTrue(runtime.snapshot().components().stream()
                    .noneMatch(item -> item.mountId().equals("private-child")));
        }
    }

    @Test
    void snapshotsAndDiagnosticsContainOnlyStableText(@TempDir Path pluginsRoot) throws Exception {
        try (KnotraRuntime runtime = KnotraRuntime.create();
             Pf4jArtifactAdapter adapter = newAdapter(pluginsRoot, runtime)) {
            adapter.loadArtifact(fixture).join();
            ComponentHandle<String> stable = adapter.resolver()
                    .resolve("alpha", String.class).orElseThrow()
                    .mount(runtime.rootContext(), "stable", "value");
            ComponentHandle<NoConfig> parent = adapter.resolver()
                    .resolve("parent", NoConfig.class).orElseThrow()
                    .mount(runtime.rootContext(), "stable-parent", NoConfig.INSTANCE);
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
            adapter.loadArtifact(fixture).join();
            DefaultPf4jArtifactAdapter typed = assertInstanceOf(
                    DefaultPf4jArtifactAdapter.class, adapter);

            Integer result = typed.coordinateRead(() ->
                    typed.coordinateRead(() -> adapter.factoryCatalog().size()));

            assertEquals(10, result);
        }
    }

    @Test
    void concurrentCloseIsIdempotentAndDrainsEverything(@TempDir Path pluginsRoot) throws Exception {
        try (KnotraRuntime runtime = KnotraRuntime.create();
             Pf4jArtifactAdapter adapter = newAdapter(pluginsRoot, runtime);
             ExecutorService executor = Executors.newFixedThreadPool(2)) {
            adapter.loadArtifact(fixture).join();
            adapter.resolver().resolve("alpha", String.class).orElseThrow()
                    .mount(runtime.rootContext(), "close-me", "value");

            CompletableFuture<Void> first = CompletableFuture.supplyAsync(
                    () -> adapter.closeAsync().join(), executor);
            CompletableFuture<Void> second = CompletableFuture.supplyAsync(
                    () -> adapter.closeAsync().join(), executor);
            CompletableFuture.allOf(first, second).get(20, TimeUnit.SECONDS);

            assertTrue(runtime.snapshot().components().isEmpty());
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
                    adapter.loadArtifact(fixture).join();
                    ComponentHandle<NoConfig> handle = adapter.resolver()
                            .resolve("failing-cleanup", NoConfig.class).orElseThrow()
                            .mount(runtime.rootContext(),
                                    "close-retry-" + iteration,
                                    NoConfig.INSTANCE);
                    assertEquals(ComponentState.ACTIVE, settle(handle));

                    CompletableFuture<Void> firstClose = adapter.closeAsync();
                    assertThrows(CompletionException.class, firstClose::join);
                    assertEquals(ArtifactState.DRAIN_FAILED,
                            adapter.artifact(ARTIFACT_ID).orElseThrow().state());
                    assertEquals("drain-failed", adapter.diagnostic(ARTIFACT_ID)
                            .orElseThrow().transition());
                    assertFalse(adapter.ownership(ARTIFACT_ID).isEmpty());

                    CleanupCoordinator.allowCleanup();
                    adapter.closeAsync().join();
                    assertEquals(ArtifactState.UNLOADED,
                            adapter.artifact(ARTIFACT_ID).orElseThrow().state());
                    assertEquals(ComponentState.DISPOSED, handle.state());
                    assertTrue(runtime.snapshot().components().isEmpty());
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
            adapter.loadArtifact(fixture).join();
            adapter.resolver().resolve("alpha", String.class).orElseThrow()
                    .mount(runtime.rootContext(), "gc", "value");
            adapter.unloadArtifact(ARTIFACT_ID).join();

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
            assertThrows(CompletionException.class, () -> adapter.loadArtifact(
                    pluginsRoot.resolve("start-failure.jar")).join());
            assertCollectorsReachZero();
        } finally {
            ReferenceVault.clear();
        }
    }

    @Test
    void closeDrainsOwnedHandlesAndStopsPf4j(@TempDir Path pluginsRoot) throws Exception {
        try (KnotraRuntime runtime = KnotraRuntime.create();
             Pf4jArtifactAdapter adapter = newAdapter(pluginsRoot, runtime)) {
            adapter.loadArtifact(fixture).join();
            adapter.resolver().resolve("alpha", String.class).orElseThrow()
                    .mount(runtime.rootContext(), "drain-on-close", "value");

            adapter.closeAsync().join();

            assertEquals(ArtifactState.UNLOADED,
                    adapter.artifact(ARTIFACT_ID).orElseThrow().state());
            assertEquals("UNLOADED", adapter.artifact(ARTIFACT_ID).orElseThrow().pf4jState());
            assertTrue(runtime.snapshot().components().isEmpty());
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
                    adapter.loadArtifact(malformed).join());

            ArtifactOperationException structured = assertInstanceOf(
                    ArtifactOperationException.class, failure.getCause());
            assertEquals("load", structured.phase());
            assertTrue(adapter.factoryCatalog().isEmpty());
        }
    }

    @Test
    void unloadUnknownArtifactIsStructured(@TempDir Path pluginsRoot) throws Exception {
        try (KnotraRuntime runtime = KnotraRuntime.create();
             Pf4jArtifactAdapter adapter = newAdapter(pluginsRoot, runtime)) {
            CompletionException failure = assertThrows(CompletionException.class, () ->
                    adapter.unloadArtifact("absent").join());

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
            ArtifactSnapshot first = adapter.loadArtifact(fixture).join();
            adapter.unloadArtifact(ARTIFACT_ID).join();
            ArtifactSnapshot second = adapter.loadArtifact(fixture).join();

            assertEquals(first.artifactId(), second.artifactId());
            assertEquals(ArtifactState.ACTIVE, second.state());
            assertEquals(10, adapter.factoryCatalog().size());
        }
    }
    @Test
    void adapterCloseConvergesWhenRuntimeCloseAlreadyOwnsTheDisposal(
            @TempDir Path pluginsRoot) throws Exception {
        try (KnotraRuntime runtime = KnotraRuntime.create();
             Pf4jArtifactAdapter adapter = newAdapter(pluginsRoot, runtime)) {
            adapter.loadArtifact(fixture).join();
            ComponentHandle<NoConfig> handle = adapter.resolver()
                    .resolve("async-cleanup", NoConfig.class).orElseThrow()
                    .mount(runtime.rootContext(), "runtime-owned", NoConfig.INSTANCE);
            assertEquals(ComponentState.ACTIVE, settle(handle));
            ControlledGate gate = runtime.context().require(GATE);

            CompletableFuture<Void> runtimeClose =
                    runtime.closeAsync().toCompletableFuture();
            for (int attempt = 0;
                    attempt < 1000 && handle.state() != ComponentState.STOPPING;
                    attempt++) {
                tick();
            }
            assertEquals(ComponentState.STOPPING, handle.state());

            CompletableFuture<Void> adapterClose = adapter.closeAsync();
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
                        return ComponentDescriptor.of("host-artifact-root");
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

    private static ComponentState settle(ComponentHandle<?> handle) throws Exception {
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
            // Manifest-only fixtures are sufficient for repository resolution failures.
        }
    }
}
