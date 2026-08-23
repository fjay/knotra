package io.knotra.it;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.example.integration.contract.IntegrationCoordinator;
import io.knotra.ComponentDescriptor;
import io.knotra.ComponentState;
import io.knotra.KnotraRuntime;
import io.knotra.MountFactory;
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

final class LoaderPf4jBridgeIntegrationTest {

    private static final FactoryRef GREETING =
            FactoryRef.of("integration-greeting", "1.0.0");
    private static final FactoryRef PARENT =
            FactoryRef.of("integration-parent", "1.0.0");
    private static final FactoryRef IN_FLIGHT =
            FactoryRef.of("integration-in-flight", "1.0.0");
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
}
