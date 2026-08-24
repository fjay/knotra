package io.knotra.internal;

import io.knotra.ComponentDescriptor;
import io.knotra.ComponentState;
import io.knotra.ContextHandle;
import io.knotra.KnotraRuntime;
import io.knotra.MountFactory;
import io.knotra.MountHandle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
final class KernelStateStoreTest {
    private final KnotraRuntime runtime = KnotraRuntime.create();
    private final DefaultKnotraRuntime internal = (DefaultKnotraRuntime) runtime;

    @AfterEach
    void tearDown() {
        try {
            runtime.close();
        } catch (RuntimeException expected) {
            // 注入的首次清理失败会让根 Context 进入 FAILED；重试 close 重新调度清理。
            try {
                runtime.close();
            } catch (RuntimeException retryExpected) {
                // 清理提交探针仍在时，最终排空可能再次失败；测试资源到此已足够释放。
            }
        }
    }

    @Test
    void staleExpectedIsRejectedWithoutChangingCurrentState() {
        Object lock = new Object();
        KernelStateStore store = KernelStateStore.initial(
                lock, new ContextHandleImpl(internal, "ctx-root"));
        PublishedKernelState initial;
        PublishedKernelState first;
        synchronized (lock) {
            initial = store.read();
            first = successor(initial);
            store.commitLocked(initial, first);
        }

        KernelStateStore.CommitRejectedException error = assertThrows(
                KernelStateStore.CommitRejectedException.class,
                () -> {
                    synchronized (lock) {
                        store.commitLocked(initial, successor(initial));
                    }
                });
        assertEquals("stale kernel state expected", error.getMessage());
        synchronized (lock) {
            assertSame(first, store.read());
        }
    }

    @Test
    void nonMonotonicGenerationIsRejectedWithoutChangingCurrentState() {
        Object lock = new Object();
        KernelStateStore store = KernelStateStore.initial(
                lock, new ContextHandleImpl(internal, "ctx-root"));
        PublishedKernelState initial;
        PublishedKernelState first;
        synchronized (lock) {
            initial = store.read();
            first = successor(initial);
            store.commitLocked(initial, first);
        }

        KernelStateStore.CommitRejectedException error = assertThrows(
                KernelStateStore.CommitRejectedException.class,
                () -> {
                    synchronized (lock) {
                        store.commitLocked(store.read(), first);
                    }
                });
        assertEquals("kernel state generation must increase", error.getMessage());
        synchronized (lock) {
            assertSame(first, store.read());
        }
    }

    @Test
    void commitRequiresTheCoordinatorLock() {
        Object lock = new Object();
        KernelStateStore store = KernelStateStore.initial(
                lock, new ContextHandleImpl(internal, "ctx-root"));
        PublishedKernelState initial = store.read();

        KernelStateStore.CommitRejectedException error = assertThrows(
                KernelStateStore.CommitRejectedException.class,
                () -> store.commitLocked(initial, successor(initial)));
        assertEquals("kernel state commit requires coordinator lock", error.getMessage());
        assertSame(initial, store.read());
    }

    @Test
    void concurrentCommitsFormOneMonotonicLineage() throws Exception {
        Object lock = new Object();
        KernelStateStore store = KernelStateStore.initial(
                lock, new ContextHandleImpl(internal, "ctx-root"));
        int commits = 24;
        ExecutorService executor = Executors.newFixedThreadPool(8);
        List<Future<PublishedKernelState>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < commits; index++) {
                futures.add(executor.submit(() -> {
                    synchronized (lock) {
                        PublishedKernelState expected = store.read();
                        return store.commitLocked(expected, successor(expected));
                    }
                }));
            }
            List<PublishedKernelState> states = futures.stream()
                    .map(KernelStateStoreTest::uncheckedGet)
                    .sorted((first, second) -> Long.compare(
                            first.generation, second.generation))
                    .toList();
            states.forEach(PublishedKernelState::validateInvariants);
            for (int index = 0; index < commits; index++) {
                assertEquals(index + 1, states.get(index).generation);
            }
        } finally {
            executor.shutdown();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }

        synchronized (lock) {
            PublishedKernelState current = store.read();
            assertEquals(commits, current.generation);
            assertNotNull(current.index.contextHandles.get("ctx-root"));
        }
    }

    @Test
    void diagnosticReadsUseTheSamePublishedGeneration() {
        ContextHandle context = runtime.advanced().childContext(
                runtime.root(), "diagnostic-state");
        PublishedKernelState state = internal.publishedState();

        assertEquals(state.generation, runtime.advanced().snapshot().generation());
        DiagnosticSupport.FailureSnapshot snapshot =
                DiagnosticSupport.failureSnapshot(state, "missing-handle");
        assertEquals(ComponentState.DISPOSED, snapshot.state());
        state.validateInvariants();
    }

    @Test
    void staleActivationFinalPublishConvergesOriginalFuture() throws Exception {
        CountDownLatch activationPaused = new CountDownLatch(1);
        CountDownLatch releaseActivation = new CountDownLatch(1);
        internal.activationCoordinator().activationFinalPublishProbe = () ->
                commitUnrelatedGeneration(internal);
        pauseActivationAtDecision(activationPaused, releaseActivation);
        try {
            MountHandle handle = runtime.advanced().transact(transaction -> transaction.mount(
                    runtime.root(),
                    "stale-final-publish",
                    MountFactory.of(
                            "stale-final-publish",
                            ComponentDescriptor.named("stale-final-publish"),
                            context -> {
                            }))).value();

            // 在 primary 失败清槽前捕获真实过渡 future；事后 whenSettled 只能拿到
            // 新的已完成观察 future，会造成观察窗口假失败。
            CompletableFuture<ComponentState> settled =
                    captureOriginalFuture(handle, activationPaused, releaseActivation);

            java.util.concurrent.ExecutionException settledFailure =
                    assertThrows(
                            java.util.concurrent.ExecutionException.class,
                            () -> settled.get(10, TimeUnit.SECONDS));
            assertTrue(settledFailure.getCause() instanceof IllegalStateException);
            assertTrue(settledFailure.getCause().getMessage().contains(
                    "stale kernel state expected"));
            PublishedKernelState state = internal.publishedState();
            state.validateInvariants();
        } finally {
            releaseActivation.countDown();
            internal.activationCoordinator().activationDecisionProbe = null;
            internal.activationCoordinator().activationFinalPublishProbe = null;
        }
    }

    @Test
    void staleActivationFinalPublishRecoversThroughCleanupAndRetry() throws Exception {
        CountDownLatch scopeCleanup = new CountDownLatch(1);
        CountDownLatch activationPaused = new CountDownLatch(1);
        CountDownLatch releaseActivation = new CountDownLatch(1);
        AtomicBoolean injected = new AtomicBoolean();
        internal.activationCoordinator().activationFinalPublishProbe = () -> {
            if (!injected.getAndSet(true)) {
                commitUnrelatedGeneration(internal);
            }
        };
        pauseActivationAtDecision(activationPaused, releaseActivation);
        try {
            MountHandle handle = runtime.advanced().transact(transaction -> transaction.mount(
                    runtime.root(),
                    "stale-final-recovery",
                    MountFactory.of(
                            "stale-final-recovery",
                            ComponentDescriptor.named("stale-final-recovery"),
                            context -> context.lifecycle().onClose(
                                    "stale-final-recovery-cleanup",
                                    scopeCleanup::countDown)))).value();
            CompletableFuture<ComponentState> settled =
                    captureOriginalFuture(handle, activationPaused, releaseActivation);

            java.util.concurrent.ExecutionException failure =
                    assertThrows(
                            java.util.concurrent.ExecutionException.class,
                            () -> settled.get(10, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof IllegalStateException);
            assertTrue(failure.getCause().getMessage().contains(
                    "stale kernel state expected"));

            // 首候选被拒绝后组件不得悬停 STARTING：中止清理按原语义收敛到 FAILED。
            ComponentState converged = awaitTerminalState(handle.handleId());
            assertEquals(ComponentState.FAILED, converged);
            PublishedKernelState state = internal.publishedState();
            state.validateInvariants();

            // scope 资源照常清理；收敛后 pending 不再为该组件保留过渡解释。
            assertTrue(scopeCleanup.await(10, TimeUnit.SECONDS));
            assertTrue(runtime.advanced().pendingOperations().operations().stream()
                    .noneMatch(operation -> operation.targetId().equals(handle.handleId())));

            // retry 意图可收敛：重新激活成功。
            assertEquals(ComponentState.ACTIVE, handle.retryAsync()
                    .toCompletableFuture().get(10, TimeUnit.SECONDS));
        } finally {
            releaseActivation.countDown();
            internal.activationCoordinator().activationDecisionProbe = null;
            internal.activationCoordinator().activationFinalPublishProbe = null;
        }
    }

    @Test
    void staleFinalPublishRecoveryFailureFallsBackToEmergency() throws Exception {
        CountDownLatch scopeCleanup = new CountDownLatch(1);
        CountDownLatch activationPaused = new CountDownLatch(1);
        CountDownLatch releaseActivation = new CountDownLatch(1);
        AtomicBoolean staleInjected = new AtomicBoolean();
        AtomicBoolean recoveryFaulted = new AtomicBoolean();
        internal.activationCoordinator().activationFinalPublishProbe = () -> {
            if (!staleInjected.getAndSet(true)) {
                commitUnrelatedGeneration(internal);
            }
        };
        pauseActivationAtDecision(activationPaused, releaseActivation);
        internal.activationCoordinator().scheduler().transitionReservationProbe = () -> {
            // 只在恢复候选构造期间注入故障，避免影响挂载事务与首候选。
            if (staleInjected.get() && !recoveryFaulted.getAndSet(true)) {
                throw new IllegalStateException("injected recovery fault");
            }
        };
        try {
            MountHandle handle = runtime.advanced().transact(transaction -> transaction.mount(
                    runtime.root(),
                    "emergency-final-publish",
                    MountFactory.of(
                            "emergency-final-publish",
                            ComponentDescriptor.named("emergency-final-publish"),
                            context -> context.lifecycle().onClose(
                                    "emergency-final-publish-cleanup",
                                    scopeCleanup::countDown)))).value();
            CompletableFuture<ComponentState> settled =
                    captureOriginalFuture(handle, activationPaused, releaseActivation);

            java.util.concurrent.ExecutionException failure =
                    assertThrows(
                            java.util.concurrent.ExecutionException.class,
                            () -> settled.get(10, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof IllegalStateException);
            assertTrue(failure.getCause().getMessage().contains(
                    "stale kernel state expected"));

            // 恢复提交失败退到紧急路径：FAILED + failedCleanup 归属，而不是悬停 STARTING。
            assertEquals(ComponentState.FAILED, awaitTerminalState(handle.handleId()));
            PublishedKernelState state = internal.publishedState();
            state.validateInvariants();
            ComponentRuntime component = state.index.components.get(handle.handleId());
            assertNotNull(component.failedCleanup());

            // retry 驱动 failedCleanup 清理；stale 裁决清除启动失败标记后按原语义重启。
            assertEquals(ComponentState.ACTIVE, handle.retryAsync()
                    .toCompletableFuture().get(10, TimeUnit.SECONDS));
            assertTrue(scopeCleanup.await(10, TimeUnit.SECONDS));
            assertTrue(runtime.advanced().pendingOperations().operations().stream()
                    .noneMatch(operation -> operation.targetId().equals(handle.handleId())));
        } finally {
            releaseActivation.countDown();
            internal.activationCoordinator().activationDecisionProbe = null;
            internal.activationCoordinator().activationFinalPublishProbe = null;
            internal.activationCoordinator().scheduler().transitionReservationProbe = null;
        }
    }

    @Test
    void primaryFailureCompletionGateBlocksCleanupFromCompletingOriginalFuture() throws Exception {
        CountDownLatch activationPaused = new CountDownLatch(1);
        CountDownLatch releaseActivation = new CountDownLatch(1);
        CountDownLatch completionCaptured = new CountDownLatch(1);
        CountDownLatch scopeCleanup = new CountDownLatch(1);
        AtomicBoolean staleInjected = new AtomicBoolean();
        AtomicReference<CompletableFuture<ComponentState>> gatedFuture = new AtomicReference<>();
        AtomicReference<Runnable> gatedCompletion = new AtomicReference<>();
        internal.activationCoordinator().activationFinalPublishProbe = () -> {
            if (!staleInjected.getAndSet(true)) {
                commitUnrelatedGeneration(internal);
            }
        };
        pauseActivationAtDecision(activationPaused, releaseActivation);
        internal.activationCoordinator().activationFailureCompletionGate = (future, completion) -> {
            gatedFuture.set(future);
            gatedCompletion.set(completion);
            completionCaptured.countDown();
        };
        try {
            MountHandle handle = runtime.advanced().transact(transaction -> transaction.mount(
                    runtime.root(),
                    "primary-failure-ownership",
                    MountFactory.of(
                            "primary-failure-ownership",
                            ComponentDescriptor.named("primary-failure-ownership"),
                            context -> context.lifecycle().onClose(
                                    "primary-failure-ownership-cleanup",
                                    scopeCleanup::countDown)))).value();

            CompletableFuture<ComponentState> observed =
                    captureOriginalFuture(handle, activationPaused, releaseActivation);
            assertTrue(completionCaptured.await(10, TimeUnit.SECONDS));
            CompletableFuture<ComponentState> original = gatedFuture.get();
            assertSame(original, observed);

            // cleanup 先提交状态与 scope 清理；原始 future 仍由 primary 失败持有，保持 pending。
            assertTrue(scopeCleanup.await(10, TimeUnit.SECONDS));
            assertEquals(ComponentState.FAILED, awaitTerminalState(handle.handleId()));
            assertFalse(original.isDone(),
                    "cleanup must not normally complete a future owned by primary failure");

            // 释放 gate 后原始 future 收到异常 stale 完成，而不是正常 FAILED。
            gatedCompletion.get().run();
            java.util.concurrent.ExecutionException failure =
                    assertThrows(
                            java.util.concurrent.ExecutionException.class,
                            () -> original.get(10, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof IllegalStateException);
            assertTrue(failure.getCause().getMessage().contains(
                    "stale kernel state expected"));
        } finally {
            releaseActivation.countDown();
            internal.activationCoordinator().activationDecisionProbe = null;
            internal.activationCoordinator().activationFinalPublishProbe = null;
            internal.activationCoordinator().activationFailureCompletionGate = null;
        }
    }

    @Test
    void cleanupCommitFailureCompletesFutureOutsideCoordinator() throws Exception {
        ExecutorService reentry = Executors.newSingleThreadExecutor();
        AtomicBoolean injected = new AtomicBoolean();
        internal.activationCoordinator().cleanupFinalCommitProbe = () -> {
            if (!injected.getAndSet(true)) {
                commitUnrelatedGeneration(internal);
            }
        };
        try {
            CountDownLatch reentryDone = new CountDownLatch(1);
            AtomicReference<Throwable> reentryFailure = new AtomicReference<>();
            MountHandle handle = runtime.advanced().transact(transaction -> transaction.mount(
                    runtime.root(),
                    "cleanup-commit-failure",
                    MountFactory.of(
                            "cleanup-commit-failure",
                            ComponentDescriptor.named("cleanup-commit-failure"),
                            context -> {
                                throw new IllegalStateException("injected start failure");
                            }))).value();
            CompletableFuture<ComponentState> settled =
                    handle.whenSettled().toCompletableFuture();
            settled.whenComplete((state, error) -> {
                try {
                    reentry.submit(() -> runtime.advanced().transact(
                            transaction -> transaction.childContext(
                                    runtime.root(), "cleanup-reentry")))
                            .get(20, TimeUnit.SECONDS);
                } catch (Throwable callbackError) {
                    reentryFailure.compareAndSet(null, callbackError);
                } finally {
                    reentryDone.countDown();
                }
            });

            java.util.concurrent.ExecutionException failure =
                    assertThrows(
                            java.util.concurrent.ExecutionException.class,
                            () -> settled.get(20, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof IllegalStateException);
            assertTrue(failure.getCause().getMessage().contains(
                    "component cleanup publish failed"));
            // 异常完成必须发生在协调器外：回调内阻塞等待另一线程的事务不得死锁。
            assertTrue(reentryDone.await(20, TimeUnit.SECONDS),
                    "completion callback must not run under coordinator");
            assertNull(reentryFailure.get());
        } finally {
            internal.activationCoordinator().cleanupFinalCommitProbe = null;
            reentry.shutdown();
            assertTrue(reentry.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void contextFinalizationCommitFailureCompletesOutsideCoordinator() throws Exception {
        ExecutorService reentry = Executors.newSingleThreadExecutor();
        AtomicBoolean injected = new AtomicBoolean();
        internal.contextCoordinator().contextFinalCommitProbe = () -> {
            if (!injected.getAndSet(true)) {
                commitUnrelatedGeneration(internal);
            }
        };
        try {
            ContextHandle context = runtime.advanced().transact(transaction -> transaction.childContext(
                    runtime.root(), "context-finalization-failure")).value();
            runtime.advanced().transact(transaction -> transaction.mount(
                    context,
                    "context-finalization-component",
                    MountFactory.of(
                            "context-finalization-component",
                            ComponentDescriptor.named("context-finalization-component"),
                            activationContext -> {
                            }))).value();

            CountDownLatch reentryDone = new CountDownLatch(1);
            AtomicReference<Throwable> reentryFailure = new AtomicReference<>();
            CompletableFuture<io.knotra.ContextState> disposed = context.disposeAsync().toCompletableFuture();
            disposed.whenComplete((ignored, error) -> {
                try {
                    reentry.submit(() -> runtime.advanced().transact(
                            transaction -> transaction.childContext(
                                    runtime.root(), "context-reentry")))
                            .get(20, TimeUnit.SECONDS);
                } catch (Throwable callbackError) {
                    reentryFailure.compareAndSet(null, callbackError);
                } finally {
                    reentryDone.countDown();
                }
            });

            java.util.concurrent.ExecutionException failure =
                    assertThrows(
                            java.util.concurrent.ExecutionException.class,
                            () -> disposed.get(20, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof IllegalStateException);
            assertEquals("context finalization publish failed",
                    failure.getCause().getMessage());
            assertTrue(reentryDone.await(20, TimeUnit.SECONDS),
                    "completion callback must not run under coordinator");
            assertNull(reentryFailure.get());
        } finally {
            internal.contextCoordinator().contextFinalCommitProbe = null;
            reentry.shutdown();
            assertTrue(reentry.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void transactionsActivationsAndContextDisposalsSerializeWithoutStaleCommits() throws Exception {
        int workers = 6;
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        List<Future<Void>> operations = new ArrayList<>();
        try {
            for (int index = 0; index < workers; index++) {
                String name = "linear-context-" + index;
                operations.add(executor.submit(() -> {
                    ContextHandle context = runtime.advanced().transact(
                            transaction -> transaction.childContext(runtime.root(), name))
                            .value();
                    MountHandle handle = runtime.advanced().transact(transaction -> transaction.mount(
                            context,
                            name,
                            MountFactory.of(
                                    name,
                                    ComponentDescriptor.named(name),
                                    activationContext -> {
                                    }))).value();
                    assertEquals(ComponentState.ACTIVE, handle.whenSettled()
                            .toCompletableFuture().get(10, TimeUnit.SECONDS));
                    assertEquals(io.knotra.ContextState.DISPOSED, context.disposeAsync()
                            .toCompletableFuture().get(10, TimeUnit.SECONDS));
                    return null;
                }));
            }
            for (Future<Void> operation : operations) {
                operation.get(10, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdown();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }

        PublishedKernelState state = internal.publishedState();
        state.validateInvariants();
        assertTrue(state.view.contexts.values().stream().noneMatch(
                context -> context.name().startsWith("linear-context-")));
    }

    private void pauseActivationAtDecision(
            CountDownLatch activationPaused,
            CountDownLatch releaseActivation) {
        internal.activationCoordinator().activationDecisionProbe = () -> {
            internal.activationCoordinator().activationDecisionProbe = null;
            activationPaused.countDown();
            try {
                assertTrue(releaseActivation.await(10, TimeUnit.SECONDS));
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        };
    }

    private CompletableFuture<ComponentState> captureOriginalFuture(
            MountHandle handle,
            CountDownLatch activationPaused,
            CountDownLatch releaseActivation) throws InterruptedException {
        assertTrue(activationPaused.await(10, TimeUnit.SECONDS),
                "activation did not reach decision probe");
        // 此刻 primary 失败尚未清槽，whenSettled 返回的就是原始过渡 future。
        CompletableFuture<ComponentState> settled =
                handle.whenSettled().toCompletableFuture();
        releaseActivation.countDown();
        return settled;
    }
    private static PublishedKernelState uncheckedGet(
            Future<PublishedKernelState> future) {
        try {
            return future.get(10, TimeUnit.SECONDS);
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    private static void commitUnrelatedGeneration(DefaultKnotraRuntime runtime) {
        try {
            Field storeField = DefaultKnotraRuntime.class.getDeclaredField("kernelState");
            storeField.setAccessible(true);
            KernelStateStore store = (KernelStateStore) storeField.get(runtime);
            Method commit = KernelStateStore.class.getDeclaredMethod(
                    "commitLocked", PublishedKernelState.class, PublishedKernelState.class);
            commit.setAccessible(true);
            synchronized (runtime.coordinator) {
                PublishedKernelState current = runtime.publishedState();
                commit.invoke(store, current, successor(current));
            }
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    private ComponentState awaitTerminalState(String handleId) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        ComponentState state = internal.componentState(handleId);
        while (state == ComponentState.STARTING || state == ComponentState.STOPPING) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("component did not converge beyond " + state);
            }
            Thread.sleep(10);
            state = internal.componentState(handleId);
        }
        return state;
    }

    private static PublishedKernelState successor(PublishedKernelState base) {
        return new KernelStateDraft(base).publish(
                new RuntimeView.Draft(base.view).publishOnce());
    }
}
