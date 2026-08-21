package io.knotra.loader;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.knotra.CapabilityKey;
import io.knotra.CapabilityRequirement;
import io.knotra.ComponentFactory;
import io.knotra.ComponentState;
import io.knotra.ConfigSchema;
import io.knotra.KnotraRuntime;
import io.knotra.NoConfig;
import io.knotra.RegistrationHandle;

import static org.junit.jupiter.api.Assertions.*;

final class LifecycleReconcileTest {

    static final CapabilityKey<String> TEXT = CapabilityKey.of("text", String.class);

    private final KnotraRuntime runtime = KnotraRuntime.create();

    @AfterEach
    void tearDown() throws Exception {
        runtime.close();
    }

    @Test
    void normalizedConfigChangeReusesHandle() throws Exception {
        FactoryRef ref = FactoryRef.of("configured");
        List<String> configs = new CopyOnWriteArrayList<>();
        ComponentFactory<String> factory = LoaderTestKit.factory(
                "configured", (context, config) -> configs.add(config));
        ConfigSchema<String> schema = raw -> String.valueOf(raw).trim();
        KnotraLoader loader = KnotraLoader.over(runtime, runtime.rootContext(),
                LoaderTestKit.resolver(ref, factory, schema));
        try {
            LoaderTestKit.assertAccepted(loader.reconcile(ComponentTree.of(
                    LoaderTestKit.entry("alpha", ref, " one "))));
            var first = loader.snapshot().entry("alpha").orElseThrow();

            LoaderTestKit.assertAccepted(loader.reconcile(ComponentTree.of(
                    LoaderTestKit.entry("alpha", ref, " one "))));
            assertEquals(1, first.configRevision());

            LoaderTestKit.assertAccepted(loader.reconcile(ComponentTree.of(
                    LoaderTestKit.entry("alpha", ref, " two "))));
            var second = loader.snapshot().entry("alpha").orElseThrow();
            assertEquals(first.handleId(), second.handleId());
            assertEquals(2, second.configRevision());
            assertEquals(List.of("one", "two"), configs);
        } finally {
            loader.close();
        }
    }

    @Test
    void waitingDeclarationKeepsLatestConfigForLaterActivation() throws Exception {
        FactoryRef ref = FactoryRef.of("consumer");
        List<String> configs = new CopyOnWriteArrayList<>();
        ComponentFactory<String> factory = LoaderTestKit.factory("consumer", (context, config) -> {
            assertNotNull(context.require(TEXT));
            configs.add(config);
        }, CapabilityRequirement.required(TEXT));
        KnotraLoader loader = KnotraLoader.over(runtime, runtime.rootContext(),
                LoaderTestKit.resolver(ref, factory));
        try {
            var waiting = loader.reconcile(ComponentTree.of(
                    LoaderTestKit.entry("alpha", ref, "one")));
            assertTrue(waiting.converged());
            assertEquals(ComponentState.WAITING, loader.snapshot().entry("alpha").orElseThrow().state());

            LoaderTestKit.assertAccepted(loader.reconcile(ComponentTree.of(
                    LoaderTestKit.entry("alpha", ref, "two"))));
            provide("root-value");
            assertEquals(ComponentState.ACTIVE, loader.snapshot().entry("alpha").orElseThrow().state());
            assertEquals(List.of("two"), configs);
        } finally {
            loader.close();
        }
    }

    @Test
    void failedActivationIsNotAutomaticallyRetried() throws Exception {
        FactoryRef ref = FactoryRef.of("bad");
        AtomicInteger attempts = new AtomicInteger();
        var factory = LoaderTestKit.factory("bad", (context, config) -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("temporary");
        });
        KnotraLoader loader = KnotraLoader.over(runtime, runtime.rootContext(),
                LoaderTestKit.resolver(ref, factory));
        try {
            var first = loader.reconcile(ComponentTree.of(
                    LoaderTestKit.entry("alpha", ref, NoConfig.INSTANCE)));
            assertFalse(first.converged());
            assertEquals(ComponentState.FAILED, loader.snapshot().entry("alpha").orElseThrow().state());

            loader.reconcile(ComponentTree.of(
                    LoaderTestKit.entry("alpha", ref, NoConfig.INSTANCE)));
            assertEquals(1, attempts.get());
        } finally {
            loader.close();
        }
    }

    @Test
    void failedContextTeardownBlocksReplacementThenRetries() throws Exception {
        FactoryRef ref = FactoryRef.of("old");
        AtomicBoolean failOnce = new AtomicBoolean(true);
        var factory = LoaderTestKit.factory("old", (context, config) ->
                context.lifecycle().onClose("cleanup", () -> {
                    if (failOnce.getAndSet(false)) {
                        throw new IllegalStateException("cleanup failed");
                    }
                }));
        KnotraLoader loader = KnotraLoader.over(runtime, runtime.rootContext(),
                LoaderTestKit.resolver(ref, factory));
        try {
            LoaderTestKit.assertAccepted(loader.reconcile(ComponentTree.of(
                    LoaderTestKit.entry("alpha", ref, NoConfig.INSTANCE))));
            var blocked = loader.reconcile(ComponentTree.of(
                    LoaderTestKit.entry("beta", ref, NoConfig.INSTANCE)));
            LoaderTestKit.assertRejected(blocked, LoaderDiagnosticCode.TEARDOWN_FAILED);
            assertTrue(runtime.snapshot().components().stream()
                    .noneMatch(component -> component.mountId().equals("beta")));

            LoaderTestKit.assertAccepted(loader.reconcile(ComponentTree.of(
                    LoaderTestKit.entry("beta", ref, NoConfig.INSTANCE))));
            assertTrue(loader.snapshot().entry("alpha").isEmpty());
            assertEquals(ComponentState.ACTIVE, loader.snapshot().entry("beta").orElseThrow().state());
        } finally {
            loader.close();
        }
    }

    @Test
    void factoryIdentityChangeReplacesHandleAtSameMountId() throws Exception {
        FactoryRef ref = FactoryRef.of("implementation");
        AtomicInteger oldStarts = new AtomicInteger();
        AtomicInteger newStarts = new AtomicInteger();
        AtomicReference<ComponentFactoryResolver> resolverReference = new AtomicReference<>();
        KnotraLoader loader = KnotraLoader.over(runtime, runtime.rootContext(), ref2 -> resolverReference.get().resolve(ref2));
        try {
            resolverReference.set(LoaderTestKit.resolver(ref,
                    LoaderTestKit.factory("old", (context, config) -> oldStarts.incrementAndGet())));
            LoaderTestKit.assertAccepted(loader.reconcile(ComponentTree.of(
                    LoaderTestKit.entry("alpha", ref, NoConfig.INSTANCE))));
            String oldHandle = loader.snapshot().entry("alpha").orElseThrow().handleId();

            resolverReference.set(LoaderTestKit.resolver(ref,
                    LoaderTestKit.factory("new", (context, config) -> newStarts.incrementAndGet())));
            LoaderTestKit.assertAccepted(loader.reconcile(ComponentTree.of(
                    LoaderTestKit.entry("alpha", ref, NoConfig.INSTANCE))));
            var replacement = loader.snapshot().entry("alpha").orElseThrow();
            assertNotEquals(oldHandle, replacement.handleId());
            assertEquals("alpha", replacement.mountId());
            assertEquals("new", replacement.componentId());
            assertEquals(1, oldStarts.get());
            assertEquals(1, newStarts.get());
        } finally {
            loader.close();
        }
    }

    @Test
    void failedOldCleanupPreventsNewImplementationUntilSettled() throws Exception {
        FactoryRef ref = FactoryRef.of("implementation");
        AtomicBoolean failOnce = new AtomicBoolean(true);
        AtomicInteger newStarts = new AtomicInteger();
        AtomicReference<ComponentFactoryResolver> resolverReference = new AtomicReference<>();
        KnotraLoader loader = KnotraLoader.over(runtime, runtime.rootContext(), wanted -> resolverReference.get().resolve(wanted));
        try {
            resolverReference.set(LoaderTestKit.resolver(ref,
                    LoaderTestKit.factory("old", (context, config) ->
                            context.lifecycle().onClose("cleanup", () -> {
                                if (failOnce.getAndSet(false)) {
                                    throw new IllegalStateException("cleanup failed");
                                }
                            }))));
            LoaderTestKit.assertAccepted(loader.reconcile(ComponentTree.of(
                    LoaderTestKit.entry("alpha", ref, NoConfig.INSTANCE))));
            String oldHandle = loader.snapshot().entry("alpha").orElseThrow().handleId();

            resolverReference.set(LoaderTestKit.resolver(ref,
                    LoaderTestKit.factory("new", (context, config) -> newStarts.incrementAndGet())));
            var blocked = loader.reconcile(ComponentTree.of(
                    LoaderTestKit.entry("alpha", ref, NoConfig.INSTANCE)));
            assertFalse(blocked.converged(), () ->
                    blocked.diagnostics() + " snapshot=" + loader.snapshot());
            assertEquals(0, newStarts.get());
            assertEquals("old", loader.snapshot().entry("alpha").orElseThrow().componentId());

            LoaderTestKit.assertAccepted(loader.reconcile(ComponentTree.of(
                    LoaderTestKit.entry("alpha", ref, NoConfig.INSTANCE))));
            var replacement = loader.snapshot().entry("alpha").orElseThrow();
            assertNotEquals(oldHandle, replacement.handleId());
            assertEquals("new", replacement.componentId());
            assertEquals(1, newStarts.get());
        } finally {
            loader.close();
        }
    }

    @Test
    void rejectedReplacementIsCompensatedWithOldDefinition() throws Exception {
        FactoryRef ref = FactoryRef.of("implementation");
        var oldFactory = LoaderTestKit.factory("old", (context, config) -> {});
        var newFactory = LoaderTestKit.factory("new", (context, config) -> {});
        ResolvedComponentDefinition oldDefinition = ResolvedComponentDefinition.of(
                FactoryIdentity.of("implementation", "", "old"), oldFactory);
        ResolvedComponentDefinition newDefinition = new ResolvedComponentDefinition(
                FactoryIdentity.of("implementation", "", "new"),
                null,
                (context, config) -> java.util.concurrent.CompletableFuture.failedFuture(
                        new ControlledMountException(java.util.List.of(new io.knotra.RuntimeDiagnostic(
                                io.knotra.DiagnosticCode.INVALID_MOUNT_ID,
                                context.mountId(),
                                "controlled mount rejected")))),
                ReconfigureStrategy.direct());
        AtomicReference<ResolvedComponentDefinition> selected = new AtomicReference<>(oldDefinition);
        KnotraLoader loader = KnotraLoader.over(runtime, runtime.rootContext(), wanted ->
                selected.get() == null ? java.util.Optional.empty() : java.util.Optional.of(selected.get()));
        try {
            LoaderTestKit.assertAccepted(loader.reconcile(ComponentTree.of(
                    LoaderTestKit.entry("alpha", ref, NoConfig.INSTANCE))));
            String oldHandle = loader.snapshot().entry("alpha").orElseThrow().handleId();
            selected.set(newDefinition);

            var rejected = loader.reconcile(ComponentTree.of(
                    LoaderTestKit.entry("alpha", ref, NoConfig.INSTANCE)));
            LoaderTestKit.assertRejected(rejected, LoaderDiagnosticCode.REPLACEMENT_BLOCKED);
            var restored = loader.snapshot().entry("alpha").orElseThrow();
            assertEquals("old", restored.componentId());
            assertNotEquals(oldHandle, restored.handleId());
            assertEquals(oldDefinition.identity(), restored.factoryIdentity());
        } finally {
            loader.close();
        }
    }

    @Test
    void replacementCompensationFailurePreservesSpecificDiagnostics() throws Exception {
        FactoryRef ref = FactoryRef.of("implementation");
        AtomicBoolean rejectFallback = new AtomicBoolean();
        ResolvedComponentDefinition directOld = ResolvedComponentDefinition.of(
                FactoryIdentity.of("implementation", "", "old"),
                LoaderTestKit.factory("old", (context, config) -> {}));
        ResolvedComponentDefinition oldDefinition = new ResolvedComponentDefinition(
                directOld.identity(),
                null,
                (context, config) -> {
                    if (rejectFallback.get()) {
                        return CompletableFuture.failedFuture(new ControlledMountException(List.of(
                                new io.knotra.RuntimeDiagnostic(
                                        io.knotra.DiagnosticCode.INVALID_MOUNT_ID,
                                        context.mountId(),
                        "fallback rejected"))));
                    }
                    return directOld.mountStrategy().mount(context, config);
                },
                ReconfigureStrategy.direct());
        ResolvedComponentDefinition newDefinition = new ResolvedComponentDefinition(
                FactoryIdentity.of("implementation", "", "new"),
                null,
                (context, config) -> CompletableFuture.failedFuture(
                        new ControlledMountException(List.of(new io.knotra.RuntimeDiagnostic(
                                io.knotra.DiagnosticCode.INVALID_MOUNT_ID,
                                context.mountId(),
                                "replacement rejected")))),
                ReconfigureStrategy.direct());
        AtomicReference<ResolvedComponentDefinition> selected = new AtomicReference<>(oldDefinition);
        KnotraLoader loader = KnotraLoader.over(runtime, runtime.rootContext(), wanted ->
                java.util.Optional.of(selected.get()));
        try {
            LoaderTestKit.assertAccepted(loader.reconcile(ComponentTree.of(
                    LoaderTestKit.entry("alpha", ref, NoConfig.INSTANCE))));
            selected.set(newDefinition);
            rejectFallback.set(true);

            var result = loader.reconcile(ComponentTree.of(
                    LoaderTestKit.entry("alpha", ref, NoConfig.INSTANCE)));
            LoaderTestKit.assertRejected(result, LoaderDiagnosticCode.COMPENSATION_FAILED);
            assertTrue(result.diagnostics().stream().anyMatch(diagnostic ->
                    diagnostic.message().equals("replacement rejected")));
            assertTrue(result.diagnostics().stream().anyMatch(diagnostic ->
                    diagnostic.message().equals("fallback rejected")));
            assertTrue(loader.snapshot().entry("alpha").isEmpty());
        } finally {
            loader.close();
        }
    }

    @Test
    void preparationFailureDoesNotChangeExistingTree() throws Exception {
        FactoryRef good = FactoryRef.of("good");
        FactoryRef missing = FactoryRef.of("missing");
        KnotraLoader loader = KnotraLoader.over(runtime, runtime.rootContext(), wanted ->
                wanted.equals(good)
                        ? java.util.Optional.of(ResolvedComponentDefinition.of(
                                FactoryIdentity.of("good", "", "good"),
                                LoaderTestKit.factory("good", (context, config) -> {})))
                        : java.util.Optional.empty());
        try {
            LoaderTestKit.assertAccepted(loader.reconcile(ComponentTree.of(
                    LoaderTestKit.entry("alpha", good, NoConfig.INSTANCE))));
            String handle = loader.snapshot().entry("alpha").orElseThrow().handleId();

            var rejected = loader.reconcile(ComponentTree.of(
                    LoaderTestKit.entry("alpha", good, NoConfig.INSTANCE),
                    LoaderTestKit.entry("beta", missing, NoConfig.INSTANCE)));
            LoaderTestKit.assertRejected(rejected, LoaderDiagnosticCode.RESOLUTION_FAILED);
            assertTrue(loader.snapshot().entry("beta").isEmpty());
            assertEquals(handle, loader.snapshot().entry("alpha").orElseThrow().handleId());
        } finally {
            loader.close();
        }
    }

    @Test
    void childContextInheritsThenShadowsRootCapability() throws Exception {
        FactoryRef providerRef = FactoryRef.of("provider");
        FactoryRef consumerRef = FactoryRef.of("consumer");
        List<String> observed = new CopyOnWriteArrayList<>();
        var provider = LoaderTestKit.factory("provider", (context, config) -> {
            if (config.equals("child")) {
                context.provide(TEXT, "child");
            }
        });
        var consumer = LoaderTestKit.factory("consumer", (context, config) ->
                observed.add(context.require(TEXT)), CapabilityRequirement.required(TEXT));
        ComponentFactoryResolver resolver = CompositeComponentFactoryResolver.of(
                LoaderTestKit.resolver(providerRef, provider),
                LoaderTestKit.resolver(consumerRef, consumer));
        KnotraLoader loader = KnotraLoader.over(runtime, runtime.rootContext(), resolver);
        try {
            provide("root");
            ComponentTree tree = ComponentTree.of(ComponentEntry.of(
                    "alpha",
                    providerRef,
                    "child",
                    LoaderTestKit.entry("child", consumerRef, NoConfig.INSTANCE)));
            LoaderTestKit.assertAccepted(loader.reconcile(tree));
            assertEquals(List.of("child"), observed);

            ComponentTree unshadowed = ComponentTree.of(ComponentEntry.of(
                    "alpha",
                    providerRef,
                    "off",
                    LoaderTestKit.entry("child", consumerRef, NoConfig.INSTANCE)));
            LoaderTestKit.assertAccepted(loader.reconcile(unshadowed));
            assertEquals(List.of("child", "root"), observed);
        } finally {
            loader.close();
        }
    }

    @Test
    void concurrentReconcilesAreSerialized() throws Exception {
        FactoryRef ref = FactoryRef.of("serialized");
        CountDownLatch entered = new CountDownLatch(1);
        CompletableFuture<Void> gate = new CompletableFuture<>();
        AtomicInteger enteredCount = new AtomicInteger();
        var factory = LoaderTestKit.factory("serialized", (context, config) -> {
            enteredCount.incrementAndGet();
            entered.countDown();
            gate.join();
        });
        KnotraLoader loader = KnotraLoader.over(runtime, runtime.rootContext(),
                LoaderTestKit.resolver(ref, factory));
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            var first = CompletableFuture.supplyAsync(() -> loader.reconcile(ComponentTree.of(
                    LoaderTestKit.entry("first", ref, NoConfig.INSTANCE))), executor);
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            var second = CompletableFuture.supplyAsync(() -> loader.reconcile(ComponentTree.of(
                    LoaderTestKit.entry("second", ref, NoConfig.INSTANCE))), executor);
            assertTrue(first.toCompletableFuture().isDone() || enteredCount.get() == 1);
            assertFalse(second.isDone());
            gate.complete(null);
            LoaderTestKit.assertAccepted(second.get(10, TimeUnit.SECONDS));
            LoaderTestKit.assertAccepted(first.get(10, TimeUnit.SECONDS));
            assertEquals(1, loader.snapshot().entries().size(),
                    () -> loader.snapshot().toString());
            assertTrue(loader.snapshot().entry("second").isPresent());
            assertTrue(loader.snapshot().entry("first").isEmpty());
        } finally {
            executor.shutdown();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
            loader.close();
        }
    }

    @Test
    void closeFailureCanBeRetried() throws Exception {
        FactoryRef ref = FactoryRef.of("alpha");
        AtomicBoolean failOnce = new AtomicBoolean(true);
        var factory = LoaderTestKit.factory("alpha", (context, config) ->
                context.lifecycle().onClose("cleanup", () -> {
                    if (failOnce.getAndSet(false)) {
                        throw new IllegalStateException("cleanup failed");
                    }
                }));
        KnotraLoader loader = KnotraLoader.over(runtime, runtime.rootContext(),
                LoaderTestKit.resolver(ref, factory));
        LoaderTestKit.assertAccepted(loader.reconcile(ComponentTree.of(
                LoaderTestKit.entry("alpha", ref, NoConfig.INSTANCE))));

        assertInstanceOf(IllegalStateException.class, assertThrows(ExecutionException.class,
                () -> loader.close()).getCause());
        assertTrue(loader.snapshot().closed());
        loader.close();
        assertTrue(loader.snapshot().entries().isEmpty());
        assertTrue(runtime.snapshot().components().isEmpty());
    }

    private RegistrationHandle provide(String value) {
        var result = runtime.mutate(mutation ->
                mutation.provide(runtime.rootContext(), TEXT, value));
        assertTrue(result.committed(), () -> result.diagnostics().toString());
        try {
            result.settlement().toCompletableFuture().get(10, TimeUnit.SECONDS);
        } catch (Exception error) {
            throw new AssertionError(error);
        }
        return result.value();
    }
}
