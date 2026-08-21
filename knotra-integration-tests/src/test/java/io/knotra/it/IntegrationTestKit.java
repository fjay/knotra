package io.knotra.it;

import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import com.example.integration.contract.IntegrationCoordinator;
import io.knotra.Component;
import io.knotra.ComponentDescriptor;
import io.knotra.ComponentFactory;
import io.knotra.ComponentHandle;
import io.knotra.ComponentState;
import io.knotra.ConfigSchema;
import io.knotra.ContextHandle;
import io.knotra.KnotraRuntime;
import io.knotra.NoConfig;
import io.knotra.loader.ControlledMountStrategy;
import io.knotra.loader.FactoryIdentity;
import io.knotra.loader.FactoryRef;
import io.knotra.loader.ReconfigureStrategy;
import io.knotra.loader.ResolvedComponentDefinition;
import io.knotra.pf4j.ArtifactFactoryHandle;
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

    static ComponentState settle(ComponentHandle<?> handle) throws Exception {
        return handle.whenSettled().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }

    /**
     * Bridges the typed artifact catalog into the loader's opaque resolver surface.
     * The loader receives no runtime, mutation, or executable factory.
     */
    static Function<FactoryRef, Optional<ResolvedComponentDefinition>> bridge(
            Pf4jArtifactAdapter adapter) {
        return ref -> {
            if ("integration-greeting".equals(ref.factoryId())) {
                return typedBridge(adapter, ref, String.class);
            }
            return typedBridge(adapter, ref, NoConfig.class);
        };
    }

    static <C> Optional<ResolvedComponentDefinition> typedBridge(
            Pf4jArtifactAdapter adapter,
            FactoryRef ref,
            Class<C> configType) {
        return adapter.resolver().resolve(ref.factoryId(), configType)
                .map(handle -> bridgeDefinition(ref, handle, configType));
    }

    static <C> ResolvedComponentDefinition bridgeDefinition(
            FactoryRef ref,
            ArtifactFactoryHandle<C> handle,
            Class<C> configType) {
        String fingerprint = handle.artifactId() + "@" + handle.artifactVersion()
                + "#" + handle.factoryId() + ":" + handle.configType().getName();
        ConfigSchema<Object> schema = raw -> {
            Optional<ConfigSchema<C>> selected = handle.configSchema();
            if (selected.isPresent()) {
                return selected.get().validate(raw);
            }
            return raw == null ? NoConfig.INSTANCE : raw;
        };
        ControlledMountStrategy strategy = (context, config) -> {
            try {
                C typedConfig = configType.cast(config);
                CompletionStage<ComponentHandle<C>> mounted = CompletableFuture.completedFuture(
                        handle.mount(context.context(), context.mountId(), typedConfig));
                return mounted.thenApply(handleValue -> (ComponentHandle<?>) handleValue);
            } catch (RuntimeException error) {
                return CompletableFuture.failedFuture(error);
            }
        };
        return new ResolvedComponentDefinition(
                FactoryIdentity.fromRef(ref, fingerprint),
                schema,
                strategy,
                ReconfigureStrategy.direct());
    }

    interface Start<C> {
        void start(io.knotra.ActivationContext context, C config) throws Exception;
    }

    static <C> ComponentFactory<C> classpathFactory(
            String id,
            Start<C> start,
            io.knotra.CapabilityRequirement... requirements) {
        ComponentDescriptor descriptor = ComponentDescriptor.of(id, requirements);
        return new ComponentFactory<>() {
            @Override
            public String factoryId() {
                return id;
            }

            @Override
            public Component<C> create() {
                return new Component<>() {
                    @Override
                    public ComponentDescriptor descriptor() {
                        return descriptor;
                    }

                    @Override
                    public void start(io.knotra.ActivationContext context, C config)
                            throws Exception {
                        start.start(context, config);
                    }
                };
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
