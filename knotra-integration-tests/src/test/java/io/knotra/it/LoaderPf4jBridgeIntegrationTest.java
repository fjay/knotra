package io.knotra.it;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.example.integration.contract.IntegrationCoordinator;
import io.knotra.ComponentFactory;
import io.knotra.ComponentState;
import io.knotra.KnotraRuntime;
import io.knotra.NoConfig;
import io.knotra.loader.ClasspathComponentFactoryResolver;
import io.knotra.loader.ComponentEntry;
import io.knotra.loader.ComponentFactoryResolver;
import io.knotra.loader.ComponentTree;
import io.knotra.loader.CompositeComponentFactoryResolver;
import io.knotra.loader.FactoryRef;
import io.knotra.loader.KnotraLoader;
import io.knotra.loader.LoaderDiagnosticCode;
import io.knotra.loader.ReconcileResult;
import io.knotra.pf4j.ArtifactState;
import io.knotra.pf4j.Pf4jArtifactAdapter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

    private static ComponentFactoryResolver artifactBridge(Pf4jArtifactAdapter adapter) {
        return ref -> IntegrationTestKit.bridge(adapter).apply(ref);
    }

    @Test
    void opaqueBridgeReconcilesNestedTreeWithSchemaIdentityAndOwnership(
            @TempDir Path pluginsRoot) throws Exception {
        try (Pf4jArtifactAdapter adapter = IntegrationTestKit.adapter(pluginsRoot, runtime)) {
            adapter.loadArtifact(IntegrationTestKit.fixture()).join();
            KnotraLoader loader = KnotraLoader.over(
                    runtime,
                    runtime.rootContext(),
                    CompositeComponentFactoryResolver.of(artifactBridge(adapter)));
            try {
                ReconcileResult rejected = loader.reconcile(ComponentTree.of(
                        ComponentEntry.of("greeting", GREETING, "   ")));
                assertFalse(rejected.converged());
                assertTrue(rejected.diagnostics().stream()
                        .anyMatch(item -> item.code() == LoaderDiagnosticCode.CONFIG_INVALID),
                        () -> rejected.diagnostics().toString());
                assertTrue(runtime.snapshot().components().isEmpty());

                ComponentTree desired = ComponentTree.of(ComponentEntry.of(
                        "greeting", GREETING, "  hello  ",
                        ComponentEntry.of("parent", PARENT, NoConfig.INSTANCE)));
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
                assertTrue(runtime.snapshot().contexts().stream()
                        .anyMatch(context -> context.canonicalPath()
                                .endsWith("greeting/parent")));
                assertEquals(3, adapter.ownership(IntegrationTestKit.ARTIFACT_ID).size(),
                        "greeting, parent, and the artifact child must stay owned");

                ReconcileResult updated = loader.reconcile(ComponentTree.of(
                        ComponentEntry.of("greeting", GREETING, "  next  ",
                                ComponentEntry.of("parent", PARENT, NoConfig.INSTANCE))));
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
    void reconcileAndArtifactDrainRaceLeavesNoPartialStateAndConvergesOnRetry(
            @TempDir Path pluginsRoot) throws Exception {
        try (Pf4jArtifactAdapter adapter = IntegrationTestKit.adapter(pluginsRoot, runtime)) {
            adapter.loadArtifact(IntegrationTestKit.fixture()).join();
            ComponentFactory<Object> local = IntegrationTestKit.classpathFactory(
                    "local-recovery", (context, config) -> {
                    });
            ComponentFactoryResolver classpath = ClasspathComponentFactoryResolver.builder()
                    .add(LOCAL, local)
                    .build();
            KnotraLoader loader = KnotraLoader.over(
                    runtime,
                    runtime.rootContext(),
                    CompositeComponentFactoryResolver.of(artifactBridge(adapter), classpath));
            try {
                var race = loader.reconcileAsync(
                        ComponentTree.of(ComponentEntry.of(
                                "gated", IN_FLIGHT, NoConfig.INSTANCE)));
                IntegrationCoordinator.mountEntered().get(10, TimeUnit.SECONDS);

                CompletableFuture<Void> unload =
                        adapter.unloadArtifact(IntegrationTestKit.ARTIFACT_ID);
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
                assertTrue(runtime.snapshot().components().isEmpty(),
                        "the drained race must not leave a partial mount");
                assertTrue(loader.snapshot().entry("gated").isEmpty());

                ReconcileResult recovery = loader.reconcile(ComponentTree.of(
                        ComponentEntry.of("recovered", LOCAL, NoConfig.INSTANCE)));
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
    void loaderRetryConvergesAfterAFailedStart() throws Exception {
        AtomicInteger starts = new AtomicInteger();
        ComponentFactory<NoConfig> flaky = IntegrationTestKit.classpathFactory(
                "flaky",
                (context, config) -> {
                    if (starts.incrementAndGet() == 1) {
                        throw new IllegalStateException("intentional first-start failure");
                    }
                    context.lifecycle().onClose("flaky-cleanup", () -> {
                    });
                });
        KnotraLoader loader = KnotraLoader.over(
                runtime,
                runtime.rootContext(),
                ClasspathComponentFactoryResolver.builder().add(FLAKY, flaky).build());
        try {
            ReconcileResult first = loader.reconcile(ComponentTree.of(
                    ComponentEntry.of("flaky", FLAKY, NoConfig.INSTANCE)));
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
