package io.knotra.internal;

import io.knotra.ComponentDescriptor;
import io.knotra.ComponentState;
import io.knotra.ContextHandle;
import io.knotra.ContextState;
import io.knotra.KnotraConfig;
import io.knotra.MountFactory;
import io.knotra.MountHandle;
import io.knotra.TransactionRejectedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Context cleanup retry intent 是 postcommit 效果，不能被失败事务写入 live 状态。 */
final class ContextCleanupIntentCommitTest {
    private final DefaultKnotraRuntime runtime =
            new DefaultKnotraRuntime(KnotraConfig.defaults(), System::nanoTime);

    @AfterEach
    void tearDown() {
        runtime.close();
    }

    @Test
    void failedTransactionAfterContextDisposeDoesNotWriteCleanupRetryIntent() throws Exception {
        ContextHandle context = runtime.advanced().childContext(runtime.root(), "failed-intent");
        MountHandle handle = mountWithOneCleanupFailure(context, "blocked");
        failFirstContextCleanup(context);
        ComponentRuntime component = runtime.publishedState()
                .index.components.get(handle.handleId());
        assertSame(ComponentRuntime.RetryIntent.NONE, component.peekRetryIntent());

        assertThrows(TransactionRejectedException.class, () -> runtime.advanced().transact(
                transaction -> {
                    transaction.dispose(context);
                    transaction.mount(context, "must-reject-in-draft", idleFactory());
                    return null;
                }));

        assertSame(ComponentRuntime.RetryIntent.NONE, component.peekRetryIntent(),
                "a rejected transaction must not leave cleanup retry intent in live state");
        assertEquals(ComponentState.FAILED, handle.state());
        assertEquals(ContextState.FAILED, context.state());
    }

    @Test
    void successfulContextDisposeWritesCleanupRetryIntentAfterStructureCommit() throws Exception {
        ContextHandle context = runtime.advanced().childContext(runtime.root(), "successful-intent");
        MountHandle handle = mountWithOneCleanupFailure(context, "blocked");
        failFirstContextCleanup(context);
        ComponentRuntime component = runtime.publishedState()
                .index.components.get(handle.handleId());
        assertSame(ComponentRuntime.RetryIntent.NONE, component.peekRetryIntent());

        runtime.activationCoordinator().transitionPublicationProbe = () -> {
            runtime.activationCoordinator().transitionPublicationProbe = null;
            assertSame(ComponentRuntime.RetryIntent.CLEANUP, component.peekRetryIntent(),
                    "cleanup retry intent must first become visible after the disposal commit");
        };
        var receipt = runtime.advanced().transact(transaction -> {
            transaction.dispose(context);
            return null;
        });
        receipt.settlement().whenSettled().toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertEquals(ComponentState.DISPOSED, handle.whenSettled()
                .toCompletableFuture().get(10, TimeUnit.SECONDS));
        assertEquals(ContextState.DISPOSED, context.state());
        assertSame(ComponentRuntime.RetryIntent.NONE, component.peekRetryIntent());
    }

    private MountHandle mountWithOneCleanupFailure(ContextHandle context, String mountId) {
        AtomicInteger failures = new AtomicInteger(1);
        MountHandle handle = runtime.advanced().transact(transaction -> transaction.mount(
                context,
                mountId,
                MountFactory.of("blocked-factory",
                        ComponentDescriptor.named("blocked-component"),
                        componentContext -> componentContext.lifecycle().onClose(
                                "cleanup",
                                () -> {
                                    if (failures.getAndDecrement() > 0) {
                                        throw new IllegalStateException("cleanup failed once");
                                    }
                                })))).value();
        ComponentState state = handle.whenSettled().toCompletableFuture().join();
        assertEquals(ComponentState.ACTIVE, state);
        return handle;
    }

    private void failFirstContextCleanup(ContextHandle context) throws Exception {
        CompletableFuture<?> disposal = runtime
                .disposeContext((ContextHandleImpl) context)
                .toCompletableFuture();
        try {
            disposal.get(10, TimeUnit.SECONDS);
            throw new AssertionError("expected context cleanup failure");
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            String message = String.valueOf(cause.getMessage());
            while ((cause = cause.getCause()) != null) {
                message += "; " + cause.getMessage();
            }
            assertTrue(message.contains("context cleanup failed"), message);
        }
    }

    private static MountFactory idleFactory() {
        return MountFactory.of("idle-factory",
                ComponentDescriptor.named("idle-component"),
                context -> {
                });
    }
}
