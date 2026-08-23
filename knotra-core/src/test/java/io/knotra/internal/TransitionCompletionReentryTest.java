package io.knotra.internal;

import io.knotra.ComponentDescriptor;
import io.knotra.ComponentState;
import io.knotra.MountFactory;
import io.knotra.MountHandle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用户 whenSettled 回调在 transition future 完成时同步重入 runtime。
 *
 * <p>完成动作必须已经释放 chainLock/coordinator；否则回调内的事务预约会自锁。</p>
 */
final class TransitionCompletionReentryTest {
    private final DefaultKnotraRuntime runtime =
            new DefaultKnotraRuntime(io.knotra.KnotraConfig.defaults(), System::nanoTime);
    private CompletableFuture<Void> callbackTransaction;

    @AfterEach
    void tearDown() {
        runtime.activationCoordinator().activationPostPublishEffectProbe = null;
        runtime.activationCoordinator().transitionPublicationProbe = null;
        // RED 阶段的旧实现会把回调线程留在 chainLock 自锁上；此时不能在测试线程再等 close。
        if (callbackTransaction == null || callbackTransaction.isDone()) {
            runtime.close();
        }
    }

    @Test
    void normalFinishCallbackCanStartAndAwaitTransaction() throws Exception {
        CountDownLatch startEntered = new CountDownLatch(1);
        CompletableFuture<Void> releaseStart = new CompletableFuture<>();
        MountHandle handle = runtime.advanced().transact(transaction -> transaction.mount(
                runtime.root(),
                "normal-finish",
                gatedFactory(startEntered, releaseStart))).value();
        assertTrue(startEntered.await(10, TimeUnit.SECONDS));
        attachReenteringCallback(handle, "normal-finish-callback", true);

        releaseStart.complete(null);
        assertEquals(ComponentState.ACTIVE, handle.whenSettled()
                .toCompletableFuture().get(10, TimeUnit.SECONDS));
        assertNull(awaitCallbackTransaction());
    }

    @Test
    void failedFinishCallbackCanStartAndAwaitTransaction() throws Exception {
        CountDownLatch startEntered = new CountDownLatch(1);
        CompletableFuture<Void> releaseStart = new CompletableFuture<>();
        MountHandle handle = runtime.advanced().transact(transaction -> transaction.mount(
                runtime.root(),
                "failed-finish",
                gatedFactory(startEntered, releaseStart))).value();
        assertTrue(startEntered.await(10, TimeUnit.SECONDS));
        attachReenteringCallback(handle, "failed-finish-callback", true);
        runtime.activationCoordinator().activationPostPublishEffectProbe = () -> {
            runtime.activationCoordinator().activationPostPublishEffectProbe = null;
            throw new IllegalStateException("injected postpublish failure");
        };

        releaseStart.complete(null);
        ExecutionException failure = assertThrows(ExecutionException.class, () ->
                handle.whenSettled().toCompletableFuture().get(10, TimeUnit.SECONDS));
        assertTrue(failure.getCause().getMessage().contains("injected postpublish failure"));
        assertNull(awaitCallbackTransaction());
    }
    @Test
    void executorRejectionCallbackCanStartAndAwaitTransaction() {
        runtime.executor.shutdown();
        AtomicReference<CompletableFuture<ComponentState>> observed =
                new AtomicReference<>();
        runtime.activationCoordinator().transitionPublicationProbe = () -> {
            runtime.activationCoordinator().transitionPublicationProbe = null;
            MountHandleImpl pending = runtime.publishedState()
                    .index.componentHandles.values().stream()
                    .filter(candidate -> candidate.mountId().equals("rejected-transition"))
                    .findFirst()
                    .orElseThrow();
            observed.set(attachReenteringCallback(
                    pending, "executor-rejection-callback", false));
        };

        runtime.advanced().transact(transaction -> transaction.mount(
                runtime.root(),
                "rejected-transition",
                MountFactory.of("rejected-factory",
                        ComponentDescriptor.named("rejected-transition"),
                        context -> {
                        })));

        ExecutionException rejection = assertThrows(ExecutionException.class, () ->
                observed.get().get(10, TimeUnit.SECONDS));
        assertTrue(rejection.getCause() instanceof TransitionRejectedStateException,
                () -> String.valueOf(rejection.getCause()));
        assertNull(awaitCallbackTransaction(),
                "callback transaction must reserve its transition after rejection handling");
    }

    private CompletableFuture<ComponentState> attachReenteringCallback(
            MountHandle handle,
            String capabilityName,
            boolean awaitSettlement) {
        callbackTransaction = new CompletableFuture<>();
        CompletableFuture<ComponentState> settled =
                handle.whenSettled().toCompletableFuture();
        settled.whenComplete((state, error) -> {
            try {
                var receipt = runtime.advanced().transact(transaction -> transaction.mount(
                        runtime.root(),
                        capabilityName,
                        MountFactory.of(capabilityName + "-factory",
                                ComponentDescriptor.named(capabilityName),
                                context -> {
                                })));
                if (awaitSettlement) {
                    receipt.settlement().whenSettled().toCompletableFuture().get(10, TimeUnit.SECONDS);
                }
                callbackTransaction.complete(null);
            } catch (Throwable callbackError) {
                callbackTransaction.completeExceptionally(callbackError);
            }
        });
        return settled;
    }

    private Throwable awaitCallbackTransaction() {
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(10), () ->
                    callbackTransaction.get(10, TimeUnit.SECONDS));
            return null;
        } catch (Throwable error) {
            return error instanceof ExecutionException execution
                    ? execution.getCause()
                    : error;
        }
    }

    private static MountFactory gatedFactory(
            CountDownLatch startEntered,
            CompletableFuture<Void> releaseStart) {
        return MountFactory.of("gated-factory",
                ComponentDescriptor.named("gated-component"),
                context -> {
                    startEntered.countDown();
                    releaseStart.get(10, TimeUnit.SECONDS);
                });
    }
}
