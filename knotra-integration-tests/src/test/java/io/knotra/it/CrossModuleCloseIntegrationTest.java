package io.knotra.it;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.example.integration.contract.IntegrationCoordinator;
import io.knotra.ComponentHandle;
import io.knotra.ComponentState;
import io.knotra.KnotraRuntime;
import io.knotra.NoConfig;
import io.knotra.events.EventBusFactory;
import io.knotra.loader.ComponentEntry;
import io.knotra.loader.ComponentTree;
import io.knotra.loader.CompositeComponentFactoryResolver;
import io.knotra.loader.FactoryRef;
import io.knotra.loader.KnotraLoader;
import io.knotra.loader.ReconcileResult;
import io.knotra.pf4j.ArtifactState;
import io.knotra.pf4j.Pf4jArtifactAdapter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

final class CrossModuleCloseIntegrationTest {

    private static final FactoryRef GREETING = FactoryRef.of("integration-greeting");

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

    private ComponentHandle<NoConfig> mountBus() {
        var result = runtime.mutate(mutation -> mutation.mount(
                runtime.rootContext(), "bus", new EventBusFactory(), NoConfig.INSTANCE));
        assertTrue(result.committed(), () -> result.diagnostics().toString());
        return result.value();
    }

    @Test
    void concurrentClosesFromAllOwnersConvergeAndStayIdempotent(
            @TempDir Path pluginsRoot) throws Exception {
        Pf4jArtifactAdapter adapter = IntegrationTestKit.adapter(pluginsRoot, runtime);
        try {
            adapter.loadArtifact(IntegrationTestKit.fixture()).join();
            adapter.resolver().resolve("integration-greeting", String.class).orElseThrow()
                    .mount(runtime.rootContext(), "greeting", "hello");
            KnotraLoader loader = KnotraLoader.over(
                    runtime,
                    runtime.rootContext(),
                    CompositeComponentFactoryResolver.of(ref ->
                            IntegrationTestKit.bridge(adapter).apply(ref)));
            ComponentHandle<NoConfig> bus = mountBus();
            ReconcileResult reconcile = loader.reconcile(ComponentTree.of(
                    ComponentEntry.of("greeting", GREETING, "loader")));
            assertTrue(reconcile.converged(), () -> reconcile.diagnostics().toString());
            assertEquals(ComponentState.ACTIVE, IntegrationTestKit.settle(bus));

            try (ExecutorService executor = Executors.newFixedThreadPool(3)) {
                CompletableFuture<Void> adapterClose = CompletableFuture.runAsync(
                        () -> adapter.closeAsync().join(), executor);
                CompletableFuture<Void> loaderClose = CompletableFuture.runAsync(
                        () -> loader.closeAsync().toCompletableFuture().join(), executor);
                CompletableFuture<Void> runtimeClose = CompletableFuture.runAsync(
                        () -> runtime.closeAsync().toCompletableFuture().join(), executor);
                CompletableFuture.allOf(adapterClose, loaderClose, runtimeClose)
                        .get(60, TimeUnit.SECONDS);
            }

            adapter.closeAsync().toCompletableFuture().get(10, TimeUnit.SECONDS);
            loader.closeAsync().toCompletableFuture().get(10, TimeUnit.SECONDS);
            runtime.closeAsync().toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertEquals(ArtifactState.UNLOADED, adapter.artifact(
                    IntegrationTestKit.ARTIFACT_ID).orElseThrow().state());
            assertTrue(runtime.snapshot().components().isEmpty());
        } finally {
            adapter.close();
        }
    }

    @Test
    void reverseOrderClosesConvergeAndRepeatedCallsAreIdempotent(
            @TempDir Path pluginsRoot) throws Exception {
        Pf4jArtifactAdapter adapter = IntegrationTestKit.adapter(pluginsRoot, runtime);
        try {
            adapter.loadArtifact(IntegrationTestKit.fixture()).join();
            adapter.resolver().resolve("integration-greeting", String.class).orElseThrow()
                    .mount(runtime.rootContext(), "greeting", "hello");
            KnotraLoader loader = KnotraLoader.over(
                    runtime,
                    runtime.rootContext(),
                    CompositeComponentFactoryResolver.of(ref ->
                            IntegrationTestKit.bridge(adapter).apply(ref)));
            ReconcileResult reconcile = loader.reconcile(ComponentTree.of(
                    ComponentEntry.of("greeting", GREETING, "loader")));
            assertTrue(reconcile.converged(), () -> reconcile.diagnostics().toString());

            runtime.closeAsync().toCompletableFuture().get(30, TimeUnit.SECONDS);
            loader.close();
            adapter.close();

            runtime.closeAsync().toCompletableFuture().get(10, TimeUnit.SECONDS);
            loader.closeAsync().toCompletableFuture().get(10, TimeUnit.SECONDS);
            adapter.closeAsync().toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertEquals(ArtifactState.UNLOADED, adapter.artifact(
                    IntegrationTestKit.ARTIFACT_ID).orElseThrow().state());
        } finally {
            adapter.close();
        }
    }

    @Test
    void failedArtifactCleanupRetriesOnTheNextCloseAttempt(@TempDir Path pluginsRoot)
            throws Exception {
        Pf4jArtifactAdapter adapter = IntegrationTestKit.adapter(pluginsRoot, runtime);
        try {
            adapter.loadArtifact(IntegrationTestKit.fixture()).join();
            ComponentHandle<NoConfig> handle = adapter.resolver()
                    .resolve("integration-failing-cleanup", NoConfig.class).orElseThrow()
                    .mount(runtime.rootContext(), "failing", NoConfig.INSTANCE);
            assertEquals(ComponentState.ACTIVE, IntegrationTestKit.settle(handle));

            IntegrationCoordinator.failNextCleanup();
            CompletableFuture<Void> firstClose = adapter.closeAsync();
            assertThrows(CompletionException.class, firstClose::join);
            assertEquals(ArtifactState.DRAIN_FAILED, adapter.artifact(
                    IntegrationTestKit.ARTIFACT_ID).orElseThrow().state());
            assertEquals(ComponentState.FAILED, handle.state());
            assertFalse(adapter.ownership(IntegrationTestKit.ARTIFACT_ID).isEmpty());

            IntegrationCoordinator.allowCleanup();
            adapter.closeAsync().join();
            assertEquals(ArtifactState.UNLOADED, adapter.artifact(
                    IntegrationTestKit.ARTIFACT_ID).orElseThrow().state());
            assertEquals(ComponentState.DISPOSED, handle.state());
            assertTrue(runtime.snapshot().components().isEmpty());
        } finally {
            IntegrationCoordinator.allowCleanup();
            adapter.close();
        }
    }
}
