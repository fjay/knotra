package io.knotra.it;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.example.integration.contract.IntegrationCoordinator;
import io.knotra.ComponentState;
import io.knotra.KnotraRuntime;
import io.knotra.MountHandle;
import io.knotra.events.EventBusFactory;
import io.knotra.loader.ComponentEntry;
import io.knotra.loader.ComponentTree;
import io.knotra.pf4j.loader.Pf4jFactoryResolver;
import io.knotra.loader.FactoryRef;
import io.knotra.loader.KnotraLoader;
import io.knotra.loader.ReconcileResult;
import io.knotra.pf4j.ArtifactState;
import io.knotra.pf4j.Pf4jArtifactAdapter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ResourceLock(IntegrationTestKit.INTEGRATION_COORDINATOR_LOCK)
final class CrossModuleCloseIntegrationTest {

    private static final FactoryRef GREETING = FactoryRef.of("integration-greeting");

    @RegisterExtension
    private final KnotraIntegrationExtension runtimeExtension =
            KnotraIntegrationExtension.manualRuntimeClose();

    private MountHandle mountBus(KnotraRuntime runtime) {
        return runtime.mount("bus", new EventBusFactory());
    }

    @Test
    void concurrentClosesFromAllOwnersConvergeAndStayIdempotent(
            KnotraRuntime runtime,
            @TempDir Path pluginsRoot) throws Throwable {
        Pf4jArtifactAdapter adapter = null;
        KnotraLoader loader = null;
        Throwable testFailure = null;
        try {
            adapter = IntegrationTestKit.adapter(pluginsRoot, runtime);
            adapter.loadArtifactAsync(IntegrationTestKit.fixture()).toCompletableFuture().join();
            adapter.factories().resolve("integration-greeting", String.class).orElseThrow()
                    .mount(runtime.root(), "greeting", "hello");
            loader = KnotraLoader.over(
                    runtime,
                    runtime.root(),
                    Pf4jFactoryResolver.of(adapter));
            MountHandle bus = mountBus(runtime);
            ReconcileResult reconcile = loader.reconcile(ComponentTree.of(
                    ComponentEntry.configured("greeting", GREETING, "loader")));
            assertTrue(reconcile.converged(), () -> reconcile.diagnostics().toString());
            assertEquals(ComponentState.ACTIVE, bus.awaitSettled(Duration.ofSeconds(30)));

            final Pf4jArtifactAdapter coordinatedAdapter = adapter;
            final KnotraLoader coordinatedLoader = loader;
            try (ExecutorService executor = Executors.newFixedThreadPool(3)) {
                CompletableFuture<Void> adapterClose = CompletableFuture.runAsync(
                        () -> coordinatedAdapter.closeAsync().toCompletableFuture().join(),
                        executor);
                CompletableFuture<Void> loaderClose = CompletableFuture.runAsync(
                        () -> coordinatedLoader.closeAsync().toCompletableFuture().join(),
                        executor);
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
            assertTrue(runtime.advanced().snapshot().mounts().isEmpty());
        } catch (Throwable failure) {
            testFailure = failure;
        } finally {
            closeRemainingResources(testFailure, adapter, loader, runtime);
        }
        if (testFailure != null) {
            throw testFailure;
        }
    }

    @Test
    void reverseOrderClosesConvergeAndRepeatedCallsAreIdempotent(
            KnotraRuntime runtime,
            @TempDir Path pluginsRoot) throws Throwable {
        Pf4jArtifactAdapter adapter = null;
        KnotraLoader loader = null;
        Throwable testFailure = null;
        try {
            adapter = IntegrationTestKit.adapter(pluginsRoot, runtime);
            adapter.loadArtifactAsync(IntegrationTestKit.fixture()).toCompletableFuture().join();
            adapter.factories().resolve("integration-greeting", String.class).orElseThrow()
                    .mount(runtime.root(), "greeting", "hello");
            loader = KnotraLoader.over(
                    runtime,
                    runtime.root(),
                    Pf4jFactoryResolver.of(adapter));
            ReconcileResult reconcile = loader.reconcile(ComponentTree.of(
                    ComponentEntry.configured("greeting", GREETING, "loader")));
            assertTrue(reconcile.converged(), () -> reconcile.diagnostics().toString());

            runtime.closeAsync().toCompletableFuture().get(30, TimeUnit.SECONDS);
            loader.close();
            adapter.close();

            runtime.closeAsync().toCompletableFuture().get(10, TimeUnit.SECONDS);
            loader.closeAsync().toCompletableFuture().get(10, TimeUnit.SECONDS);
            adapter.closeAsync().toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertEquals(ArtifactState.UNLOADED, adapter.artifact(
                    IntegrationTestKit.ARTIFACT_ID).orElseThrow().state());
        } catch (Throwable failure) {
            testFailure = failure;
        } finally {
            closeRemainingResources(testFailure, adapter, loader, runtime);
        }
        if (testFailure != null) {
            throw testFailure;
        }
    }

    @Test
    void failedArtifactCleanupRetriesOnTheNextCloseAttempt(
            KnotraRuntime runtime, @TempDir Path pluginsRoot)
            throws Throwable {
        Pf4jArtifactAdapter adapter = null;
        Throwable testFailure = null;
        try {
            adapter = IntegrationTestKit.adapter(pluginsRoot, runtime);
            adapter.loadArtifactAsync(IntegrationTestKit.fixture()).toCompletableFuture().join();
            MountHandle handle = adapter.factories()
                    .resolveNoConfig("integration-failing-cleanup").orElseThrow()
                    .mount(runtime.root(), "failing");
            assertEquals(ComponentState.ACTIVE, handle.awaitSettled(Duration.ofSeconds(30)));

            IntegrationCoordinator.failNextCleanup();
            CompletableFuture<Void> firstClose = adapter.closeAsync().toCompletableFuture();
            assertThrows(CompletionException.class, firstClose::join);
            assertEquals(ArtifactState.DRAIN_FAILED, adapter.artifact(
                    IntegrationTestKit.ARTIFACT_ID).orElseThrow().state());
            assertEquals(ComponentState.FAILED, handle.state());
            assertFalse(adapter.ownership(IntegrationTestKit.ARTIFACT_ID).isEmpty());

            IntegrationCoordinator.allowCleanup();
            adapter.closeAsync().toCompletableFuture().join();
            assertEquals(ArtifactState.UNLOADED, adapter.artifact(
                    IntegrationTestKit.ARTIFACT_ID).orElseThrow().state());
            assertEquals(ComponentState.DISPOSED, handle.state());
            assertTrue(runtime.advanced().snapshot().mounts().isEmpty());
            runtime.close();
        } catch (Throwable failure) {
            testFailure = failure;
        } finally {
            IntegrationCoordinator.allowCleanup();
            closeRemainingResources(testFailure, adapter, runtime);
        }
        if (testFailure != null) {
            throw testFailure;
        }
    }

    private static void closeRemainingResources(
            Throwable testFailure,
            AutoCloseable... resources) throws Throwable {
        Throwable cleanupFailure = null;
        for (AutoCloseable resource : resources) {
            if (resource == null) {
                continue;
            }
            try {
                resource.close();
            } catch (Throwable failure) {
                cleanupFailure = appendCleanupFailure(cleanupFailure, failure);
            }
        }
        if (cleanupFailure != null) {
            if (testFailure == null) {
                throw cleanupFailure;
            }
            testFailure.addSuppressed(cleanupFailure);
        }
    }

    private static Throwable appendCleanupFailure(
            Throwable current,
            Throwable additional) {
        if (current == null) {
            return additional;
        }
        current.addSuppressed(additional);
        return current;
    }
}
