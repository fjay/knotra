package io.knotra.pf4j.loader;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import io.knotra.ComponentGoal;
import io.knotra.ComponentState;
import io.knotra.ConfiguredMountHandle;
import io.knotra.ContextHandle;
import io.knotra.ContextInfo;
import io.knotra.ContextState;
import io.knotra.ContextView;
import io.knotra.MountHandle;
import io.knotra.loader.ControlledMountContext;
import io.knotra.loader.FactoryRef;
import io.knotra.loader.ResolvedFactory;
import io.knotra.loader.ResolvedFactory.FactoryKind;
import io.knotra.pf4j.ArtifactDiagnostic;
import io.knotra.pf4j.ArtifactFactoryCatalog;
import io.knotra.pf4j.ArtifactFactoryCatalogEntry;
import io.knotra.pf4j.ArtifactFactoryHandle;
import io.knotra.pf4j.ArtifactOwnership;
import io.knotra.pf4j.ArtifactSnapshot;
import io.knotra.pf4j.ArtifactState;
import io.knotra.pf4j.Pf4jArtifactAdapter;
import io.knotra.MountOptions;
import io.knotra.ComponentFactory;
import io.knotra.NoConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class Pf4jFactoryResolverTest {

    @Test
    void noConfigFactoryIsResolvedThroughTheNoConfigMountView() throws Exception {
        FakeCatalog catalog = FakeCatalog.noConfig();
        ResolvedFactory definition = Pf4jFactoryResolver.of(new FakeAdapter(catalog))
                .resolve(FactoryRef.of("parent", "1.0.0"))
                .orElseThrow();

        assertEquals(
                "artifact@1.0.0:/plugins/factory.jar#parent:io.knotra.NoConfig",
                definition.identity().fingerprint());
        assertEquals(FactoryKind.PLAIN, definition.factoryKind());
        assertFalse(definition.configured());
        assertNotNull(definition.decodeConfig(null));

        FakeSlot slot = new FakeSlot();
        MountHandle mounted = definition.mountStrategy()
                .mountAsync(slot, definition.decodeConfig(null))
                .toCompletableFuture()
                .get(1, TimeUnit.SECONDS);

        assertEquals("allocated-mount", mounted.mountId());
        assertFalse(mounted instanceof ConfiguredMountHandle<?>);
        assertSame(slot.context(), catalog.noConfigHandle.mountedContext);
        assertEquals("allocated-mount", catalog.noConfigHandle.mountedMountId);

        CompletableFuture<ComponentState> reconfigured =
                definition.reconfigureStrategy()
                        .reconfigureAsync(mounted, NoConfig.INSTANCE)
                        .toCompletableFuture();
        ExecutionException rejected = assertThrows(
                ExecutionException.class,
                () -> reconfigured.get(1, TimeUnit.SECONDS));
        assertInstanceOf(UnsupportedOperationException.class, rejected.getCause());
        assertTrue(rejected.getCause().getMessage().contains(
                "plain factory does not accept configuration: mount-allocated-mount"));
    }

    @Test
    void configuredFactoryRestoresItsTypeAtTheSingleMountBoundary() throws Exception {
        FakeCatalog catalog = FakeCatalog.configured();
        ResolvedFactory definition = Pf4jFactoryResolver.of(new FakeAdapter(catalog))
                .resolve(FactoryRef.of("greeting"))
                .orElseThrow();

        assertEquals(FactoryKind.CONFIGURED, definition.factoryKind());
        assertTrue(definition.configured());
        assertEquals("decoded", definition.decodeConfig("decoded"));
        assertEquals("decoded", catalog.configuredHandle.decodedConfig);

        FakeSlot slot = new FakeSlot();
        Object loaderConfig = "loader-config";
        MountHandle mounted = definition.mountStrategy()
                .mountAsync(slot, loaderConfig)
                .toCompletableFuture()
                .get(1, TimeUnit.SECONDS);

        assertInstanceOf(ConfiguredMountHandle.class, mounted);
        assertSame(slot.context(), catalog.configuredHandle.mountedContext);
        assertEquals("allocated-mount", catalog.configuredHandle.mountedMountId);
        assertEquals("loader-config", catalog.configuredHandle.mountedConfig);

        assertEquals(ComponentState.ACTIVE, definition.reconfigureStrategy()
                .reconfigureAsync(mounted, "next-config")
                .toCompletableFuture()
                .get(1, TimeUnit.SECONDS));
        assertEquals("next-config",
                ((TestConfiguredMount<?>) mounted).reconfiguredConfig);
    }

    @Test
    void wrongConfigTypeFailsTheMountFutureInsteadOfEscapingTheBoundary() {
        FakeCatalog catalog = FakeCatalog.configured();
        ResolvedFactory definition = Pf4jFactoryResolver.of(new FakeAdapter(catalog))
                .resolve(FactoryRef.of("greeting"))
                .orElseThrow();

        assertEquals(FactoryKind.CONFIGURED, definition.factoryKind());
        CompletableFuture<MountHandle> mount = definition.mountStrategy()
                .mountAsync(new FakeSlot(), Integer.valueOf(7))
                .toCompletableFuture();

        assertTrue(mount.isCompletedExceptionally());
        ExecutionException failure = assertThrows(
                ExecutionException.class,
                () -> mount.get(1, TimeUnit.SECONDS));
        assertInstanceOf(ClassCastException.class, failure.getCause());
        assertFalse(catalog.configuredHandle.mountCalled);
    }

    private static final class FakeAdapter implements Pf4jArtifactAdapter {
        private final ArtifactFactoryCatalog factories;

        private FakeAdapter(ArtifactFactoryCatalog factories) {
            this.factories = factories;
        }

        @Override
        public ArtifactFactoryCatalog factories() {
            return factories;
        }

        @Override
        public CompletionStage<ArtifactSnapshot> loadArtifactAsync(Path artifactPath) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletionStage<Void> unloadArtifactAsync(String artifactId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletionStage<Void> retryDrainAsync(String artifactId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<ArtifactSnapshot> artifacts() {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<ArtifactSnapshot> artifactsInState(ArtifactState state) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<ArtifactSnapshot> artifact(String artifactId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<ArtifactDiagnostic> diagnostic(String artifactId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<ArtifactOwnership> ownership(String artifactId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletionStage<Void> closeAsync() {
            return CompletableFuture.completedFuture(null);
        }
    }

    private static final class FakeCatalog implements ArtifactFactoryCatalog {
        private final RootHandle root;
        private final NoConfigHandle noConfigHandle;
        private final ConfiguredHandle<String> configuredHandle;

        private FakeCatalog(
                RootHandle root,
                NoConfigHandle noConfigHandle,
                ConfiguredHandle<String> configuredHandle) {
            this.root = root;
            this.noConfigHandle = noConfigHandle;
            this.configuredHandle = configuredHandle;
        }

        static FakeCatalog noConfig() {
            return new FakeCatalog(
                    new RootHandle(NoConfig.class),
                    new NoConfigHandle(),
                    null);
        }

        static FakeCatalog configured() {
            return new FakeCatalog(
                    new RootHandle(String.class),
                    null,
                    new ConfiguredHandle<>(String.class));
        }

        @Override
        public List<ArtifactFactoryCatalogEntry> list() {
            return List.of(root);
        }

        @Override
        public Optional<ArtifactFactoryCatalogEntry> find(String factoryId) {
            return Optional.of(root);
        }

        @Override
        public Optional<ArtifactFactoryHandle> resolve(String factoryId) {
            return Optional.of(root);
        }

        @Override
        public Optional<ArtifactFactoryHandle.NoConfig> resolveNoConfig(String factoryId) {
            return Optional.ofNullable(noConfigHandle);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <C> Optional<ArtifactFactoryHandle.Configured<C>> resolve(
                String factoryId,
                Class<C> configType) {
            if (configuredHandle == null || configuredHandle.configType() != configType) {
                return Optional.empty();
            }
            return Optional.of((ArtifactFactoryHandle.Configured<C>) configuredHandle);
        }
    }

    private abstract static class MetadataHandle implements ArtifactFactoryHandle {
        @Override
        public String artifactId() {
            return "artifact";
        }

        @Override
        public String artifactVersion() {
            return "1.0.0";
        }

        @Override
        public String artifactPath() {
            return "/plugins/factory.jar";
        }

        @Override
        public String factoryId() {
            return rootFactoryId();
        }

        @Override
        public String configTypeName() {
            return configType().getName();
        }

        protected abstract String rootFactoryId();
    }

    private static final class RootHandle extends MetadataHandle {
        private final Class<?> configType;

        private RootHandle(Class<?> configType) {
            this.configType = configType;
        }

        @Override
        public Class<?> configType() {
            return configType;
        }

        @Override
        protected String rootFactoryId() {
            return configType == io.knotra.NoConfig.class ? "parent" : "greeting";
        }
    }


    private static final class NoConfigHandle extends MetadataHandle
            implements ArtifactFactoryHandle.NoConfig {
        private ContextHandle mountedContext;
        private String mountedMountId;

        @Override
        public Class<?> configType() {
            return io.knotra.NoConfig.class;
        }

        @Override
        protected String rootFactoryId() {
            return "parent";
        }

        @Override
        public MountHandle mount(ContextHandle context, String mountId) {
            mountedContext = context;
            mountedMountId = mountId;
            return new TestMount(mountId);
        }
    }

    private static final class ConfiguredHandle<C> extends MetadataHandle
            implements ArtifactFactoryHandle.Configured<C> {
        private final Class<C> configType;
        private Object decodedConfig;
        private ContextHandle mountedContext;
        private String mountedMountId;
        private C mountedConfig;
        private boolean mountCalled;

        private ConfiguredHandle(Class<C> configType) {
            this.configType = configType;
        }

        @Override
        public Class<?> configType() {
            return configType;
        }

        @Override
        protected String rootFactoryId() {
            return "greeting";
        }

        @Override
        public C decodeConfig(Object rawConfig) {
            decodedConfig = rawConfig;
            return configType.cast(rawConfig);
        }

        @Override
        public ConfiguredMountHandle<C> mount(
                ContextHandle context,
                String mountId,
                C config) {
            mountCalled = true;
            mountedContext = context;
            mountedMountId = mountId;
            mountedConfig = config;
            return new TestConfiguredMount<>(mountId, config);
        }
    }

    private record TestMount(String mountId) implements MountHandle {
        @Override
        public String handleId() {
            return "mount-" + mountId;
        }

        @Override
        public String componentId() {
            return "component-" + mountId;
        }

        @Override
        public String factoryId() {
            return "test-factory";
        }

        @Override
        public String contextId() {
            return "test-context";
        }

        @Override
        public ComponentState state() {
            return ComponentState.ACTIVE;
        }

        @Override
        public ComponentGoal goal() {
            return ComponentGoal.RUNNING;
        }

        @Override
        public long configRevision() {
            return 0;
        }

        @Override
        public CompletionStage<ComponentState> whenSettled() {
            return CompletableFuture.completedFuture(ComponentState.ACTIVE);
        }

        @Override
        public CompletionStage<ComponentState> retryAsync() {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletionStage<ComponentState> disposeAsync() {
            return CompletableFuture.completedFuture(ComponentState.DISPOSED);
        }
    }

    private static final class TestConfiguredMount<C> implements ConfiguredMountHandle<C> {
        private final String mountId;
        private final C config;
        private Object reconfiguredConfig;

        private TestConfiguredMount(String mountId, C config) {
            this.mountId = mountId;
            this.config = config;
        }

        @Override
        public CompletionStage<ComponentState> reconfigureAsync(C config) {
            reconfiguredConfig = config;
            return CompletableFuture.completedFuture(ComponentState.ACTIVE);
        }

        @Override
        public String handleId() {
            return "configured-mount-" + mountId;
        }

        @Override
        public String mountId() {
            return mountId;
        }

        @Override
        public String componentId() {
            return "component-" + mountId;
        }

        @Override
        public String factoryId() {
            return "test-factory";
        }

        @Override
        public String contextId() {
            return "test-context";
        }

        @Override
        public ComponentState state() {
            return ComponentState.ACTIVE;
        }

        @Override
        public ComponentGoal goal() {
            return ComponentGoal.RUNNING;
        }

        @Override
        public long configRevision() {
            return config == null ? 0 : 1;
        }

        @Override
        public CompletionStage<ComponentState> whenSettled() {
            return CompletableFuture.completedFuture(ComponentState.ACTIVE);
        }

        @Override
        public CompletionStage<ComponentState> retryAsync() {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletionStage<ComponentState> disposeAsync() {
            return CompletableFuture.completedFuture(ComponentState.DISPOSED);
        }
    }

    private static final class FakeSlot implements ControlledMountContext {
        private final ContextHandle context = new FakeContext();

        @Override
        public ContextHandle context() {
            return context;
        }

        @Override
        public String mountId() {
            return "allocated-mount";
        }

        @Override
        public CompletionStage<MountHandle> mountAsync(
                ComponentFactory<NoConfig> factory,
                MountOptions options) {
            throw new AssertionError("PF4J bridge must call its own plain artifact handle");
        }

        @Override
        public <C> CompletionStage<io.knotra.ConfiguredMountHandle<C>> mountAsync(
                ComponentFactory<C> factory,
                C config,
                MountOptions options) {
            throw new AssertionError("PF4J bridge must call its own typed artifact handle");
        }
    }

    private static final class FakeContext implements ContextHandle {
        @Override
        public String contextId() {
            return "allocated-context";
        }

        @Override
        public ContextInfo info() {
            throw new UnsupportedOperationException();
        }

        @Override
        public ContextView view() {
            throw new UnsupportedOperationException();
        }

        @Override
        public ContextState state() {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletionStage<ContextState> disposeAsync() {
            throw new UnsupportedOperationException();
        }
    }
}
