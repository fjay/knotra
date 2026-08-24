package io.knotra.internal;

import io.knotra.ComponentDescriptor;
import io.knotra.ComponentState;
import io.knotra.KnotraRuntime;
import io.knotra.MountFactory;
import io.knotra.MountHandle;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 同 JVM 连续压测 stale primary failure 与 cleanup 的完成权竞争。
 *
 * <p>每轮都持有原始过渡 future 的 gate，让 cleanup 先提交状态与 scope 清理，
 * 再释放 primary 异常完成；任何一轮原始 future 被正常完成都视为所有权回归。</p>
 */
final class StaleCompletionOwnershipStressTest {
    private static final int ROUNDS = Math.max(1, Integer.getInteger(
            "knotra.stale.ownership.rounds", 2000));

    private static final Field STORE_FIELD;
    private static final Method COMMIT_METHOD;

    static {
        try {
            STORE_FIELD = DefaultKnotraRuntime.class.getDeclaredField("kernelState");
            STORE_FIELD.setAccessible(true);
            COMMIT_METHOD = KernelStateStore.class.getDeclaredMethod(
                    "commitLocked", PublishedKernelState.class, PublishedKernelState.class);
            COMMIT_METHOD.setAccessible(true);
        } catch (ReflectiveOperationException error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    @Test
    void stalePrimaryFailureNeverLetsCleanupCompleteTheOriginalFuture() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            runRound(round);
        }
    }

    private void runRound(int round) throws Exception {
        KnotraRuntime publicRuntime = KnotraRuntime.create();
        DefaultKnotraRuntime runtime = (DefaultKnotraRuntime) publicRuntime;
        try {
            CountDownLatch activationPaused = new CountDownLatch(1);
            CountDownLatch releaseActivation = new CountDownLatch(1);
            CountDownLatch completionCaptured = new CountDownLatch(1);
            CountDownLatch scopeCleanup = new CountDownLatch(1);
            AtomicReference<CompletableFuture<ComponentState>> gatedFuture =
                    new AtomicReference<>();
            AtomicReference<Runnable> gatedCompletion = new AtomicReference<>();
            String name = "stale-ownership-" + round;

            runtime.activationCoordinator().activationFinalPublishProbe = () ->
                    commitUnrelatedGeneration(runtime);
            runtime.activationCoordinator().activationDecisionProbe = () -> {
                runtime.activationCoordinator().activationDecisionProbe = null;
                activationPaused.countDown();
                try {
                    assertTrue(releaseActivation.await(10, TimeUnit.SECONDS),
                            "round " + round);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                }
            };
            runtime.activationCoordinator().activationFailureCompletionGate =
                    (future, completion) -> {
                        gatedFuture.set(future);
                        gatedCompletion.set(completion);
                        completionCaptured.countDown();
                    };

            MountHandle handle = publicRuntime.advanced().transact(transaction -> transaction.mount(
                    publicRuntime.root(),
                    name,
                    MountFactory.of(
                            name,
                            ComponentDescriptor.named(name),
                            context -> context.lifecycle().onClose(
                                    name + "-cleanup",
                                    scopeCleanup::countDown)))).value();

            assertTrue(activationPaused.await(10, TimeUnit.SECONDS), "round " + round);
            CompletableFuture<ComponentState> observed =
                    handle.whenSettled().toCompletableFuture();
            releaseActivation.countDown();
            assertTrue(completionCaptured.await(10, TimeUnit.SECONDS), "round " + round);
            CompletableFuture<ComponentState> original = gatedFuture.get();
            assertSame(original, observed, "round " + round);

            assertTrue(scopeCleanup.await(10, TimeUnit.SECONDS), "round " + round);
            assertEquals(ComponentState.FAILED,
                    awaitTerminalState(runtime, handle.handleId()), "round " + round);
            assertFalse(original.isDone(), "round " + round
                    + ": cleanup must not complete the original future");

            gatedCompletion.get().run();
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> original.get(10, TimeUnit.SECONDS), "round " + round);
            assertTrue(failure.getCause() instanceof IllegalStateException, "round " + round);
            assertTrue(failure.getCause().getMessage().contains(
                    "stale kernel state expected"), "round " + round);
        } finally {
            publicRuntime.close();
        }
    }

    private static void commitUnrelatedGeneration(DefaultKnotraRuntime runtime) {
        try {
            KernelStateStore store = (KernelStateStore) STORE_FIELD.get(runtime);
            synchronized (runtime.coordinator) {
                PublishedKernelState current = runtime.publishedState();
                COMMIT_METHOD.invoke(store, current, successor(current));
            }
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    private static PublishedKernelState successor(PublishedKernelState base) {
        return new KernelStateDraft(base).publish(
                new RuntimeView.Draft(base.view).publishOnce());
    }

    private static ComponentState awaitTerminalState(
            DefaultKnotraRuntime runtime,
            String handleId) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        ComponentState state = runtime.componentState(handleId);
        while (state == ComponentState.STARTING || state == ComponentState.STOPPING) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("component did not converge beyond " + state);
            }
            Thread.sleep(1);
            state = runtime.componentState(handleId);
        }
        return state;
    }
}
