package io.knotra.loader;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
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
import io.knotra.ConfigDecoder;
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
        AtomicInteger decodes = new AtomicInteger();
        AtomicInteger normalizations = new AtomicInteger();
        ComponentFactory<String> delegate = LoaderTestKit.factory(
                "configured", (context, config) -> configs.add(config));
        ComponentFactory<String> factory = new ComponentFactory<>() {
            @Override
            public String factoryId() {
                return delegate.factoryId();
            }

            @Override
            public io.knotra.Component<String> create() {
                return delegate.create();
            }

            @Override
            public String normalizeConfig(String config) {
                normalizations.incrementAndGet();
                return config.trim();
            }
        };
        ConfigDecoder<String> decoder = raw -> {
            decodes.incrementAndGet();
            return String.valueOf(raw);
        };
        KnotraLoader loader = KnotraLoader.over(runtime, runtime.root(),
                LoaderTestKit.resolver(ref, factory, decoder));
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
            assertEquals(3, decodes.get());
            assertEquals(2, normalizations.get(),
                    "Core must normalize only mount and changed reconfigure once each");
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
        KnotraLoader loader = KnotraLoader.over(runtime, runtime.root(),
                LoaderTestKit.resolver(ref, factory, (Object raw) -> String.valueOf(raw)));
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
        ComponentFactory<NoConfig> factory = LoaderTestKit.factory("bad", (context, config) -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("temporary");
        });
        KnotraLoader loader = KnotraLoader.over(runtime, runtime.root(),
                LoaderTestKit.resolver(ref, factory));
        try {
            var first = loader.reconcile(ComponentTree.of(
                    LoaderTestKit.entry("alpha", ref, NoConfig.INSTANCE)));
            assertFalse(first.converged());
            assertTrue(first.diagnostics().stream().anyMatch(diagnostic ->
                    diagnostic.code() == LoaderDiagnosticCode.ACTIVATION_FAILED
                            && diagnostic.message().contains("java.lang.IllegalStateException: temporary")),
                    () -> String.valueOf(first.diagnostics()));
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
        ComponentFactory<NoConfig> factory = LoaderTestKit.factory("old", (context, config) ->
                context.lifecycle().onClose("cleanup", () -> {
                    if (failOnce.getAndSet(false)) {
                        throw new IllegalStateException("cleanup failed");
                    }
                }));
        KnotraLoader loader = KnotraLoader.over(runtime, runtime.root(),
                LoaderTestKit.resolver(ref, factory));
        try {
            LoaderTestKit.assertAccepted(loader.reconcile(ComponentTree.of(
                    LoaderTestKit.entry("alpha", ref, NoConfig.INSTANCE))));
            var blocked = loader.reconcile(ComponentTree.of(
                    LoaderTestKit.entry("beta", ref, NoConfig.INSTANCE)));
            LoaderTestKit.assertRejected(blocked, LoaderDiagnosticCode.TEARDOWN_FAILED);
            assertTrue(runtime.advanced().snapshot().mounts().stream()
                    .noneMatch(mount -> mount.mountId().equals("beta")));

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
        KnotraLoader loader = KnotraLoader.over(runtime, runtime.root(), ref2 -> resolverReference.get().resolve(ref2));
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
        KnotraLoader loader = KnotraLoader.over(runtime, runtime.root(), wanted -> resolverReference.get().resolve(wanted));
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
        ComponentFactory<NoConfig> oldFactory =
                LoaderTestKit.factory("old", (context, config) -> {});
        ComponentFactory<NoConfig> newFactory =
                LoaderTestKit.factory("new", (context, config) -> {});
        ResolvedFactory oldDefinition = ResolvedFactory.of(
                FactoryIdentity.of("implementation", "", "old"), oldFactory);
        ResolvedFactory newDefinition = new ResolvedFactory(
                FactoryIdentity.of("implementation", "", "new"),
                ResolvedFactory.FactoryKind.PLAIN,
                null,
                (context, config) -> java.util.concurrent.CompletableFuture.failedFuture(
                        new ControlledMountException(java.util.List.of(new io.knotra.RuntimeDiagnostic(
                                io.knotra.DiagnosticCode.INVALID_MOUNT_ID,
                                context.mountId(),
                                "controlled mount rejected")))),
                ReconfigureStrategy.unsupportedPlain());
        AtomicReference<ResolvedFactory> selected = new AtomicReference<>(oldDefinition);
        KnotraLoader loader = KnotraLoader.over(runtime, runtime.root(), wanted ->
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
        ResolvedFactory directOld = ResolvedFactory.of(
                FactoryIdentity.of("implementation", "", "old"),
                LoaderTestKit.factory("old", (context, config) -> {}));
        ResolvedFactory oldDefinition = new ResolvedFactory(
                directOld.identity(),
                ResolvedFactory.FactoryKind.PLAIN,
                null,
                (context, config) -> {
                    if (rejectFallback.get()) {
                        return CompletableFuture.failedFuture(new ControlledMountException(List.of(
                                new io.knotra.RuntimeDiagnostic(
                                        io.knotra.DiagnosticCode.INVALID_MOUNT_ID,
                                        context.mountId(),
                        "fallback rejected"))));
                    }
                    return directOld.mountStrategy().mountAsync(context, config);
                },
                ReconfigureStrategy.unsupportedPlain());
        ResolvedFactory newDefinition = new ResolvedFactory(
                FactoryIdentity.of("implementation", "", "new"),
                ResolvedFactory.FactoryKind.PLAIN,
                null,
                (context, config) -> CompletableFuture.failedFuture(
                        new ControlledMountException(List.of(new io.knotra.RuntimeDiagnostic(
                                io.knotra.DiagnosticCode.INVALID_MOUNT_ID,
                                context.mountId(),
                                "replacement rejected")))),
                ReconfigureStrategy.unsupportedPlain());
        AtomicReference<ResolvedFactory> selected = new AtomicReference<>(oldDefinition);
        KnotraLoader loader = KnotraLoader.over(runtime, runtime.root(), wanted ->
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
        KnotraLoader loader = KnotraLoader.over(runtime, runtime.root(), wanted ->
                wanted.equals(good)
                        ? java.util.Optional.of(ResolvedFactory.of(
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
        ComponentFactory<String> provider = LoaderTestKit.factory("provider", (context, config) -> {
            if (config.equals("child")) {
                context.provide(TEXT, "child");
            }
        });
        ComponentFactory<NoConfig> consumer = LoaderTestKit.factory("consumer", (context, config) ->
                observed.add(context.require(TEXT)), CapabilityRequirement.required(TEXT));
        ComponentFactoryResolver resolver = CompositeFactoryResolver.of(
                LoaderTestKit.resolver(providerRef, provider, (Object raw) -> String.valueOf(raw)),
                LoaderTestKit.resolver(consumerRef, consumer));
        KnotraLoader loader = KnotraLoader.over(runtime, runtime.root(), resolver);
        try {
            provide("root");
            ComponentTree tree = ComponentTree.of(ComponentEntry.configured(
                    "alpha",
                    providerRef,
                    "child",
                    LoaderTestKit.entry("child", consumerRef, NoConfig.INSTANCE)));
            LoaderTestKit.assertAccepted(loader.reconcile(tree));
            assertEquals(List.of("child"), observed);

            ComponentTree unshadowed = ComponentTree.of(ComponentEntry.configured(
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
        ComponentFactory<NoConfig> factory = LoaderTestKit.factory("serialized", (context, config) -> {
            enteredCount.incrementAndGet();
            entered.countDown();
            gate.join();
        });
        KnotraLoader loader = KnotraLoader.over(runtime, runtime.root(),
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
        ComponentFactory<NoConfig> factory = LoaderTestKit.factory("alpha", (context, config) ->
                context.lifecycle().onClose("cleanup", () -> {
                    if (failOnce.getAndSet(false)) {
                        throw new IllegalStateException("cleanup failed");
                    }
                }));
        KnotraLoader loader = KnotraLoader.over(runtime, runtime.root(),
                LoaderTestKit.resolver(ref, factory));
        LoaderTestKit.assertAccepted(loader.reconcile(ComponentTree.of(
                LoaderTestKit.entry("alpha", ref, NoConfig.INSTANCE))));

        assertInstanceOf(IllegalStateException.class,
                assertThrows(java.util.concurrent.CompletionException.class, loader::close)
                        .getCause());
        assertTrue(loader.snapshot().closed());
        loader.close();
        assertTrue(loader.snapshot().entries().isEmpty());
        assertTrue(runtime.advanced().snapshot().mounts().isEmpty());
    }

    private RegistrationHandle provide(String value) {
        var result = runtime.advanced().transact(mutation ->
                mutation.provide(runtime.root(), TEXT, value));
        try {
            result.settlement().awaitSettled(java.time.Duration.ofSeconds(10));
        } catch (Exception error) {
            throw new AssertionError(error);
        }
        return result.value();
    }
}
