package io.knotra.it;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import com.example.integration.contract.IntegrationCoordinator;
import io.knotra.ComponentDescriptor;
import io.knotra.ComponentState;
import io.knotra.KnotraRuntime;
import io.knotra.MountFactory;
import io.knotra.PendingOperationsSnapshot;
import io.knotra.loader.ClasspathFactoryResolver;
import io.knotra.loader.ComponentEntry;
import io.knotra.loader.ComponentTree;
import io.knotra.loader.FactoryRef;
import io.knotra.loader.KnotraLoader;
import io.knotra.loader.LoaderDiagnosticCode;
import io.knotra.loader.ReconcileResult;
import io.knotra.pf4j.ArtifactState;
import io.knotra.pf4j.Pf4jArtifactAdapter;
import io.knotra.pf4j.loader.Pf4jFactoryResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

@org.junit.jupiter.api.parallel.ResourceLock(IntegrationTestKit.INTEGRATION_COORDINATOR_LOCK)
final class LoaderPf4jBridgeIntegrationTest {

    private static final FactoryRef GREETING =
            FactoryRef.of("integration-greeting", "1.0.0");
    private static final FactoryRef PARENT =
            FactoryRef.of("integration-parent", "1.0.0");
    private static final FactoryRef IN_FLIGHT =
            FactoryRef.of("integration-in-flight", "1.0.0");
    private static final FactoryRef START_GATED = FactoryRef.of("start-gated");
    private static final FactoryRef LOCAL = FactoryRef.of("local-recovery");
    private static final FactoryRef FLAKY = FactoryRef.of("flaky");

    @RegisterExtension
    private final KnotraIntegrationExtension runtimeExtension =
            KnotraIntegrationExtension.defaults();

    @Test
    void officialBridgeReconcilesNestedTreeWithDecoderIdentityAndOwnership(
            KnotraRuntime runtime,
            @TempDir Path pluginsRoot) throws Exception {
        try (Pf4jArtifactAdapter adapter = IntegrationTestKit.adapter(pluginsRoot, runtime)) {
            adapter.loadArtifactAsync(IntegrationTestKit.fixture()).toCompletableFuture().join();
            KnotraLoader loader = KnotraLoader.over(
                    runtime,
                    runtime.root(),
                    Pf4jFactoryResolver.of(adapter));
            try {
                ReconcileResult rejected = loader.reconcile(ComponentTree.of(
                        ComponentEntry.configured("greeting", GREETING, "   ")));
                assertFalse(rejected.converged());
                assertTrue(rejected.diagnostics().stream()
                        .anyMatch(item -> item.code() == LoaderDiagnosticCode.CONFIG_INVALID),
                        () -> rejected.diagnostics().toString());
                assertTrue(runtime.advanced().snapshot().mounts().isEmpty());

                ComponentTree desired = ComponentTree.of(ComponentEntry.configured(
                        "greeting", GREETING, "  hello  ",
                        ComponentEntry.of("parent", PARENT)));
                ReconcileResult first = loader.reconcile(desired);
                assertTrue(first.converged(), () -> first.diagnostics().toString());
                assertEquals(2, loader.snapshot().entries().size());

                var greeting = loader.snapshot().entry("greeting").orElseThrow();
                var parent = loader.snapshot().entry("greeting/parent").orElseThrow();
                assertEquals(ComponentState.ACTIVE, greeting.state());
                assertEquals(ComponentState.ACTIVE, parent.state());
                assertEquals("integration-greeting", greeting.factoryIdentity().factoryId());
                assertEquals("1.0.0", greeting.factoryIdentity().version());
                assertFalse(greeting.factoryIdentity().fingerprint().isBlank());
                assertEquals(1, greeting.configRevision());
                assertTrue(greeting.contextPath().endsWith("greeting"),
                        greeting.contextPath());
                assertTrue(parent.contextPath().endsWith("greeting/parent"),
                        parent.contextPath());
                assertTrue(runtime.advanced().snapshot().contexts().stream()
                        .anyMatch(context -> context.canonicalPath()
                                .endsWith("greeting/parent")));
                assertEquals(3, adapter.ownership(IntegrationTestKit.ARTIFACT_ID).size(),
                        "greeting, parent, and the artifact child must stay owned");

                ReconcileResult updated = loader.reconcile(ComponentTree.of(
                        ComponentEntry.configured("greeting", GREETING, "  next  ",
                                ComponentEntry.of("parent", PARENT))));
                assertTrue(updated.converged(), () -> updated.diagnostics().toString());
                assertTrue(updated.changes().stream()
                        .anyMatch(change -> change.type() == ReconcileResult.ChangeType.UPDATED
                                && change.path().equals("greeting")));
                assertEquals(2, loader.snapshot().entry("greeting")
                        .orElseThrow().configRevision());
            } finally {
                loader.close();
            }
        }
    }

    @Test
    void officialBridgeRejectsVersionMismatchBeforeCreatingStructure(
            KnotraRuntime runtime,
            @TempDir Path pluginsRoot) throws Exception {
        FactoryRef incompatible = FactoryRef.of("integration-greeting", "9.0.0");
        try (Pf4jArtifactAdapter adapter = IntegrationTestKit.adapter(pluginsRoot, runtime);
             KnotraLoader loader = KnotraLoader.over(
                     runtime, runtime.root(), Pf4jFactoryResolver.of(adapter))) {
            adapter.loadArtifact(IntegrationTestKit.fixture());

            ReconcileResult result = loader.reconcile(ComponentTree.of(
                    ComponentEntry.configured("greeting", incompatible, "hello")));

            assertFalse(result.converged());
            assertTrue(result.diagnostics().stream().anyMatch(
                    item -> item.code() == LoaderDiagnosticCode.RESOLUTION_FAILED));
            assertTrue(runtime.advanced().snapshot().mounts().isEmpty());
            assertTrue(loader.snapshot().entries().isEmpty());
        }
    }

    @Test
    void reconcileAndArtifactDrainRaceLeavesNoPartialStateAndConvergesOnRetry(
            KnotraRuntime runtime,
            @TempDir Path pluginsRoot) throws Exception {
        try (Pf4jArtifactAdapter adapter = IntegrationTestKit.adapter(pluginsRoot, runtime)) {
            adapter.loadArtifactAsync(IntegrationTestKit.fixture()).toCompletableFuture().join();
            MountFactory local = MountFactory.of(
                    "local-recovery",
                    ComponentDescriptor.named("local-recovery"),
                    context -> {
                    });
            ClasspathFactoryResolver classpath = ClasspathFactoryResolver.builder()
                    .add(LOCAL, local)
                    .build();
            KnotraLoader loader = KnotraLoader.over(
                    runtime,
                    runtime.root(),
                    Pf4jFactoryResolver.withFallbacks(adapter, classpath));
            try {
                var race = loader.reconcileAsync(
                        ComponentTree.of(ComponentEntry.of("gated", IN_FLIGHT)));
                IntegrationCoordinator.mountEntered().get(10, TimeUnit.SECONDS);

                CompletableFuture<Void> unload =
                        adapter.unloadArtifactAsync(IntegrationTestKit.ARTIFACT_ID)
                                .toCompletableFuture();
                await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(() ->
                        assertEquals(ArtifactState.DRAINING, adapter.artifact(
                                IntegrationTestKit.ARTIFACT_ID).orElseThrow().state()));

                IntegrationCoordinator.releaseMount();
                ReconcileResult rejected = race.toCompletableFuture().get(30, TimeUnit.SECONDS);
                assertFalse(rejected.converged());
                assertFalse(rejected.diagnostics().isEmpty());
                unload.get(30, TimeUnit.SECONDS);

                assertEquals(ArtifactState.UNLOADED, adapter.artifact(
                        IntegrationTestKit.ARTIFACT_ID).orElseThrow().state());
                assertTrue(runtime.advanced().snapshot().mounts().isEmpty(),
                        "the drained race must not leave a partial mount");
                assertTrue(loader.snapshot().entry("gated").isEmpty());

                ReconcileResult recovery = loader.reconcile(ComponentTree.of(
                        ComponentEntry.of("recovered", LOCAL)));
                assertTrue(recovery.converged(), () -> recovery.diagnostics().toString());
                assertEquals(ComponentState.ACTIVE, loader.snapshot()
                        .entry("recovered").orElseThrow().state());
            } finally {
                IntegrationCoordinator.releaseMount();
                loader.close();
            }
        }
    }

    @Test
    void blockedReconcileExposesPendingOperationsAcrossLoaderArtifactAndCore(
            KnotraRuntime runtime,
            @TempDir Path pluginsRoot) throws Exception {
        try (Pf4jArtifactAdapter adapter = IntegrationTestKit.adapter(pluginsRoot, runtime)) {
            adapter.loadArtifactAsync(IntegrationTestKit.fixture()).toCompletableFuture().join();
            KnotraLoader loader = KnotraLoader.over(
                    runtime,
                    runtime.root(),
                    Pf4jFactoryResolver.of(adapter));
            try {
                // 链 A：插件工厂在 create() 阻塞。此阶段结构视图尚未发布，
                // Loader 与适配器可见，core 侧无法稳定给出 handleId。
                CompletableFuture<ReconcileResult> reconcile = loader.reconcileAsync(
                                ComponentTree.of(ComponentEntry.of("gated", IN_FLIGHT)))
                        .toCompletableFuture();
                IntegrationCoordinator.mountEntered().get(10, TimeUnit.SECONDS);
                await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                    requireOperation(loader.pendingOperations(), item ->
                            item.kind() == PendingOperationsSnapshot.Kind.LOADER_OPERATION
                                    && item.targetId().equals("gated")
                                    && item.waitsFor() == PendingOperationsSnapshot.WaitType.USER_CALLBACK
                                    && item.detail().contains("phase=mount-execution")
                                    && item.detail().contains("path=gated"));
                    requireOperation(adapter.pendingOperations(), item ->
                            item.kind() == PendingOperationsSnapshot.Kind.ARTIFACT_MOUNT
                                    && item.targetId().equals(IntegrationTestKit.ARTIFACT_ID)
                                    && item.detail().contains("mountsInFlight=1"));
                });
                PendingOperationsSnapshot loaderWhileCreating = assertTimeout(
                        Duration.ofSeconds(1), loader::pendingOperations);
                PendingOperationsSnapshot artifactWhileCreating = assertTimeout(
                        Duration.ofSeconds(1), adapter::pendingOperations);
                PendingOperationsSnapshot.Operation createOperation = requireOperation(
                        loaderWhileCreating,
                        item -> item.kind() == PendingOperationsSnapshot.Kind.LOADER_OPERATION
                                && item.targetId().equals("gated"));
                assertTrue(createOperation.detail().contains("type=reconcile"),
                        loaderWhileCreating::render);
                assertFalse(createOperation.detail().isBlank(),
                        loaderWhileCreating::render);
                PendingOperationsSnapshot.Operation artifactMount = requireOperation(
                        artifactWhileCreating,
                        item -> item.kind() == PendingOperationsSnapshot.Kind.ARTIFACT_MOUNT
                                && item.targetId().equals(IntegrationTestKit.ARTIFACT_ID));
                assertEquals(PendingOperationsSnapshot.WaitType.MOUNTS_IN_FLIGHT,
                        artifactMount.waitsFor());

                IntegrationCoordinator.releaseMount();
                ReconcileResult converged = reconcile.get(30, TimeUnit.SECONDS);
                assertTrue(converged.converged(), () -> converged.diagnostics().toString());
                assertEquals(ComponentState.ACTIVE, loader.snapshot()
                        .entry("gated").orElseThrow().state());
                await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                    assertTrue(loader.pendingOperations().operations().isEmpty(),
                            () -> loader.pendingOperations().render());
                    assertTrue(adapter.pendingOperations().operations().isEmpty(),
                            () -> adapter.pendingOperations().render());
                });

                // 链 B：classpath 工厂在 start() 阻塞。挂载点已发布，
                // Loader 的 mount-settlement 与 core 的 transition 用同一 handleId 关联。
                CountDownLatch startEntered = new CountDownLatch(1);
                CompletableFuture<Void> startGate = new CompletableFuture<>();
                MountFactory startGated = MountFactory.of(
                        "start-gated",
                        ComponentDescriptor.named("start-gated"),
                        context -> {
                            startEntered.countDown();
                            startGate.join();
                        });
                KnotraLoader fallbackLoader = KnotraLoader.over(
                        runtime,
                        runtime.root(),
                        Pf4jFactoryResolver.withFallbacks(
                                adapter,
                                ClasspathFactoryResolver.builder()
                                        .add(START_GATED, startGated)
                                        .build()));
                try {
                    CompletableFuture<ReconcileResult> startReconcile =
                            fallbackLoader.reconcileAsync(ComponentTree.of(
                                            ComponentEntry.of("start-gated", START_GATED)))
                                    .toCompletableFuture();
                    assertTrue(startEntered.await(10, TimeUnit.SECONDS));
                    String startGatedHandleId = await().atMost(Duration.ofSeconds(10))
                            .until(() -> runtime.advanced().snapshot().mounts().stream()
                                            .filter(mount -> mount.mountId().equals("start-gated"))
                                            .findFirst()
                                            .map(mount -> mount.handleId())
                                            .orElse(""),
                                    handleId -> !handleId.isEmpty());
                    await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                        requireOperation(fallbackLoader.pendingOperations(), item ->
                                item.kind() == PendingOperationsSnapshot.Kind.LOADER_OPERATION
                                        && item.targetId().equals(startGatedHandleId)
                                        && item.waitsFor() == PendingOperationsSnapshot.WaitType.COMPONENT
                                        && item.detail().contains("phase=mount-settlement")
                                        && item.detail().contains("path=start-gated"));
                    requireOperation(runtime.advanced().pendingOperations(), item ->
                                item.kind() == PendingOperationsSnapshot.Kind.COMPONENT_TRANSITION
                                        && item.targetId().equals(startGatedHandleId)
                                        && item.waitsFor() == PendingOperationsSnapshot.WaitType.COMPONENT
                                        && item.detail().contains("activation"));
                    });
                    PendingOperationsSnapshot loaderWhileStarting = assertTimeout(
                            Duration.ofSeconds(1), fallbackLoader::pendingOperations);
                    PendingOperationsSnapshot coreWhileStarting = assertTimeout(
                            Duration.ofSeconds(1), () -> runtime.advanced().pendingOperations());
                    PendingOperationsSnapshot.Operation settleOperation = requireOperation(
                            loaderWhileStarting,
                            item -> item.kind() == PendingOperationsSnapshot.Kind.LOADER_OPERATION
                                    && item.targetId().equals(startGatedHandleId));
                    assertTrue(settleOperation.detail().contains("type=reconcile"),
                            loaderWhileStarting::render);
                    assertTrue(settleOperation.detail().contains("path=start-gated"),
                            loaderWhileStarting::render);
                    requireOperation(coreWhileStarting,
                            item -> item.kind() == PendingOperationsSnapshot.Kind.COMPONENT_TRANSITION
                                    && item.targetId().equals(startGatedHandleId));

                    startGate.complete(null);
                    ReconcileResult started =
                            startReconcile.get(30, TimeUnit.SECONDS);
                    assertTrue(started.converged(), () -> started.diagnostics().toString());
                    assertEquals(ComponentState.ACTIVE, fallbackLoader.snapshot()
                            .entry("start-gated").orElseThrow().state());
                    await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                            assertTrue(fallbackLoader.pendingOperations().operations().isEmpty(),
                                    () -> fallbackLoader.pendingOperations().render()));
                    await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                            assertTrue(runtime.advanced().pendingOperations().operations().stream()
                                            .noneMatch(item -> item.targetId()
                                                    .equals(startGatedHandleId)),
                                    () -> runtime.advanced().pendingOperations().render()));
                } finally {
                    startGate.complete(null);
                    fallbackLoader.close();
                }
            } finally {
                IntegrationCoordinator.releaseMount();
                loader.close();
            }
        }
    }

    @Test
    void loaderRetryConvergesAfterAFailedStart(KnotraRuntime runtime) throws Exception {
        AtomicInteger starts = new AtomicInteger();
        MountFactory flaky = MountFactory.of(
                "flaky",
                ComponentDescriptor.named("flaky"),
                context -> {
                    if (starts.incrementAndGet() == 1) {
                        throw new IllegalStateException("intentional first-start failure");
                    }
                    context.lifecycle().onClose("flaky-cleanup", () -> {
                    });
                });
        KnotraLoader loader = KnotraLoader.over(
                runtime,
                runtime.root(),
                ClasspathFactoryResolver.builder().add(FLAKY, flaky).build());
        try {
            ReconcileResult first = loader.reconcile(ComponentTree.of(
                    ComponentEntry.of("flaky", FLAKY)));
            assertFalse(first.converged());
            assertTrue(first.diagnostics().stream()
                    .anyMatch(item -> item.code() == LoaderDiagnosticCode.ACTIVATION_FAILED),
                    () -> first.diagnostics().toString());
            assertEquals(ComponentState.FAILED, loader.snapshot()
                    .entry("flaky").orElseThrow().state());

            ReconcileResult retry = loader.retry("flaky");
            assertTrue(retry.converged(), () -> retry.diagnostics().toString());
            assertEquals(ComponentState.ACTIVE, loader.snapshot()
                    .entry("flaky").orElseThrow().state());
            assertEquals(2, starts.get());
        } finally {
            loader.close();
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
