package io.knotra;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MountFactoryTest {

    @Test
    void ofValidatesIdentityAndArguments() {
        ComponentDescriptor descriptor = ComponentDescriptor.named("component");

        assertThrows(IllegalArgumentException.class,
                () -> MountFactory.of(" ", descriptor, context -> { }));
        assertThrows(NullPointerException.class,
                () -> MountFactory.of("factory", null, context -> { }));
        assertThrows(NullPointerException.class,
                () -> MountFactory.of("factory", descriptor, null));
    }

    @Test
    void ofPassesIdentityAndDescriptorThrough() throws Exception {
        ComponentDescriptor descriptor = ComponentDescriptor.named("typed-component");
        MountFactory factory = MountFactory.of("  factory-id  ", descriptor, context -> { });

        assertEquals("factory-id", factory.factoryId());
        Component<NoConfig> component = factory.create();
        assertSame(descriptor, component.descriptor());

        try (KnotraRuntime runtime = KnotraRuntime.create()) {
            MountHandle handle = runtime.mount("mount", factory);
            handle.requireActive(Duration.ofSeconds(5));
            assertEquals("typed-component", handle.componentId());
        }
    }

    @Test
    void createReturnsIndependentAdapters() {
        MountFactory factory = MountFactory.of(
                "factory", ComponentDescriptor.named("component"), context -> { });

        Component<NoConfig> first = factory.create();
        Component<NoConfig> second = factory.create();

        assertNotSame(first, second);
        assertSame(first.descriptor(), second.descriptor());
    }

    @Test
    void checkedStartFailuresPropagateUnwrapped() throws Exception {
        ReflectiveOperationException startFailure =
                new ReflectiveOperationException("checked start failure");
        MountFactory factory = MountFactory.of(
                "factory",
                ComponentDescriptor.named("checked-failure"),
                context -> {
                    throw startFailure;
                });

        ReflectiveOperationException propagated = assertThrows(
                ReflectiveOperationException.class,
                () -> factory.create().start(null, NoConfig.INSTANCE));

        assertSame(startFailure, propagated);
    }

    @Test
    void factoryDoesNotImplicitlyRegisterCleanup() throws Exception {
        AtomicInteger cleanups = new AtomicInteger();
        MountFactory factory = MountFactory.of(
                "factory",
                ComponentDescriptor.named("unmanaged-resource"),
                context -> {
                    AutoCloseable unusedResource = cleanups::incrementAndGet;
                    assertTrue(unusedResource != null);
                });

        try (KnotraRuntime runtime = KnotraRuntime.create()) {
            MountHandle handle = runtime.mount("mount", factory);
            handle.requireActive(Duration.ofSeconds(5));

            RuntimeSnapshot.LifecycleScopeSnapshot lifecycle =
                    lifecycleScope(runtime, handle);
            assertEquals(0, lifecycle.entries().size(), () -> lifecycle.toString());

            handle.close();
        }

        assertEquals(0, cleanups.get());
    }

    @Test
    void lifecycleCleanupRunsOnlyForExplicitlyManagedResources() throws Exception {
        AtomicInteger cleanups = new AtomicInteger();
        MountFactory factory = MountFactory.of(
                "factory",
                ComponentDescriptor.named("managed-resource"),
                context -> {
                    AutoCloseable resource = cleanups::incrementAndGet;
                    context.lifecycle().manage("explicit-resource", resource);
                });

        try (KnotraRuntime runtime = KnotraRuntime.create()) {
            MountHandle handle = runtime.mount("mount", factory);
            handle.requireActive(Duration.ofSeconds(5));

            RuntimeSnapshot.LifecycleScopeSnapshot lifecycle =
                    lifecycleScope(runtime, handle);
            assertEquals(1, lifecycle.entries().size(), () -> lifecycle.toString());
            assertEquals("explicit-resource", lifecycle.entries().get(0).description());
            assertEquals(CleanupState.PENDING, lifecycle.entries().get(0).state());

            handle.close();
        }

        assertEquals(1, cleanups.get());
    }

    @Test
    void startFailureFailsActivationWithoutImplicitCleanup() throws Exception {
        AtomicInteger cleanups = new AtomicInteger();
        MountFactory factory = MountFactory.of(
                "factory",
                ComponentDescriptor.named("broken-component"),
                context -> {
                    AutoCloseable unusedResource = cleanups::incrementAndGet;
                    assertTrue(unusedResource != null);
                    throw new IllegalStateException("intentional start failure");
                });

        try (KnotraRuntime runtime = KnotraRuntime.create()) {
            MountHandle handle = runtime.mount("mount", factory);
            assertEquals(ComponentState.FAILED, handle.awaitSettled(Duration.ofSeconds(5)));

            List<RuntimeDiagnostic> diagnostics = runtime.advanced().snapshot().diagnostics();
            assertTrue(diagnostics.stream().anyMatch(item ->
                            item.code() == DiagnosticCode.ACTIVATION_FAILED
                                    && item.targetId().equals(handle.handleId())),
                    () -> diagnostics.toString());
        }

        assertEquals(0, cleanups.get());
    }

    @Test
    void ofDoesNotOverrideMountOrigin() throws Exception {
        MountFactory factory = MountFactory.of(
                "factory", ComponentDescriptor.named("component"), context -> { });
        ComponentOrigin origin = ComponentOrigin.artifact(
                "artifact-source", "9.8.7", "explicit origin");

        try (KnotraRuntime runtime = KnotraRuntime.create()) {
            MountHandle handle = runtime.mount(
                    "mount", factory, new MountOptions(origin));
            handle.requireActive(Duration.ofSeconds(5));

            RuntimeSnapshot.MountSnapshot snapshot = runtime.advanced().snapshot().mounts()
                    .stream()
                    .filter(item -> item.handleId().equals(handle.handleId()))
                    .findFirst()
                    .orElseThrow();
            assertEquals(ComponentOrigin.Kind.ARTIFACT, snapshot.origin().kind());
            assertEquals("artifact-source", snapshot.origin().sourceId());
            assertEquals("9.8.7", snapshot.origin().version());
        }
    }

    private static RuntimeSnapshot.LifecycleScopeSnapshot lifecycleScope(
            KnotraRuntime runtime,
            MountHandle handle) {
        RuntimeSnapshot snapshot = runtime.advanced().snapshot();
        String activationId = snapshot.mounts().stream()
                .filter(mount -> mount.handleId().equals(handle.handleId()))
                .findFirst()
                .orElseThrow()
                .currentActivationId();
        return snapshot.lifecycleScopes().stream()
                .filter(scope -> scope.activationId().equals(activationId))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "missing lifecycle scope for activation " + activationId));
    }
}
