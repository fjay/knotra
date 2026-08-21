package io.knotra.loader;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.knotra.ComponentFactory;
import io.knotra.ComponentHandle;
import io.knotra.ComponentState;
import io.knotra.DiagnosticCode;
import io.knotra.KnotraRuntime;
import io.knotra.MountOptions;
import io.knotra.NoConfig;
import io.knotra.RuntimeDiagnostic;

import static org.junit.jupiter.api.Assertions.*;

final class LoaderAsyncBoundaryTest {

    private final KnotraRuntime runtime = KnotraRuntime.create();

    @AfterEach
    void tearDown() throws Exception {
        runtime.close();
    }

    @Test
    void onlyRuntimeQualifiedOverFactoryIsPublic() {
        for (Method method : KnotraLoader.class.getMethods()) {
            if (!method.getName().equals("over")) {
                continue;
            }
            assertEquals(3, method.getParameterCount(), () -> method.toString());
            assertEquals(KnotraRuntime.class, method.getParameterTypes()[0]);
        }
    }

    @Test
    void controlledMountSurfaceDoesNotExposeRuntimeTransaction() {
        for (Class<?> surface : List.of(
                ControlledMountStrategy.class,
                ControlledMountContext.class,
                ResolvedFactory.class)) {
            for (Method method : surface.getMethods()) {
                for (Class<?> parameter : method.getParameterTypes()) {
                    assertNotEquals(KnotraRuntime.class, parameter, method::toString);
                    assertNotEquals(
                            "io.knotra.RuntimeTransaction",
                            parameter.getName(),
                            method::toString);
                }
                assertNotEquals(KnotraRuntime.class, method.getReturnType(), method::toString);
                assertNotEquals(
                        "io.knotra.RuntimeTransaction",
                        method.getReturnType().getName(),
                        method::toString);
            }
        }
    }

    @Test
    void controlledMountContextIsSingleUseAndBindsOnlyItsAllocatedSlot() throws Exception {
        FactoryRef ref = FactoryRef.of("controlled");
        var factory = LoaderTestKit.factory("controlled", (context, config) -> {
        });
        ResolvedFactory definition = new ResolvedFactory(
                FactoryIdentity.of("controlled", "", "test"),
                null,
                (context, config) -> {
                    assertFalse(context instanceof KnotraRuntime);
                    assertEquals("controlled", context.mountId());
                    CompletionStage<?> mounted = context.mountAsync(factory, config, MountOptions.DEFAULT);
                    CompletionStage<?> second = context.mountAsync(factory, config, MountOptions.DEFAULT);
                    assertTrue(second.toCompletableFuture().isCompletedExceptionally());
                    @SuppressWarnings("unchecked")
                    CompletionStage<ComponentHandle<?>> first =
                            (CompletionStage<ComponentHandle<?>>) mounted;
                    return first;
                },
                ReconfigureStrategy.direct());
        KnotraLoader loader = KnotraLoader.over(runtime, runtime.root(), wanted ->
                Optional.of(definition));
        try {
            LoaderTestKit.assertAccepted(loader.reconcile(ComponentTree.of(
                    LoaderTestKit.entry("controlled", ref, NoConfig.INSTANCE))));
            var snapshot = loader.snapshot().entry("controlled").orElseThrow();
            assertEquals("controlled", snapshot.mountId());
            assertTrue(snapshot.contextPath().endsWith("controlled"));
        } finally {
            loader.close();
        }
    }

    @Test
    void loaderRejectsAHandleReturnedForAnotherAllocatedSlot() throws Exception {
        FactoryRef ref = FactoryRef.of("wrong-slot");
        var factory = LoaderTestKit.factory("wrong-slot", (context, config) -> {
        });
        var externalContext = runtime.transact(mutation ->
                mutation.childContext(runtime.root(), "external")).value();
        ResolvedFactory definition = new ResolvedFactory(
                FactoryIdentity.of("wrong-slot", "", "test"),
                null,
                (context, config) -> {
                    var external = runtime.transact(mutation -> mutation.mount(
                            externalContext, "external", factory, config, MountOptions.DEFAULT));
                    return CompletableFuture.completedFuture(external.value());
                },
                ReconfigureStrategy.direct());
        KnotraLoader loader = KnotraLoader.over(runtime, runtime.root(), wanted ->
                Optional.of(definition));
        try {
            ReconcileResult result = loader.reconcile(ComponentTree.of(
                    LoaderTestKit.entry("wanted", ref, NoConfig.INSTANCE)));

            LoaderTestKit.assertRejected(result, LoaderDiagnosticCode.STRUCTURE_REJECTED);
            assertTrue(runtime.snapshot().components().isEmpty());
            assertTrue(loader.snapshot().entries().isEmpty());
        } finally {
            loader.close();
        }
    }

    @Test
    void asyncReconcileAndCloseDoNotBlockCallerWhileCoreIsGated() throws Exception {
        FactoryRef ref = FactoryRef.of("gated");
        CountDownLatch entered = new CountDownLatch(1);
        CompletableFuture<Void> gate = new CompletableFuture<>();
        ComponentFactory<Object> factory = LoaderTestKit.factory("gated", (context, config) -> {
            entered.countDown();
            gate.join();
        });
        KnotraLoader loader = KnotraLoader.over(runtime, runtime.root(),
                LoaderTestKit.resolver(ref, factory));
        try {
            var first = loader.reconcileAsync(ComponentTree.of(
                    LoaderTestKit.entry("alpha", ref, NoConfig.INSTANCE)));
            assertTrue(entered.await(10, TimeUnit.SECONDS), () -> {
                gate.complete(null);
                try {
                    return "start did not run; reconcile="
                            + first.toCompletableFuture().get(1, TimeUnit.SECONDS);
                } catch (Exception error) {
                    return "start did not run; reconcile failed: " + error;
                }
            });
            var close = loader.closeAsync();
            var second = loader.reconcileAsync(ComponentTree.of(
                    LoaderTestKit.entry("beta", ref, NoConfig.INSTANCE)));

            assertFalse(first.toCompletableFuture().isDone());
            assertFalse(close.toCompletableFuture().isDone());
            assertTrue(second.toCompletableFuture().isDone(),
                    "reconcile after close should fail fast");
            assertNotNull(loader.snapshot());

            gate.complete(null);
            close.toCompletableFuture().get(10, TimeUnit.SECONDS);
            LoaderTestKit.assertAccepted(first.toCompletableFuture().get(10, TimeUnit.SECONDS));
            LoaderTestKit.assertRejected(second.toCompletableFuture().get(10, TimeUnit.SECONDS),
                    LoaderDiagnosticCode.CLOSED);
            assertTrue(loader.snapshot().closed());
        } finally {
            gate.complete(null);
            loader.close();
        }
    }

    @Test
    void closeReturnsTheSameInFlightFuture() throws Exception {
        FactoryRef ref = FactoryRef.of("same-close");
        CountDownLatch entered = new CountDownLatch(1);
        CompletableFuture<Void> gate = new CompletableFuture<>();
        var factory = LoaderTestKit.factory("same-close", (context, config) -> {
            entered.countDown();
            gate.join();
        });
        KnotraLoader loader = KnotraLoader.over(runtime, runtime.root(),
                LoaderTestKit.resolver(ref, factory));
        var reconcile = loader.reconcileAsync(ComponentTree.of(
                LoaderTestKit.entry("alpha", ref, NoConfig.INSTANCE)));
        assertTrue(entered.await(10, TimeUnit.SECONDS));
        var first = loader.closeAsync();
        var second = loader.closeAsync();
        assertSame(first, second);
        assertFalse(first.toCompletableFuture().isDone());
        gate.complete(null);
        first.toCompletableFuture().get(10, TimeUnit.SECONDS);
        reconcile.toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @Test
    void reentrantReconcileOnCoordinatorThreadIsRejected() throws Exception {
        FactoryRef ref = FactoryRef.of("reentrant");
        AtomicReference<RuntimeException> rejected = new AtomicReference<>();
        AtomicReference<KnotraLoader> owningLoader = new AtomicReference<>();
        ResolvedFactory definition = new ResolvedFactory(
                FactoryIdentity.of("reentrant", "", "test"),
                null,
                (context, config) -> {
                    KnotraLoader loader = owningLoader.get();
                    try {
                        loader.reconcile(ComponentTree.empty());
                        rejected.set(null);
                    } catch (RuntimeException error) {
                        rejected.set(error);
                    }
                    return CompletableFuture.failedFuture(new IllegalStateException("mount stopped"));
                },
                ReconfigureStrategy.direct());
        KnotraLoader loader = KnotraLoader.over(runtime, runtime.root(), wanted ->
                Optional.of(definition));
        owningLoader.set(loader);
        try {
            var result = loader.reconcile(ComponentTree.of(
                    LoaderTestKit.entry("alpha", ref, NoConfig.INSTANCE)));
            assertFalse(result.converged());
            assertNotNull(rejected.get());
            assertTrue(rejected.get().getMessage().contains("reentrant coordinator operation"),
                    () -> rejected.get().toString() + " result=" + result);
        } finally {
            loader.close();
        }
    }

    @Test
    void failedAddCompensatesMountedHandlesAndNewContexts() throws Exception {
        FactoryRef good = FactoryRef.of("good");
        FactoryRef bad = FactoryRef.of("bad");
        AtomicInteger goodStarts = new AtomicInteger();
        ResolvedFactory badDefinition = new ResolvedFactory(
                FactoryIdentity.of("bad", "", "bad"),
                null,
                (context, config) -> CompletableFuture.failedFuture(
                        new ControlledMountException(List.of(new RuntimeDiagnostic(
                                DiagnosticCode.INVALID_MOUNT_ID,
                                context.mountId(),
                                "external coordinator rejected mount")))),
                ReconfigureStrategy.direct());
        ComponentFactoryResolver resolver = (FactoryRef wanted) -> wanted.equals(good)
                ? Optional.of(ResolvedFactory.of(
                        FactoryIdentity.of("good", "", "good"),
                        LoaderTestKit.factory("good", (context, config) ->
                                goodStarts.incrementAndGet())))
                : Optional.of(badDefinition);
        KnotraLoader loader = KnotraLoader.over(runtime, runtime.root(), resolver);
        try {
            var result = loader.reconcile(ComponentTree.of(
                    LoaderTestKit.entry("alpha", good, NoConfig.INSTANCE),
                    LoaderTestKit.entry("beta", bad, NoConfig.INSTANCE)));
            LoaderTestKit.assertRejected(result, LoaderDiagnosticCode.STRUCTURE_REJECTED);
            assertEquals(1, goodStarts.get());
            assertTrue(loader.snapshot().entries().isEmpty());
            assertTrue(runtime.snapshot().components().isEmpty());
            assertTrue(runtime.snapshot().contexts().stream()
                    .noneMatch(context -> context.name().equals("alpha")
                            || context.name().equals("beta")));
            assertTrue(result.diagnostics().stream()
                    .anyMatch(diagnostic -> diagnostic.code() == LoaderDiagnosticCode.STRUCTURE_REJECTED
                            && diagnostic.message().equals("external coordinator rejected mount")));
        } finally {
            loader.close();
        }
    }

    @Test
    void desiredFailedComponentIsNotConvergedAndExplicitRetryActivates() throws Exception {
        FactoryRef ref = FactoryRef.of("retry");
        AtomicBoolean failOnce = new AtomicBoolean(true);
        AtomicInteger attempts = new AtomicInteger();
        var factory = LoaderTestKit.factory("retry", (context, config) -> {
            attempts.incrementAndGet();
            if (failOnce.getAndSet(false)) {
                throw new IllegalStateException("temporary");
            }
        });
        KnotraLoader loader = KnotraLoader.over(runtime, runtime.root(),
                LoaderTestKit.resolver(ref, factory));
        try {
            var failed = loader.reconcile(ComponentTree.of(
                    LoaderTestKit.entry("alpha", ref, NoConfig.INSTANCE)));
            LoaderTestKit.assertRejected(failed, LoaderDiagnosticCode.ACTIVATION_FAILED);
            assertEquals(ComponentState.FAILED, loader.snapshot().entry("alpha").orElseThrow().state());

            var stillFailed = loader.reconcile(ComponentTree.of(
                    LoaderTestKit.entry("alpha", ref, NoConfig.INSTANCE)));
            assertFalse(stillFailed.converged());
            assertEquals(1, attempts.get());

            LoaderTestKit.assertAccepted(loader.retry("alpha"));
            assertEquals(ComponentState.ACTIVE, loader.snapshot().entry("alpha").orElseThrow().state());
            assertEquals(2, attempts.get());
        } finally {
            loader.close();
        }
    }

    @Test
    void disposedBaseIsRejectedBeforeTheFirstOperation() throws Exception {
        FactoryRef ref = FactoryRef.of("alpha");
        var baseResult = runtime.transact(mutation ->
                mutation.childContext(runtime.root(), "base"));
        runtime.transact(mutation -> {
            mutation.dispose(baseResult.value());
            return null;
        }).settlement().toCompletableFuture().get(10, TimeUnit.SECONDS);
        KnotraLoader loader = KnotraLoader.over(runtime, baseResult.value(),
                LoaderTestKit.resolver(ref, LoaderTestKit.factory("alpha", (context, config) -> {})));
        try {
            var result = loader.reconcile(ComponentTree.empty());
            LoaderTestKit.assertRejected(result, LoaderDiagnosticCode.BASE_UNAVAILABLE);
            assertTrue(runtime.snapshot().components().isEmpty());
        } finally {
            loader.close();
        }
    }

    @Test
    void foreignChildUnderExternalBaseIsRejected() throws Exception {
        FactoryRef ref = FactoryRef.of("alpha");
        var baseResult = runtime.transact(mutation ->
                mutation.childContext(runtime.root(), "base"));
        runtime.transact(mutation ->
                mutation.childContext(baseResult.value(), "alpha"));
        KnotraLoader loader = KnotraLoader.over(runtime, baseResult.value(),
                LoaderTestKit.resolver(ref, LoaderTestKit.factory("alpha", (context, config) -> {})));
        try {
            var result = loader.reconcile(ComponentTree.of(
                    LoaderTestKit.entry("alpha", ref, NoConfig.INSTANCE)));
            LoaderTestKit.assertRejected(result, LoaderDiagnosticCode.CONTEXT_CONFLICT);
            assertTrue(runtime.snapshot().components().isEmpty());
        } finally {
            loader.close();
        }
    }

    @Test
    void runtimeCloseRaceDoesNotTurnBaseDisposalIntoLoaderFailure() throws Exception {
        FactoryRef ref = FactoryRef.of("close-race");
        CompletableFuture<Void> cleanupGate = new CompletableFuture<>();
        var factory = LoaderTestKit.factory("close-race", (context, config) ->
                context.lifecycle().onClose("cleanup", () -> {
                    try {
                        cleanupGate.get();
                    } catch (Exception error) {
                        throw new IllegalStateException(error);
                    }
                }));
        var baseResult = runtime.transact(mutation ->
                mutation.childContext(runtime.root(), "base"));
        KnotraLoader loader = KnotraLoader.over(runtime, baseResult.value(),
                LoaderTestKit.resolver(ref, factory));
        LoaderTestKit.assertAccepted(loader.reconcile(ComponentTree.of(
                LoaderTestKit.entry("alpha", ref, NoConfig.INSTANCE))));

        var runtimeClose = runtime.closeAsync();
        assertEquals(io.knotra.ContextState.DISPOSING, baseResult.value().state());
        loader.closeAsync().toCompletableFuture().get(10, TimeUnit.SECONDS);
        cleanupGate.complete(null);
        runtimeClose.toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertTrue(loader.snapshot().entries().isEmpty());
    }

    @Test
    void closedRuntimeReconcileIsRejectedWithStructuredBaseDiagnostic() throws Exception {
        FactoryRef ref = FactoryRef.of("late");
        runtime.close();
        KnotraLoader loader = KnotraLoader.over(runtime, runtime.root(),
                LoaderTestKit.resolver(ref, LoaderTestKit.factory("late", (context, config) -> {})));
        try {
            var result = loader.reconcile(ComponentTree.empty());
            LoaderTestKit.assertRejected(result, LoaderDiagnosticCode.BASE_UNAVAILABLE);
        } finally {
            loader.close();
        }
    }
}
