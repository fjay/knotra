package io.knotra.it;

import java.nio.file.Path;
import java.util.Optional;

import io.knotra.ConfiguredMountHandle;
import io.knotra.DiagnosticCode;
import io.knotra.FailureInfo;
import io.knotra.KnotraConfig;
import io.knotra.KnotraRuntime;
import io.knotra.MountHandle;
import io.knotra.RuntimeDiagnostic;
import io.knotra.RuntimeSnapshot;
import io.knotra.loader.ComponentEntry;
import io.knotra.loader.ComponentTree;
import io.knotra.loader.FactoryRef;
import io.knotra.loader.KnotraLoader;
import io.knotra.loader.ReconcileResult;
import io.knotra.pf4j.Pf4jArtifactAdapter;
import io.knotra.pf4j.loader.Pf4jFactoryResolver;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class SnapshotClassLoaderFixture {

    static final FactoryRef GREETING = FactoryRef.of("integration-greeting");

    private SnapshotClassLoaderFixture() {
    }

    static KnotraConfig config() {
        return new KnotraConfig(
                "snapshot-gc",
                256,
                KnotraConfig.defaults().failureDetailPolicy().withStackTraces(true));
    }

    static Pf4jArtifactAdapter loadAdapter(Path pluginsRoot, KnotraRuntime runtime) {
        Pf4jArtifactAdapter adapter = IntegrationTestKit.adapter(pluginsRoot, runtime);
        adapter.loadArtifactAsync(IntegrationTestKit.fixture()).toCompletableFuture().join();
        return adapter;
    }

    static ConfiguredMountHandle<String> mountGreeting(
            Pf4jArtifactAdapter adapter,
            KnotraRuntime runtime) {
        return adapter.factories()
                .resolve("integration-greeting", String.class).orElseThrow()
                .mount(runtime.root(), "greeting", "hello");
    }

    static MountHandle mountParent(Pf4jArtifactAdapter adapter, KnotraRuntime runtime) {
        return adapter.factories()
                .resolveNoConfig("integration-parent").orElseThrow()
                .mount(runtime.root(), "parent");
    }

    static KnotraLoader loader(Pf4jArtifactAdapter adapter, KnotraRuntime runtime) {
        return KnotraLoader.over(runtime, runtime.root(), Pf4jFactoryResolver.of(adapter));
    }

    static ReconcileResult reconcileGreeting(KnotraLoader loader) {
        ReconcileResult result = loader.reconcile(ComponentTree.of(
                ComponentEntry.configured(
                        "snapshot-entry",
                        GREETING,
                        "snapshot")));
        assertTrue(result.converged(), () -> result.diagnostics().toString());
        return result;
    }

    static MountHandle mountFailingStart(Pf4jArtifactAdapter adapter, KnotraRuntime runtime) {
        return adapter.factories()
                .resolveNoConfig("integration-failing-start").orElseThrow()
                .mount(runtime.root(), "plugin-failure");
    }

    static FailureInfo failureInfo(KnotraRuntime runtime, MountHandle handle) {
        RuntimeSnapshot snapshot = runtime.advanced().snapshot();
        Optional<RuntimeDiagnostic> diagnostic = snapshot.diagnostics().stream()
                .filter(item -> item.code() == DiagnosticCode.ACTIVATION_FAILED
                        && item.targetId().equals(handle.handleId()))
                .findFirst();
        assertTrue(diagnostic.isPresent(), () -> snapshot.diagnostics().toString());
        return diagnostic.orElseThrow().failure();
    }
}
