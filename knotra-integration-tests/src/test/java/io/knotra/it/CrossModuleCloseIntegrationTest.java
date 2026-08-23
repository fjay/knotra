package io.knotra.it;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.function.Supplier;

import com.example.integration.contract.ContractEvent;
import com.example.integration.contract.IntegrationCoordinator;
import io.knotra.ComponentState;
import io.knotra.KnotraRuntime;
import io.knotra.MountHandle;
import io.knotra.PendingOperationsSnapshot;
import io.knotra.TransactionRejectedException;
import io.knotra.events.EventBus;
import io.knotra.events.EventCapabilities;
import io.knotra.events.EventDefinition;
import io.knotra.events.EventDispatch;
import io.knotra.events.EventBusFactory;
import io.knotra.loader.ComponentEntry;
import io.knotra.loader.ComponentTree;
import io.knotra.pf4j.loader.Pf4jFactoryResolver;
import io.knotra.loader.FactoryRef;
import io.knotra.loader.KnotraLoader;
import io.knotra.loader.ReconcileResult;
import io.knotra.pf4j.ArtifactState;
import io.knotra.pf4j.Pf4jArtifactAdapter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ResourceLock(IntegrationTestKit.INTEGRATION_COORDINATOR_LOCK)
final class CrossModuleCloseIntegrationTest {

    private static final FactoryRef GREETING = FactoryRef.of("integration-greeting");
    private static final EventDefinition.Serial<ContractEvent> CONTRACT_EVENTS =
            EventDefinition.serial(ContractEvent.class);

    @RegisterExtension
    private final KnotraIntegrationExtension runtimeExtension =
            KnotraIntegrationExtension.manualRuntimeClose();

    private MountHandle mountBus(KnotraRuntime runtime) {
        return runtime.mount("bus", new EventBusFactory());
    }

    @Test
    void concurrentClosesFromAllOwnersConvergeAndStayIdempotent(
            KnotraRuntime runtime,
            @TempDir Path pluginsRoot) throws Throwable {
        Pf4jArtifactAdapter adapter = null;
        KnotraLoader loader = null;
        Throwable testFailure = null;
        try {
            adapter = IntegrationTestKit.adapter(pluginsRoot, runtime);
            adapter.loadArtifactAsync(IntegrationTestKit.fixture()).toCompletableFuture().join();
            adapter.factories().resolve("integration-greeting", String.class).orElseThrow()
                    .mount(runtime.root(), "greeting", "hello");
            loader = KnotraLoader.over(
                    runtime,
                    runtime.root(),
                    Pf4jFactoryResolver.of(adapter));
            MountHandle bus = mountBus(runtime);
            ReconcileResult reconcile = loader.reconcile(ComponentTree.of(
                    ComponentEntry.configured("greeting", GREETING, "loader")));
            assertTrue(reconcile.converged(), () -> reconcile.diagnostics().toString());
            assertEquals(ComponentState.ACTIVE, bus.awaitSettled(Duration.ofSeconds(30)));

            final Pf4jArtifactAdapter coordinatedAdapter = adapter;
            final KnotraLoader coordinatedLoader = loader;
            try (ExecutorService executor = Executors.newFixedThreadPool(3)) {
                CompletableFuture<Void> adapterClose = concurrentClose(
                        coordinatedAdapter::closeAsync, executor);
                CompletableFuture<Void> loaderClose = concurrentClose(
                        coordinatedLoader::closeAsync, executor);
                CompletableFuture<Void> runtimeClose = concurrentClose(
                        runtime::closeAsync, executor);
                awaitCloseWithoutTakeoverRejection("adapter", adapterClose);
                awaitCloseWithoutTakeoverRejection("loader", loaderClose);
                awaitCloseWithoutTakeoverRejection("runtime", runtimeClose);
            }

            adapter.closeAsync().toCompletableFuture().get(10, TimeUnit.SECONDS);
            loader.closeAsync().toCompletableFuture().get(10, TimeUnit.SECONDS);
            runtime.closeAsync().toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertEquals(ArtifactState.UNLOADED, adapter.artifact(
                    IntegrationTestKit.ARTIFACT_ID).orElseThrow().state());
            assertTrue(runtime.advanced().snapshot().mounts().isEmpty());
        } catch (Throwable failure) {
            testFailure = failure;
        } finally {
            closeRemainingResources(testFailure, adapter, loader, runtime);
        }
        if (testFailure != null) {
            throw testFailure;
        }
    }

    /**
     * 默认重复次数保持单次 CI 在秒级以内；压测时用
     * -Dknotra.it.blocked-close-repetitions=N 调大（如 1000）。
     */
    private static final int DEFAULT_BLOCKED_CLOSE_REPETITIONS = 4;

    @Test
    void blockedConcurrentClosesStayDiagnosableWithoutChangingCloseOrder(
            @TempDir Path pluginsRoot) throws Throwable {
        for (int repetition = 0; repetition < blockedCloseRepetitions(); repetition++) {
            runBlockedConcurrentClosesOnce(
                    pluginsRoot.resolve("repetition-" + repetition));
        }
    }

    private static int blockedCloseRepetitions() {
        return Math.max(1, Integer.getInteger(
                "knotra.it.blocked-close-repetitions", DEFAULT_BLOCKED_CLOSE_REPETITIONS));
    }

    private void runBlockedConcurrentClosesOnce(Path pluginsRoot) throws Throwable {
        IntegrationCoordinator.reset();
        Files.createDirectories(pluginsRoot);
        KnotraRuntime runtime = KnotraRuntime.create();
        Pf4jArtifactAdapter adapter = null;
        KnotraLoader loader = null;
        Throwable testFailure = null;
        try {
            adapter = IntegrationTestKit.adapter(pluginsRoot, runtime);
            adapter.loadArtifactAsync(IntegrationTestKit.fixture()).toCompletableFuture().join();
            adapter.factories().resolve("integration-greeting", String.class).orElseThrow()
                    .mount(runtime.root(), "greeting", "hello");
            loader = KnotraLoader.over(
                    runtime,
                    runtime.root(),
                    Pf4jFactoryResolver.of(adapter));
            ReconcileResult reconcile = loader.reconcile(ComponentTree.of(
                    ComponentEntry.configured("greeting", GREETING, "loader")));
            assertTrue(reconcile.converged(), () -> reconcile.diagnostics().toString());
            MountHandle bus = mountBus(runtime);
            assertEquals(ComponentState.ACTIVE, bus.awaitSettled(Duration.ofSeconds(30)));
            EventBus eventBus = runtime.root().view().require(EventCapabilities.EVENT_BUS);
            MountHandle consumer = adapter.factories()
                    .resolveNoConfig("integration-event-consumer").orElseThrow()
                    .mount(runtime.root(), "plugin-consumer");
            assertEquals(ComponentState.ACTIVE, consumer.awaitSettled(Duration.ofSeconds(30)));
            CompletableFuture<EventDispatch<ContractEvent>> held =
                    eventBus.dispatch(CONTRACT_EVENTS, new ContractEvent("held"))
                            .toCompletableFuture();
            IntegrationCoordinator.eventEntered().get(10, TimeUnit.SECONDS);

            final Pf4jArtifactAdapter coordinatedAdapter = adapter;
            final KnotraLoader coordinatedLoader = loader;
            try (ExecutorService executor = Executors.newFixedThreadPool(3)) {
                CompletableFuture<Void> adapterClose = concurrentClose(
                        coordinatedAdapter::closeAsync, executor);
                CompletableFuture<Void> loaderClose = concurrentClose(
                        coordinatedLoader::closeAsync, executor);
                CompletableFuture<Void> runtimeClose = concurrentClose(
                        runtime::closeAsync, executor);
                // 三路 close 同时进行且事件门未释放：先等待阻塞事实成立，再无锁采样。
                // 排空归属存在合法竞态：可能由 adapter 排空（ARTIFACT_DRAIN 链），
                // 也可能被并发 runtime.close 接管（core 侧 COMPONENT_TRANSITION 链）；
                // 两种交叠都必须能解释阻塞，close 收敛契约由后续 awaitClose 保持严格。
                await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                    assertTrue(coordinatedLoader.pendingOperations().closeRequested());
                    boolean adapterOwnedDrain = coordinatedAdapter
                            .pendingOperations().operations().stream()
                            .anyMatch(item -> item.kind() == PendingOperationsSnapshot.Kind.ARTIFACT_DRAIN
                                    && item.targetId().equals(IntegrationTestKit.ARTIFACT_ID)
                                    && item.detail().contains(consumer.handleId()));
                    boolean runtimeOwnedTeardown = runtime.advanced()
                            .pendingOperations().operations().stream()
                            .anyMatch(item -> item.kind() == PendingOperationsSnapshot.Kind.COMPONENT_TRANSITION
                                    && item.targetId().equals(consumer.handleId()));
                    assertTrue(adapterOwnedDrain || runtimeOwnedTeardown,
                            () -> "blocked close is not diagnosable\nadapter: "
                                    + coordinatedAdapter.pendingOperations().render()
                                    + "\ncore: "
                                    + runtime.advanced().pendingOperations().render());
                    requireOperation(runtime.advanced().pendingOperations(), item ->
                            item.kind() == PendingOperationsSnapshot.Kind.RUNTIME_CLOSE
                                    && item.waitsFor() == PendingOperationsSnapshot.WaitType.RUNTIME_DRAIN);
                    requireOperation(runtime.advanced().pendingOperations(), item ->
                            item.kind() == PendingOperationsSnapshot.Kind.LIFECYCLE_CLEANUP
                                    && item.detail().contains("integration-shared-listener"));
                });
                PendingOperationsSnapshot artifactWhileBlocked = assertTimeout(
                        Duration.ofSeconds(1), coordinatedAdapter::pendingOperations);
                PendingOperationsSnapshot loaderWhileBlocked = assertTimeout(
                        Duration.ofSeconds(1), coordinatedLoader::pendingOperations);
                PendingOperationsSnapshot coreWhileBlocked = assertTimeout(
                        Duration.ofSeconds(1), () -> runtime.advanced().pendingOperations());
                assertTrue(artifactWhileBlocked.closeRequested());
                assertTrue(loaderWhileBlocked.closeRequested());
                assertTrue(coreWhileBlocked.closeRequested());
                // adapter 侧的 ARTIFACT_DRAIN 明细链由 EventBusIntegrationTest 在
                // adapter 独占排空的场景下严格覆盖；并发 close 的归属竞态下这里只断言
                // 三个 owner 的 closeRequested 如实保留，阻塞解释权交给上面的 either/or。
                requireOperation(coreWhileBlocked, item ->
                        item.kind() == PendingOperationsSnapshot.Kind.RUNTIME_CLOSE);

                IntegrationCoordinator.releaseEvent();
                awaitCloseWithoutTakeoverRejection("adapter", adapterClose);
                awaitCloseWithoutTakeoverRejection("loader", loaderClose);
                awaitCloseWithoutTakeoverRejection("runtime", runtimeClose);
                assertTrue(held.get(10, TimeUnit.SECONDS).successful());
            }

            // 释放后三个 owner 各自的挂起集合都收敛为空，closeRequested 仍如实保留。
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                assertTrue(coordinatedAdapter.pendingOperations().operations().isEmpty(),
                        () -> coordinatedAdapter.pendingOperations().render());
                assertTrue(coordinatedLoader.pendingOperations().operations().isEmpty(),
                        () -> coordinatedLoader.pendingOperations().render());
                assertTrue(runtime.advanced().pendingOperations().operations().isEmpty(),
                        () -> runtime.advanced().pendingOperations().render());
                assertTrue(coordinatedAdapter.pendingOperations().closeRequested());
                assertTrue(coordinatedLoader.pendingOperations().closeRequested());
                assertTrue(runtime.advanced().pendingOperations().closeRequested());
            });
            assertEquals(ArtifactState.UNLOADED, adapter.artifact(
                    IntegrationTestKit.ARTIFACT_ID).orElseThrow().state());
        } catch (Throwable failure) {
            testFailure = failure;
        } finally {
            IntegrationCoordinator.releaseEvent();
            closeRemainingResources(testFailure, adapter, loader, runtime);
        }
        if (testFailure != null) {
            throw testFailure;
        }
    }

    @Test
    void reverseOrderClosesConvergeAndRepeatedCallsAreIdempotent(
            KnotraRuntime runtime,
            @TempDir Path pluginsRoot) throws Throwable {
        Pf4jArtifactAdapter adapter = null;
        KnotraLoader loader = null;
        Throwable testFailure = null;
        try {
            adapter = IntegrationTestKit.adapter(pluginsRoot, runtime);
            adapter.loadArtifactAsync(IntegrationTestKit.fixture()).toCompletableFuture().join();
            adapter.factories().resolve("integration-greeting", String.class).orElseThrow()
                    .mount(runtime.root(), "greeting", "hello");
            loader = KnotraLoader.over(
                    runtime,
                    runtime.root(),
                    Pf4jFactoryResolver.of(adapter));
            ReconcileResult reconcile = loader.reconcile(ComponentTree.of(
                    ComponentEntry.configured("greeting", GREETING, "loader")));
            assertTrue(reconcile.converged(), () -> reconcile.diagnostics().toString());

            runtime.closeAsync().toCompletableFuture().get(30, TimeUnit.SECONDS);
            loader.close();
            adapter.close();

            runtime.closeAsync().toCompletableFuture().get(10, TimeUnit.SECONDS);
            loader.closeAsync().toCompletableFuture().get(10, TimeUnit.SECONDS);
            adapter.closeAsync().toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertEquals(ArtifactState.UNLOADED, adapter.artifact(
                    IntegrationTestKit.ARTIFACT_ID).orElseThrow().state());
        } catch (Throwable failure) {
            testFailure = failure;
        } finally {
            closeRemainingResources(testFailure, adapter, loader, runtime);
        }
        if (testFailure != null) {
            throw testFailure;
        }
    }

    @Test
    void failedArtifactCleanupRetriesOnTheNextCloseAttempt(
            KnotraRuntime runtime, @TempDir Path pluginsRoot)
            throws Throwable {
        Pf4jArtifactAdapter adapter = null;
        Throwable testFailure = null;
        try {
            adapter = IntegrationTestKit.adapter(pluginsRoot, runtime);
            adapter.loadArtifactAsync(IntegrationTestKit.fixture()).toCompletableFuture().join();
            MountHandle handle = adapter.factories()
                    .resolveNoConfig("integration-failing-cleanup").orElseThrow()
                    .mount(runtime.root(), "failing");
            assertEquals(ComponentState.ACTIVE, handle.awaitSettled(Duration.ofSeconds(30)));

            IntegrationCoordinator.failNextCleanup();
            CompletableFuture<Void> firstClose = adapter.closeAsync().toCompletableFuture();
            assertThrows(CompletionException.class, firstClose::join);
            assertEquals(ArtifactState.DRAIN_FAILED, adapter.artifact(
                    IntegrationTestKit.ARTIFACT_ID).orElseThrow().state());
            assertEquals(ComponentState.FAILED, handle.state());
            assertFalse(adapter.ownership(IntegrationTestKit.ARTIFACT_ID).isEmpty());

            IntegrationCoordinator.allowCleanup();
            adapter.closeAsync().toCompletableFuture().join();
            assertEquals(ArtifactState.UNLOADED, adapter.artifact(
                    IntegrationTestKit.ARTIFACT_ID).orElseThrow().state());
            assertEquals(ComponentState.DISPOSED, handle.state());
            assertTrue(runtime.advanced().snapshot().mounts().isEmpty());
            runtime.close();
        } catch (Throwable failure) {
            testFailure = failure;
        } finally {
            IntegrationCoordinator.allowCleanup();
            closeRemainingResources(testFailure, adapter, runtime);
        }
        if (testFailure != null) {
            throw testFailure;
        }
    }

    private static CompletableFuture<Void> concurrentClose(
            Supplier<CompletionStage<Void>> close, ExecutorService executor) {
        return CompletableFuture.supplyAsync(close, executor)
                .thenCompose(CompletionStage::toCompletableFuture);
    }

    /**
     * runtime.close 与 adapter/loader.close 并发时，runtime 接管处置的 TransactionRejected
     * 必须被适配器内部消化为等待，而不是泄漏成任一 owner 的 close 失败。
     */
    private static void awaitCloseWithoutTakeoverRejection(
            String owner, CompletableFuture<Void> close) throws Exception {
        try {
            close.get(60, TimeUnit.SECONDS);
        } catch (ExecutionException failure) {
            TransactionRejectedException rejection =
                    findTransactionRejected(failure.getCause());
            if (rejection != null) {
                throw new AssertionError(
                        owner + " close surfaced runtime takeover rejection", rejection);
            }
            throw failure;
        }
    }

    private static TransactionRejectedException findTransactionRejected(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof TransactionRejectedException rejection) {
                return rejection;
            }
        }
        return null;
    }

    private static PendingOperationsSnapshot.Operation requireOperation(
            PendingOperationsSnapshot snapshot,
            Predicate<PendingOperationsSnapshot.Operation> filter) {
        return snapshot.operations().stream()
                .filter(filter)
                .findFirst()
                .orElseThrow(() -> new AssertionError(snapshot.render()));
    }

    private static void closeRemainingResources(
            Throwable testFailure,
            AutoCloseable... resources) throws Throwable {
        Throwable cleanupFailure = null;
        for (AutoCloseable resource : resources) {
            if (resource == null) {
                continue;
            }
            try {
                resource.close();
            } catch (Throwable failure) {
                cleanupFailure = appendCleanupFailure(cleanupFailure, failure);
            }
        }
        if (cleanupFailure != null) {
            if (testFailure == null) {
                throw cleanupFailure;
            }
            testFailure.addSuppressed(cleanupFailure);
        }
    }

    private static Throwable appendCleanupFailure(
            Throwable current,
            Throwable additional) {
        if (current == null) {
            return additional;
        }
        current.addSuppressed(additional);
        return current;
    }
}
