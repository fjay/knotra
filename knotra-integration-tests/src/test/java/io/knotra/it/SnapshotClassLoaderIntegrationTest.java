package io.knotra.it;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.time.Duration;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import com.example.integration.contract.IntegrationCoordinator;
import io.knotra.ComponentState;
import io.knotra.DiagnosticCode;
import io.knotra.FailureInfo;
import io.knotra.KnotraConfig;
import io.knotra.KnotraRuntime;
import io.knotra.ConfiguredMountHandle;
import io.knotra.MountHandle;
import io.knotra.Publication;
import io.knotra.PublicationChange;
import io.knotra.PublicationState;
import io.knotra.RuntimeDiagnostic;
import io.knotra.RuntimeSnapshot;
import io.knotra.SettlementReport;
import io.knotra.events.EventBus;
import io.knotra.events.EventBusSnapshot;
import io.knotra.events.EventBusFactory;
import io.knotra.loader.ComponentEntry;
import io.knotra.loader.ComponentTree;
import io.knotra.pf4j.loader.Pf4jFactoryResolver;
import io.knotra.loader.FactoryRef;
import io.knotra.loader.KnotraLoader;
import io.knotra.loader.ReconcileResult;
import io.knotra.loader.LoaderSnapshot;
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
        runtime = KnotraRuntime.create(new KnotraConfig(
                "snapshot-gc",
                256,
                KnotraConfig.defaults().failureDetailPolicy().withStackTraces(true)));
    }

    @AfterEach
    void tearDown() throws Exception {
        IntegrationTestKit.drainIntegrations();
        runtime.close();
    }

    @Test
    void retainedSnapshotsReportsAndPublicationChangesDoNotPinThePluginClassLoader(
            @TempDir Path pluginsRoot) throws Exception {
        try (Pf4jArtifactAdapter adapter = IntegrationTestKit.adapter(pluginsRoot, runtime)) {
            adapter.loadArtifactAsync(IntegrationTestKit.fixture()).toCompletableFuture().join();

            ConfiguredMountHandle<String> greeting = adapter.factories()
                    .resolve("integration-greeting", String.class).orElseThrow()
                    .mount(runtime.root(), "greeting", "hello");
            MountHandle parent = adapter.factories()
                    .resolveNoConfig("integration-parent").orElseThrow()
                    .mount(runtime.root(), "parent");
            MountHandle busProvider = runtime.mount("bus", new EventBusFactory());
            assertEquals(ComponentState.ACTIVE, IntegrationTestKit.settle(greeting));
            assertEquals(ComponentState.ACTIVE, IntegrationTestKit.settle(parent));
            assertEquals(ComponentState.ACTIVE, IntegrationTestKit.settle(busProvider));
            EventBus bus = runtime.root().view().require(io.knotra.events.EventCapabilities.EVENT_BUS);

            KnotraLoader loader = KnotraLoader.over(
                    runtime,
                    runtime.root(),
                    Pf4jFactoryResolver.of(adapter));
            ReconcileResult reconcile = loader.reconcile(ComponentTree.of(
                    ComponentEntry.configured(
                            "snapshot-entry",
                            FactoryRef.of("integration-greeting"),
                            "snapshot")));
            assertTrue(reconcile.converged(), () -> reconcile.diagnostics().toString());

            MountHandle pluginFailure = adapter.factories()
                    .resolveNoConfig("integration-failing-start").orElseThrow()
                    .mount(runtime.root(), "plugin-failure");
            assertEquals(ComponentState.FAILED, IntegrationTestKit.settle(pluginFailure));

            PublicationChange<String> published = runtime.publish(String.class, "one");
            SettlementReport publishReport = published.awaitSettled(Duration.ofSeconds(10));
            Publication<String> publication = published.publication();
            PublicationChange<String> updated = publication.update("two");
            SettlementReport updateReport = updated.awaitSettled(Duration.ofSeconds(10));
            PublicationChange<String> unpublished = publication.unpublish();
            unpublished.awaitSettled(Duration.ofSeconds(10));
            assertEquals(PublicationState.UNPUBLISHED, publication.state());
            assertTrue(publishReport.generation() < updateReport.generation());
            assertTrue(unpublished.generation() > updateReport.generation());
            assertTrue(runtime.root().view().find(String.class).isEmpty());

            RuntimeSnapshot runtimeSnapshot = runtime.advanced().snapshot();
            LoaderSnapshot loaderSnapshot = loader.snapshot();
            EventBusSnapshot busSnapshot = bus.snapshot();
            FailureInfo pluginFailureInfo = runtimeSnapshot.diagnostics().stream()
                    .filter(item -> item.code() == DiagnosticCode.ACTIVATION_FAILED
                            && item.targetId().equals(pluginFailure.handleId()))
                    .map(RuntimeDiagnostic::failure)
                    .findFirst()
                    .orElseThrow();
            assertEquals("java.lang.IllegalStateException", pluginFailureInfo.exceptionType());
            assertEquals("intentional plugin activation failure", pluginFailureInfo.message());
            assertFalse(pluginFailureInfo.stackTrace().isEmpty());
            assertTrue(pluginFailureInfo.stackTrace().stream()
                    .anyMatch(frame -> frame.contains(
                            "com.example.integration.plugin.IntegrationRuntimeComponentProvider")),
                    () -> pluginFailureInfo.stackTrace().toString());
            loader.close();

            adapter.unloadArtifactAsync(IntegrationTestKit.ARTIFACT_ID)
                    .toCompletableFuture().get(30, TimeUnit.SECONDS);
            busProvider.disposeAsync().toCompletableFuture().get(10, TimeUnit.SECONDS);
            EventBusSnapshot closedBusSnapshot = bus.snapshot();
            assertTrue(closedBusSnapshot.closed());
            assertEquals(ArtifactState.UNLOADED, adapter.artifact(
                    IntegrationTestKit.ARTIFACT_ID).orElseThrow().state());
            assertTrue(runtime.advanced().snapshot().mounts().isEmpty());
            ArtifactSnapshot artifactSnapshot = adapter.artifact(
                    IntegrationTestKit.ARTIFACT_ID).orElseThrow();
            runtime.close();

            List<SettlementReport> reports = List.of(publishReport, updateReport);
            List<FailureInfo> failures = List.of(pluginFailureInfo);
            assertPureDtos(
                    runtimeSnapshot,
                    artifactSnapshot,
                    loaderSnapshot,
                    busSnapshot,
                    closedBusSnapshot,
                    reports,
                    failures);
            assertActivePublicationChange(unpublished);
            assertTrue(runtimeSnapshot.mounts().stream()
                    .anyMatch(mount -> mount.mountId().equals("plugin-failure")));
            assertTrue(artifactSnapshot.artifactId().equals(IntegrationTestKit.ARTIFACT_ID));
            assertFalse(busSnapshot.closed(), "the pre-dispose snapshot is immutable");
            assertTrue(closedBusSnapshot.closed(), "the post-dispose snapshot records closure");

            greeting = null;
            parent = null;
            busProvider = null;
            bus = null;

            await().atMost(Duration.ofSeconds(30))
                    .pollInterval(Duration.ofMillis(50))
                    .untilAsserted(() -> {
                        System.gc();
                        assertEquals(0, IntegrationCoordinator.liveLoaders(),
                                "retained stable DTOs must not retain the plugin class loader");
                    });
        } finally {
            IntegrationCoordinator.clearLoaders();
        }
    }

    private static void assertPureDtos(Object... roots) {
        IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
        for (Object root : roots) {
            scanGraph(root, seen, false);
        }
    }

    private static void assertActivePublicationChange(Object change) {
        scanGraph(change, new IdentityHashMap<>(), true);
    }

    private static void scanGraph(
            Object value,
            IdentityHashMap<Object, Boolean> seen,
            boolean activeHandleAllowsHostClass) {
        if (value == null || seen.put(value, Boolean.TRUE) != null) {
            return;
        }
        Class<?> type = value.getClass();
        assertFalse(value instanceof Throwable,
                "retained graph must not expose Throwable: " + type.getName());
        assertFalse(value instanceof ClassLoader,
                "retained graph must not expose ClassLoader: " + type.getName());
        assertFalse(type.getName().startsWith("com.example.integration.plugin."),
                "retained graph must not expose a plugin instance: " + type.getName());
        if (value instanceof Class<?> retainedClass) {
            assertFalse(retainedClass.getName().startsWith(
                            "com.example.integration.plugin."),
                    "active publication graph must not expose a plugin-private Class");
            if (!activeHandleAllowsHostClass) {
                fail("pure DTO graph must not expose Class: " + retainedClass.getName());
            }
            return;
        }
        if (type.getName().startsWith("java.")) {
            return;
        }
        if (type.isPrimitive()
                || value instanceof String
                || value instanceof Number
                || value instanceof Boolean
                || value instanceof Character
                || type.isEnum()
                || value instanceof java.time.temporal.TemporalAccessor) {
            return;
        }
        if (value instanceof Optional<?> optional) {
            scanGraph(optional.orElse(null), seen, activeHandleAllowsHostClass);
            return;
        }
        if (type.isArray()) {
            int length = Array.getLength(value);
            for (int index = 0; index < length; index++) {
                scanGraph(Array.get(value, index), seen, activeHandleAllowsHostClass);
            }
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                scanGraph(item, seen, activeHandleAllowsHostClass);
            }
            return;
        }
        for (Class<?> current = type; current != null && current != Object.class;
                current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (field.isSynthetic()) {
                    continue;
                }
                field.setAccessible(true);
                try {
                    scanGraph(field.get(value), seen, activeHandleAllowsHostClass);
                } catch (ReflectiveOperationException error) {
                    throw new AssertionError("cannot inspect retained graph field " + field, error);
                }
            }
        }
    }
}
