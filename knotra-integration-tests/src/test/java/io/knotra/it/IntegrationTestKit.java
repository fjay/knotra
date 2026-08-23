package io.knotra.it;

import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import com.example.integration.contract.IntegrationCoordinator;
import io.knotra.KnotraRuntime;
import io.knotra.pf4j.Pf4jArtifactAdapter;

final class IntegrationTestKit {
    static final String ARTIFACT_ID = "knotra-integration-plugin";
    static final String INTEGRATION_COORDINATOR_LOCK =
            "integration-coordinator";
    static final io.knotra.CapabilityKey<String> VALUE =
            io.knotra.CapabilityKey.of("integration.greeting", String.class);
    static final Set<String> SHARED_CONTRACTS = Set.of("com.example.integration.contract");
    private IntegrationTestKit() {
    }

    static Path fixture() {
        return Path.of(
                "target",
                "fixtures",
                "knotra-integration-tests-0.1.0-SNAPSHOT-integration-fixture.jar")
                .toAbsolutePath().normalize();
    }

    static Pf4jArtifactAdapter adapter(Path pluginsRoot, KnotraRuntime runtime) {
        return Pf4jArtifactAdapter.create(pluginsRoot, runtime, SHARED_CONTRACTS);
    }

    static CompletionStage<Void> drainIntegrations() {
        IntegrationCoordinator.releaseEvent();
        IntegrationCoordinator.releaseMount();
        IntegrationCoordinator.allowCleanup();
        return CompletableFuture.completedFuture(null);
    }
}
