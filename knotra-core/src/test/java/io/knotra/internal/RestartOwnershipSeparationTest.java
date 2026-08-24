package io.knotra.internal;

import io.knotra.ActivationContext;
import io.knotra.Component;
import io.knotra.ComponentDescriptor;
import io.knotra.ComponentFactory;
import io.knotra.ComponentState;
import io.knotra.ConfiguredMountHandle;
import io.knotra.KnotraRuntime;
import io.knotra.MountHandle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * cleanup restart 分支的旧 future completion 所有权与新 transition 所有权分离。
 *
 * <p>postcommit primary 失败先清空过渡槽后，cleanup 仍必须推进 reconcile：独立预约
 * restart、不链接也不完成旧 future；槽位已被其他所有者占用时不重复预约/驱动。</p>
 */
final class RestartOwnershipSeparationTest {
    private final KnotraRuntime runtime = KnotraRuntime.create();
    private final DefaultKnotraRuntime internal = (DefaultKnotraRuntime) runtime;

    @AfterEach
    void tearDown() {
        runtime.close();
    }

    @Test
    void primaryFailedCleanupStillReconcilesThroughIndependentRestart() throws Exception {
        CountDownLatch startEntered = new CountDownLatch(1);
        CountDownLatch releaseStart = new CountDownLatch(1);
        CountDownLatch completionCaptured = new CountDownLatch(1);
        AtomicReference<CompletableFuture<ComponentState>> gatedFuture =
                new AtomicReference<>();
        AtomicReference<Runnable> gatedCompletion = new AtomicReference<>();
        AtomicBoolean publicationFaultInjected = new AtomicBoolean();
        StartCounter starts = new StartCounter();

        try {
            ConfiguredMountHandle<String> handle = mountConfigured(
                    "independent-restart",
                    "v1",
                    config -> {
                        int attempt = starts.next();
                        if (attempt == 1) {
                            startEntered.countDown();
                            assertTrue(releaseStart.await(10, TimeUnit.SECONDS));
                        }
                    });

            assertTrue(startEntered.await(10, TimeUnit.SECONDS),
                    "first activation did not enter start()");
            CompletableFuture<ComponentState> original =
                    handle.whenSettled().toCompletableFuture();

            internal.activationCoordinator().activationFailureCompletionGate =
                    (future, completion) -> {
                        gatedFuture.set(future);
                        gatedCompletion.set(completion);
                        completionCaptured.countDown();
                    };

            // reconfigure 使第一代 activation stale：组件 STOPPING、goal 保持 RUNNING。
            CompletableFuture<ComponentState> reconfigured =
                    handle.reconfigureAsync("v2").toCompletableFuture();

            // 结构事务已同步提交；此刻注入的一次性发布故障只会命中第一代 activation。
            internal.activationCoordinator().transitionPublicationProbe = () -> {
                if (!publicationFaultInjected.getAndSet(true)) {
                    throw new IllegalStateException("injected publication fault");
                }
            };

            releaseStart.countDown();

            assertTrue(completionCaptured.await(10, TimeUnit.SECONDS),
                    "primary failure completion was not captured");
            CompletableFuture<ComponentState> failed = gatedFuture.get();
            assertSame(original, failed, "captured future must be the original transition");

            // primary 失败已清槽并持有旧 F；restart 必须独立推进，而不是等待旧 F 释放。
            assertEquals(ComponentState.ACTIVE,
                    awaitState(handle.handleId(), ComponentState.ACTIVE));
            assertFalse(failed.isDone(),
                    "independent restart must not link the original future");
            assertEquals(2, starts.get(), "restart must drive exactly one new start");

            PublishedKernelState state = internal.publishedState();
            state.validateInvariants();
            ComponentRuntime component = state.index.components.get(handle.handleId());
            assertEquals(1, component.reconcileState().attempts());
            awaitNoPending(handle.handleId());

            // 释放 gate：旧 F 只收到 primary 失败的异常完成，restart 结果不回写旧 F。
            gatedCompletion.get().run();
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> failed.get(10, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof IllegalStateException);
            assertTrue(failure.getCause().getMessage().contains(
                    "activation postcommit failed after publish"));
            assertThrows(ExecutionException.class,
                    () -> reconfigured.get(10, TimeUnit.SECONDS));
        } finally {
            runCaptured(gatedCompletion);
        }
    }

    @Test
    void reserveRestartDistinguishesOwnershipAndRejectsExecutorRefusal() throws Exception {
        ComponentRuntime component = ComponentRuntimeStateTest.component();
        CompletableFuture<ComponentState> original =
                new CompletableFuture<>();

        // 槽位为空（primary 失败已清槽）：独立 restart，不链接旧 future。
        ComponentRuntime.RestartReservation independent =
                component.reserveRestart(original, 19L, "component restart");
        assertTrue(independent.created());
        assertEquals(ComponentRuntime.RestartOwnership.INDEPENDENT, independent.ownership());
        assertFalse(independent.linksOriginalFuture());

        // 槽位已有其他未完成所有者：不重复预约，由新所有者收敛。
        ComponentRuntime.RestartReservation foreign =
                component.reserveRestart(original, 20L, "component restart");
        assertFalse(foreign.created());
        assertEquals(ComponentRuntime.RestartOwnership.FOREIGN, foreign.ownership());

        // 仍拥有旧 future：替换并望远镜链接。
        ComponentRuntime.RestartReservation linked =
                component.reserveRestart(independent.reservation().future(), 21L, "component restart");
        assertTrue(linked.created());
        assertEquals(ComponentRuntime.RestartOwnership.LINKED, linked.ownership());
        assertTrue(linked.linksOriginalFuture());
        assertNotNull(component.pendingSnapshot());

        // executor 拒绝：restart future 异常完成、清槽并清除 pending 元数据。
        TransitionScheduler rejecting = new TransitionScheduler(
                new Object(),
                task -> {
                    throw new RejectedExecutionException("executor closed");
                },
                (candidate, future) -> {
                    throw new AssertionError("driver must not run after rejection");
                },
                () -> 22L);
        rejecting.driveReservation(linked.reservation());

        ExecutionException rejection = assertThrows(ExecutionException.class,
                () -> linked.reservation().future().get(1, TimeUnit.SECONDS));
        assertTrue(rejection.getCause() instanceof TransitionRejectedStateException);
        assertFalse(component.ownsTransition(linked.reservation().future()));
        assertNull(component.pendingSnapshot());
        assertFalse(original.isDone(), "restart refusal must not complete the old future");
    }

    @Test
    void cancelledForeignRestartOwnerReschedulesIndependentRestart() throws Exception {
        CountDownLatch startEntered = new CountDownLatch(1);
        CountDownLatch releaseStart = new CountDownLatch(1);
        CountDownLatch completionCaptured = new CountDownLatch(1);
        AtomicReference<CompletableFuture<ComponentState>> gatedFuture =
                new AtomicReference<>();
        AtomicReference<Runnable> gatedCompletion = new AtomicReference<>();
        AtomicBoolean publicationFaultInjected = new AtomicBoolean();
        AtomicBoolean foreignInjected = new AtomicBoolean();
        AtomicReference<ComponentRuntime.Reservation> foreign = new AtomicReference<>();
        StartCounter starts = new StartCounter();

        try {
            ConfiguredMountHandle<String> handle = mountConfigured(
                    "cancelled-foreign-owner",
                    "v1",
                    config -> {
                        int attempt = starts.next();
                        if (attempt == 1) {
                            startEntered.countDown();
                            assertTrue(releaseStart.await(10, TimeUnit.SECONDS));
                        }
                    });

            assertTrue(startEntered.await(10, TimeUnit.SECONDS),
                    "first activation did not enter start()");
            CompletableFuture<ComponentState> original =
                    handle.whenSettled().toCompletableFuture();

            internal.activationCoordinator().activationFailureCompletionGate =
                    (future, completion) -> {
                        gatedFuture.set(future);
                        gatedCompletion.set(completion);
                        completionCaptured.countDown();
                    };

            CompletableFuture<ComponentState> reconfigured =
                    handle.reconfigureAsync("v2").toCompletableFuture();

            internal.activationCoordinator().transitionPublicationProbe = () -> {
                if (!publicationFaultInjected.getAndSet(true)) {
                    throw new IllegalStateException("injected publication fault");
                }
            };

            internal.activationCoordinator().cleanupFinalCommitProbe = () -> {
                if (foreignInjected.getAndSet(true)) {
                    return;
                }
                ComponentRuntime component = internal.publishedState()
                        .index.components.get(handle.handleId());
                foreign.set(component.reserveTransition(
                        internal.activationCoordinator().scheduler().pendingTime(),
                        "foreign restart owner"));
            };

            releaseStart.countDown();

            assertTrue(completionCaptured.await(10, TimeUnit.SECONDS),
                    "primary failure completion was not captured");
            assertSame(original, gatedFuture.get());
            assertEquals(ComponentState.WAITING,
                    awaitState(handle.handleId(), ComponentState.WAITING));

            ComponentRuntime.Reservation reservation = foreign.get();
            assertNotNull(reservation, "foreign owner reservation was not injected");
            awaitSlotOwner(handle, reservation.future());

            // 模拟外部事务 commit 失败后的 cancelCreated + completeCancelled。
            ComponentRuntime component = internal.publishedState().index.components
                    .get(handle.handleId());
            assertTrue(component.cancelTransition(reservation.future()));
            internal.activationCoordinator().scheduler().completeCancelled(
                    List.of(reservation.future()));
            assertEquals(ComponentState.ACTIVE,
                    awaitState(handle.handleId(), ComponentState.ACTIVE));
            assertEquals(2, starts.get(), "handoff must drive exactly one restart");
            awaitNoPending(handle.handleId());
            assertFalse(gatedFuture.get().isDone(),
                    "handoff must not complete the primary-owned original future");

            gatedCompletion.get().run();
            assertThrows(ExecutionException.class,
                    () -> gatedFuture.get().get(10, TimeUnit.SECONDS));
            assertThrows(ExecutionException.class,
                    () -> reconfigured.get(10, TimeUnit.SECONDS));
        } finally {
            runCaptured(gatedCompletion);
        }
    }

    @Test
    void cancelledForeignOwnerDuringRuntimeCloseDoesNotRestart() throws Exception {
        CountDownLatch startEntered = new CountDownLatch(1);
        CountDownLatch releaseStart = new CountDownLatch(1);
        CountDownLatch completionCaptured = new CountDownLatch(1);
        AtomicReference<CompletableFuture<ComponentState>> gatedFuture =
                new AtomicReference<>();
        AtomicReference<Runnable> gatedCompletion = new AtomicReference<>();
        AtomicBoolean publicationFaultInjected = new AtomicBoolean();
        AtomicBoolean foreignInjected = new AtomicBoolean();
        AtomicReference<ComponentRuntime.Reservation> foreign = new AtomicReference<>();
        StartCounter starts = new StartCounter();

        try {
            ConfiguredMountHandle<String> handle = mountConfigured(
                    "closing-foreign-owner",
                    "v1",
                    config -> {
                        int attempt = starts.next();
                        if (attempt == 1) {
                            startEntered.countDown();
                            assertTrue(releaseStart.await(10, TimeUnit.SECONDS));
                        }
                    });

            assertTrue(startEntered.await(10, TimeUnit.SECONDS),
                    "first activation did not enter start()");
            internal.activationCoordinator().activationFailureCompletionGate =
                    (future, completion) -> {
                        gatedFuture.set(future);
                        gatedCompletion.set(completion);
                        completionCaptured.countDown();
                    };

            CompletableFuture<ComponentState> reconfigured =
                    handle.reconfigureAsync("v2").toCompletableFuture();
            internal.activationCoordinator().transitionPublicationProbe = () -> {
                if (!publicationFaultInjected.getAndSet(true)) {
                    throw new IllegalStateException("injected publication fault");
                }
            };
            internal.activationCoordinator().cleanupFinalCommitProbe = () -> {
                if (foreignInjected.getAndSet(true)) {
                    return;
                }
                ComponentRuntime component = internal.publishedState()
                        .index.components.get(handle.handleId());
                foreign.set(component.reserveTransition(
                        internal.activationCoordinator().scheduler().pendingTime(),
                        "foreign restart owner"));
            };

            releaseStart.countDown();
            assertTrue(completionCaptured.await(10, TimeUnit.SECONDS));
            assertEquals(ComponentState.WAITING,
                    awaitState(handle.handleId(), ComponentState.WAITING));
            ComponentRuntime.Reservation reservation = foreign.get();
            assertNotNull(reservation);
            awaitSlotOwner(handle, reservation.future());

            ComponentRuntime component = internal.publishedState().index.components
                    .get(handle.handleId());
            assertNotNull(component);
            CompletableFuture<Void> closed =
                    runtime.closeAsync().toCompletableFuture();
            assertTrue(internal.isClosing(), "runtime close must be visible before cancellation");
            assertTrue(component.cancelTransition(reservation.future()));
            internal.activationCoordinator().scheduler().completeCancelled(
                    List.of(reservation.future()));

            closed.get(10, TimeUnit.SECONDS);
            assertEquals(1, starts.get(), "closing must suppress foreign handoff restart");
            assertEquals(ComponentState.DISPOSED, internal.componentState(handle.handleId()));

            gatedCompletion.get().run();
            assertThrows(ExecutionException.class,
                    () -> gatedFuture.get().get(10, TimeUnit.SECONDS));
            assertThrows(ExecutionException.class,
                    () -> reconfigured.get(10, TimeUnit.SECONDS));
        } finally {
            runCaptured(gatedCompletion);
        }
    }

    @Test
    void foreignSlotOwnerDrivesSingleRestartWithoutDuplicateHandoff() throws Exception {
        CountDownLatch startEntered = new CountDownLatch(1);
        CountDownLatch releaseStart = new CountDownLatch(1);
        CountDownLatch completionCaptured = new CountDownLatch(1);
        AtomicReference<CompletableFuture<ComponentState>> gatedFuture =
                new AtomicReference<>();
        AtomicReference<Runnable> gatedCompletion = new AtomicReference<>();
        AtomicBoolean publicationFaultInjected = new AtomicBoolean();
        AtomicBoolean foreignInjected = new AtomicBoolean();
        AtomicReference<ComponentRuntime.Reservation> foreign = new AtomicReference<>();
        StartCounter starts = new StartCounter();

        try {
            ConfiguredMountHandle<String> handle = mountConfigured(
                    "foreign-restart-owner",
                    "v1",
                    config -> {
                        int attempt = starts.next();
                        if (attempt == 1) {
                            startEntered.countDown();
                            assertTrue(releaseStart.await(10, TimeUnit.SECONDS));
                        }
                    });

            assertTrue(startEntered.await(10, TimeUnit.SECONDS),
                    "first activation did not enter start()");
            CompletableFuture<ComponentState> original =
                    handle.whenSettled().toCompletableFuture();

            internal.activationCoordinator().activationFailureCompletionGate =
                    (future, completion) -> {
                        gatedFuture.set(future);
                        gatedCompletion.set(completion);
                        completionCaptured.countDown();
                    };

            CompletableFuture<ComponentState> reconfigured =
                    handle.reconfigureAsync("v2").toCompletableFuture();

            internal.activationCoordinator().transitionPublicationProbe = () -> {
                if (!publicationFaultInjected.getAndSet(true)) {
                    throw new IllegalStateException("injected publication fault");
                }
            };

            // cleanup 提交前、restart 分支前，在同一协调器临界区内注入外部所有者。
            internal.activationCoordinator().cleanupFinalCommitProbe = () -> {
                if (foreignInjected.getAndSet(true)) {
                    return;
                }
                ComponentRuntime component = internal.publishedState()
                        .index.components.get(handle.handleId());
                foreign.set(component.reserveTransition(
                        internal.activationCoordinator().scheduler().pendingTime(),
                        "foreign restart owner"));
            };

            releaseStart.countDown();

            assertTrue(completionCaptured.await(10, TimeUnit.SECONDS),
                    "primary failure completion was not captured");
            assertSame(original, gatedFuture.get());

            // cleanup 收敛为 WAITING；重启责任完全交给外部所有者。
            assertEquals(ComponentState.WAITING,
                    awaitState(handle.handleId(), ComponentState.WAITING));
            ComponentRuntime.Reservation reservation = foreign.get();
            assertNotNull(reservation, "foreign owner reservation was not injected");
            assertTrue(reservation.created());
            awaitSlotOwner(handle, reservation.future());
            assertEquals(1, starts.get(), "cleanup must not duplicate the foreign driver");

            internal.activationCoordinator().scheduler().driveReservation(reservation);
            assertEquals(ComponentState.ACTIVE,
                    awaitState(handle.handleId(), ComponentState.ACTIVE));
            assertEquals(ComponentState.ACTIVE, reservation.future().get(10, TimeUnit.SECONDS));
            assertEquals(2, starts.get(),
                    "normal foreign completion must not schedule a second start");
            awaitNoPending(handle.handleId());

            PublishedKernelState state = internal.publishedState();
            state.validateInvariants();
            assertFalse(gatedFuture.get().isDone(),
                    "foreign owner branch must not complete the original future");

            gatedCompletion.get().run();
            assertThrows(ExecutionException.class,
                    () -> gatedFuture.get().get(10, TimeUnit.SECONDS));
        } finally {
            runCaptured(gatedCompletion);
        }
    }

    private ConfiguredMountHandle<String> mountConfigured(
            String mountId,
            String config,
            ConfiguredStart start) {
        ComponentFactory<String> factory = new ComponentFactory<>() {
            @Override
            public String factoryId() {
                return mountId;
            }

            @Override
            public Component<String> create() {
                return new Component<>() {
                    @Override
                    public ComponentDescriptor descriptor() {
                        return ComponentDescriptor.named(mountId);
                    }

                    @Override
                    public void start(ActivationContext context, String cfg) throws Exception {
                        start.start(cfg);
                    }
                };
            }
        };
        return runtime.advanced().transact(transaction -> transaction.mount(
                runtime.root(), mountId, factory, config)).value();
    }

    private ComponentState awaitState(String handleId, ComponentState expected)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        ComponentState state = internal.componentState(handleId);
        while (state != expected) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError(
                        "component did not converge to " + expected + ", now " + state);
            }
            Thread.sleep(1);
            state = internal.componentState(handleId);
        }
        return state;
    }

    private void awaitNoPending(String handleId) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (runtime.advanced().pendingOperations().operations().stream()
                .anyMatch(operation -> operation.targetId().equals(handleId))) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError(
                        "converged component still has pending transition metadata");
            }
            Thread.sleep(1);
        }
    }

    private void awaitSlotOwner(
            MountHandle handle,
            CompletableFuture<ComponentState> expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (handle.whenSettled().toCompletableFuture() != expected) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("transition slot is not owned by the foreign future");
            }
            Thread.sleep(1);
        }
    }

    private static void runCaptured(AtomicReference<Runnable> gatedCompletion) {
        Runnable completion = gatedCompletion.get();
        if (completion != null) {
            completion.run();
        }
    }

    @FunctionalInterface
    private interface ConfiguredStart {
        void start(String config) throws Exception;
    }

    private static final class StartCounter {
        private final AtomicInteger counter = new AtomicInteger();

        int next() {
            return counter.incrementAndGet();
        }

        int get() {
            return counter.get();
        }
    }
}
