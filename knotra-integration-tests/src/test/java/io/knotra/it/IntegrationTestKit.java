package io.knotra.it;

import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import com.example.integration.contract.IntegrationCoordinator;
import io.knotra.ActivationContext;
import io.knotra.Component;
import io.knotra.ComponentDescriptor;
import io.knotra.ComponentState;
import io.knotra.KnotraRuntime;
import io.knotra.MountFactory;
import io.knotra.MountHandle;
import io.knotra.pf4j.Pf4jArtifactAdapter;

final class IntegrationTestKit {
    static final String ARTIFACT_ID = "knotra-integration-plugin";
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

    static ComponentState settle(MountHandle handle) throws Exception {
        return handle.whenSettled().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }

    interface Start {
        void start(ActivationContext context) throws Exception;
    }

    @SuppressWarnings("rawtypes")
    static MountFactory classpathFactory(String id, Start start) {
        Component component = new Component() {
            @Override
            public ComponentDescriptor descriptor() {
                return ComponentDescriptor.named(id);
            }

            @Override
            public void start(ActivationContext context, Object config) throws Exception {
                start.start(context);
            }
        };
        return new MountFactory() {
            @Override
            public String factoryId() {
                return id;
            }

            @Override
            @SuppressWarnings("unchecked")
            public Component create() {
                return component;
            }
        };
    }

    static CompletionStage<Void> drainIntegrations() {
        IntegrationCoordinator.releaseEvent();
        IntegrationCoordinator.releaseMount();
        IntegrationCoordinator.allowCleanup();
        return CompletableFuture.completedFuture(null);
    }
}
