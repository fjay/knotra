package io.knotra;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

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
    void lifecycleCleanupRunsOnlyWhenStartRegistersIt() throws Exception {
        AtomicInteger cleanups = new AtomicInteger();
        MountFactory factory = MountFactory.of(
                "factory",
                ComponentDescriptor.named("clean-component"),
                context -> context.lifecycle().onClose("cleanup", cleanups::incrementAndGet));

        try (KnotraRuntime runtime = KnotraRuntime.create()) {
            MountHandle handle = runtime.mount("mount", factory);
            handle.requireActive(Duration.ofSeconds(5));
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
                    context.lifecycle().onClose("explicit-cleanup", cleanups::incrementAndGet);
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
}
