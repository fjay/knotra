package io.knotra.loader;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import io.knotra.ComponentFactory;
import io.knotra.KnotraRuntime;
import io.knotra.NoConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class CoordinatorBehaviorContractTest {

    private static final FactoryRef FIRST = FactoryRef.of("first");
    private static final FactoryRef SECOND = FactoryRef.of("second");

    @Test
    void reconcilesAreSerializedOnOneNamedCoordinatorThread() throws Exception {
        try (KnotraRuntime runtime = KnotraRuntime.create()) {
            CountDownLatch firstEntered = new CountDownLatch(1);
            CompletableFuture<Void> releaseFirst = new CompletableFuture<>();
            List<String> order = new ArrayList<>();
            AtomicReference<Thread> firstThread = new AtomicReference<>();
            AtomicReference<Thread> secondThread = new AtomicReference<>();
            ComponentFactoryResolver delegate = fixedResolver();
            ComponentFactoryResolver resolver = ref -> {
                if (FIRST.equals(ref)) {
                    firstThread.set(Thread.currentThread());
                    firstEntered.countDown();
                    releaseFirst.join();
                    order.add("first");
                } else if (SECOND.equals(ref)) {
                    secondThread.set(Thread.currentThread());
                    order.add("second");
                }
                return delegate.resolve(ref);
            };

            try (KnotraLoader loader = KnotraLoader.over(runtime, runtime.root(), resolver)) {
                CompletableFuture<ReconcileResult> first =
                        loader.reconcileAsync(tree("first", FIRST)).toCompletableFuture();
                assertTrue(firstEntered.await(10, TimeUnit.SECONDS));
                CompletableFuture<ReconcileResult> second =
                        loader.reconcileAsync(tree("second", SECOND)).toCompletableFuture();
                assertFalse(second.isDone(), "second reconcile must wait for the coordinator");
                releaseFirst.complete(null);

                assertTrue(first.get(10, TimeUnit.SECONDS).converged());
                assertTrue(second.get(10, TimeUnit.SECONDS).converged());
                assertEquals(List.of("first", "second"), order);
                assertSame(firstThread.get(), secondThread.get());
                assertTrue(firstThread.get().getName().endsWith("-coordinator"));
            }
        }
    }

    @Test
    void coordinatorRemainsUsableAfterAResolverFailure() throws Exception {
        try (KnotraRuntime runtime = KnotraRuntime.create()) {
            ComponentFactoryResolver delegate = fixedResolver();
            AtomicReference<Boolean> rejectNext = new AtomicReference<>(Boolean.TRUE);
            ComponentFactoryResolver resolver = ref -> {
                if (rejectNext.getAndSet(Boolean.FALSE)) {
                    throw new IllegalStateException("temporary resolver failure");
                }
                return delegate.resolve(ref);
            };

            try (KnotraLoader loader = KnotraLoader.over(runtime, runtime.root(), resolver)) {
                ReconcileResult failed = loader.reconcile(tree("component", FIRST));
                assertFalse(failed.converged());
                assertTrue(failed.diagnostics().stream()
                        .anyMatch(item -> item.code() == LoaderDiagnosticCode.RESOLUTION_FAILED),
                        () -> failed.diagnostics().toString());

                ReconcileResult recovered = loader.reconcile(tree("component", FIRST));
                assertTrue(recovered.converged(), () -> recovered.diagnostics().toString());
            }
        }
    }

    @Test
    void reconcileAfterCloseIsRejectedWithClosedDiagnostics() throws Exception {
        try (KnotraRuntime runtime = KnotraRuntime.create();
             KnotraLoader loader = KnotraLoader.over(
                     runtime, runtime.root(), fixedResolver())) {
            loader.close();

            ReconcileResult result = loader.reconcile(tree("component", FIRST));

            assertFalse(result.converged());
            assertTrue(result.diagnostics().stream()
                    .anyMatch(item -> item.code() == LoaderDiagnosticCode.CLOSED),
                    () -> result.diagnostics().toString());
        }
    }

    @Test
    void reentrantReconcileFromCoordinatorThreadIsRejected() throws Exception {
        try (KnotraRuntime runtime = KnotraRuntime.create()) {
            AtomicReference<IllegalStateException> rejection = new AtomicReference<>();
            AtomicReference<KnotraLoader> activeLoader = new AtomicReference<>();
            ComponentFactoryResolver delegate = fixedResolver();
            ComponentFactoryResolver resolver = ref -> {
                rejection.set(assertThrows(IllegalStateException.class, () ->
                        activeLoader.get().reconcileAsync(tree("nested", FIRST))));
                return delegate.resolve(ref);
            };

            try (KnotraLoader loader =
                         KnotraLoader.over(runtime, runtime.root(), resolver)) {
                activeLoader.set(loader);
                ReconcileResult outer = loader.reconcile(tree("component", FIRST));
                assertTrue(outer.converged(), () -> outer.diagnostics().toString());

                assertNotNull(rejection.get());
                assertTrue(rejection.get().getMessage().contains("reentrant"),
                        rejection.get()::getMessage);
            }
        }
    }

    private static ComponentFactoryResolver fixedResolver() {
        ComponentFactory<NoConfig> factory =
                LoaderTestKit.<NoConfig>factory(
                        "behavior", (context, config) -> { });
        ComponentFactoryResolver resolver = ClasspathFactoryResolver.builder()
                .add(FIRST, factory)
                .add(SECOND, factory)
                .build();
        return ref -> {
            Optional<ResolvedFactory> resolved = resolver.resolve(ref);
            if (resolved.isPresent()) {
                return resolved;
            }
            throw new IllegalArgumentException("unexpected reference: " + ref);
        };
    }

    private static ComponentTree tree(String path, FactoryRef ref) {
        return ComponentTree.of(ComponentEntry.of(path, ref));
    }
}
