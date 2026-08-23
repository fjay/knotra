package io.knotra.loader;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.knotra.ComponentFactory;
import io.knotra.KnotraRuntime;
import io.knotra.MountOptions;
import io.knotra.NoConfig;

import static org.junit.jupiter.api.Assertions.*;
import static io.knotra.loader.LoaderStateStore.ManagedEntry;

final class LoaderStateStoreTest {

    private final KnotraRuntime runtime = KnotraRuntime.create();

    @AfterEach
    void tearDown() throws Exception {
        runtime.close();
    }

    @Test
    void registerWritesEntryAndContextIntoTheSameView() {
        io.knotra.ContextHandle context = runtime.advanced().transact(transaction ->
                transaction.childContext(runtime.root(), "alpha")).value();
        ComponentFactory<NoConfig> factory = LoaderTestKit.factory("alpha", (activation, config) -> {
        });
        MountAttempt attempt = new MountAttempt(
                "alpha",
                "alpha",
                context,
                runtime.advanced().transact(transaction -> transaction.mount(
                        context, "alpha", factory, MountOptions.DEFAULT)).value(),
                ResolvedFactory.of(FactoryIdentity.of("alpha", "", "alpha"), factory),
                NoConfig.INSTANCE);
        LoaderStateStore store = new LoaderStateStore();

        ManagedEntry registered = store.register(attempt);

        LoaderStateStore.LoaderView view = store.view();
        assertEquals(registered, view.entries().get("alpha"));
        assertEquals(context, view.contexts().get("alpha"));
        assertTrue(view.diagnostics().isEmpty());
    }

    @Test
    void pruneRemovesThePathAndItsDescendants() {
        LoaderStateStore store = new LoaderStateStore();
        store.put(new ManagedEntry("alpha", "alpha", null, null, null, null));
        store.put(new ManagedEntry("alpha/child", "child", null, null, null, null));
        store.put(new ManagedEntry("beta", "beta", null, null, null, null));

        store.prune("alpha");
        store.publish(List.of());

        LoaderStateStore.LoaderView view = store.view();
        assertTrue(view.entries().containsKey("beta"));
        assertFalse(view.entries().containsKey("alpha"));
        assertFalse(view.entries().containsKey("alpha/child"));
    }

    @Test
    void concurrentReadsNeverMixPublishedGenerations() throws Exception {
        LoaderStateStore store = new LoaderStateStore();
        LoaderDiagnostic first = LoaderDiagnostic.of(
                LoaderDiagnosticCode.ACTIVATION_FAILED, "alpha", "first generation");
        LoaderDiagnostic second = LoaderDiagnostic.of(
                LoaderDiagnosticCode.TEARDOWN_FAILED, "beta", "second generation");
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean stopping = new AtomicBoolean();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> publisher = executor.submit(() -> {
                started.countDown();
                boolean firstGeneration = true;
                while (!stopping.get()) {
                    store.clear();
                    if (firstGeneration) {
                        store.put(new ManagedEntry("alpha", "alpha", null, null, null, null));
                        store.publish(List.of(first));
                    } else {
                        store.put(new ManagedEntry("beta", "beta", null, null, null, null));
                        store.publish(List.of(second));
                    }
                    firstGeneration = !firstGeneration;
                }
                return null;
            });
            assertTrue(started.await(10, TimeUnit.SECONDS));

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (System.nanoTime() < deadline) {
                LoaderStateStore.LoaderView view = store.view();
                if (view.entries().containsKey("alpha")) {
                    assertEquals(List.of(first), view.diagnostics(),
                            () -> "torn snapshot: " + view);
                } else if (view.entries().containsKey("beta")) {
                    assertEquals(List.of(second), view.diagnostics(),
                            () -> "torn snapshot: " + view);
                }
            }
            stopping.set(true);
            publisher.get(10, TimeUnit.SECONDS);
        } finally {
            stopping.set(true);
            executor.shutdown();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }
}
