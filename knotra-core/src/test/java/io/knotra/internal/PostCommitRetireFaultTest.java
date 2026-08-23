package io.knotra.internal;

import io.knotra.CapabilityKey;
import io.knotra.CapabilityRequirement;
import io.knotra.ComponentDescriptor;
import io.knotra.ComponentState;
import io.knotra.ContextHandle;
import io.knotra.ContextState;
import io.knotra.KnotraRuntime;
import io.knotra.PendingOperationsSnapshot;
import io.knotra.MountFactory;
import io.knotra.MountHandle;
import io.knotra.RegistrationHandle;
import io.knotra.Settlement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** provider lease 真实 retire 之后的 postcommit 阶段故障必须隔离，且不得回滚或遗留孤立租约。 */
final class PostCommitRetireFaultTest {
    private final KnotraRuntime publicRuntime = KnotraRuntime.create();
    private final DefaultKnotraRuntime runtime =
            (DefaultKnotraRuntime) publicRuntime;

    @AfterEach
    void tearDown() {
        runtime.activationCoordinator().providerLeaseRetireFaultProbe = null;
        publicRuntime.close();
    }

    @Test
    void hostTransactionRetireFaultStillDrivesBothDirtyTransitions() throws Exception {
        CapabilityKey<String> firstKey =
                CapabilityKey.of("host-retire-fault-a", String.class);
        CapabilityKey<String> secondKey =
                CapabilityKey.of("host-retire-fault-b", String.class);
        ContextHandle workspace = publicRuntime.advanced().transact(transaction ->
                transaction.childContext(publicRuntime.root(), "host-fault"))
                .value();
        RegistrationHandle firstProvider = publicRuntime.advanced().transact(
                transaction -> transaction.provide(workspace, firstKey, "first"))
                .value();
        RegistrationHandle secondProvider = publicRuntime.advanced().transact(
                transaction -> transaction.provide(workspace, secondKey, "second"))
                .value();
        MountHandle first = mountConsumer(workspace, "first", firstKey, secondKey);
        MountHandle second = mountConsumer(workspace, "second", firstKey, secondKey);
        awaitActive(first);
        awaitActive(second);

        AtomicInteger attempts = new AtomicInteger();
        runtime.activationCoordinator().providerLeaseRetireFaultProbe = index -> {
            int attempt = attempts.getAndIncrement();
            if (index == 0 && attempt == 0) {
                throw new IllegalStateException(
                        "injected host lease retire fault");
            }
        };

        Settlement settlement = publicRuntime.advanced().transact(transaction -> {
            transaction.revoke(firstProvider);
            transaction.provide(workspace, firstKey, "first replacement");
            transaction.revoke(secondProvider);
            transaction.provide(workspace, secondKey, "second replacement");
            return null;
        }).settlement();
        ExecutionException failure = assertThrows(ExecutionException.class, () ->
                settlement.whenSettled().toCompletableFuture().get(10, TimeUnit.SECONDS));
        assertTrue(failure.getCause() instanceof IllegalStateException,
                () -> String.valueOf(failure.getCause()));
        assertTrue(failure.getCause().getMessage()
                        .contains("injected host lease retire fault"),
                () -> String.valueOf(failure.getCause()));

        PublishedKernelState state = runtime.publishedState();
        state.validateInvariants();
        assertFalse(state.view.registrations.containsKey(firstProvider.registrationId()));
        assertFalse(state.view.registrations.containsKey(secondProvider.registrationId()));
        assertFalse(state.index.providerLeases.containsKey(firstProvider.registrationId()));
        assertFalse(state.index.providerLeases.containsKey(secondProvider.registrationId()));
        assertNoPendingLease(firstProvider.registrationId());
        assertNoPendingLease(secondProvider.registrationId());
        assertEquals(ComponentState.ACTIVE, settledState(first));
        assertEquals(ComponentState.ACTIVE, settledState(second));
        assertEquals(2, attempts.get(), "postcommit fault must not skip retire of any lease");
    }

    @Test
    void contextDisposalRetireFaultFailsContextAndStillDrainsComponents()
            throws Exception {
        CapabilityKey<String> firstKey =
                CapabilityKey.of("context-retire-fault-a", String.class);
        CapabilityKey<String> secondKey =
                CapabilityKey.of("context-retire-fault-b", String.class);
        ContextHandle workspace = publicRuntime.advanced().transact(transaction ->
                transaction.childContext(publicRuntime.root(), "context-fault"))
                .value();
        RegistrationHandle firstProvider = publicRuntime.advanced().transact(
                transaction -> transaction.provide(workspace, firstKey, "first"))
                .value();
        RegistrationHandle secondProvider = publicRuntime.advanced().transact(
                transaction -> transaction.provide(workspace, secondKey, "second"))
                .value();
        MountHandle first = mountConsumer(workspace, "first", firstKey, secondKey);
        MountHandle second = mountConsumer(workspace, "second", firstKey, secondKey);
        awaitActive(first);
        awaitActive(second);

        AtomicInteger attempts = new AtomicInteger();
        runtime.activationCoordinator().providerLeaseRetireFaultProbe = index -> {
            int attempt = attempts.getAndIncrement();
            if (index == 0 && attempt == 0) {
                throw new IllegalStateException(
                        "injected context lease retire fault");
            }
        };

        CompletableFuture<Void> disposal = runtime
                .disposeContext((ContextHandleImpl) workspace)
                .toCompletableFuture();
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> disposal.get(10, TimeUnit.SECONDS));
        assertTrue(failure.getCause() instanceof IllegalStateException,
                () -> String.valueOf(failure.getCause()));
        assertTrue(failure.getCause().getMessage()
                        .contains("injected context lease retire fault"),
                () -> String.valueOf(failure.getCause()));
        assertEquals(ContextState.FAILED, workspace.state());

        PublishedKernelState state = runtime.publishedState();
        state.validateInvariants();
        assertFalse(state.view.registrations.containsKey(firstProvider.registrationId()));
        assertFalse(state.view.registrations.containsKey(secondProvider.registrationId()));
        assertFalse(state.index.providerLeases.containsKey(firstProvider.registrationId()));
        assertFalse(state.index.providerLeases.containsKey(secondProvider.registrationId()));
        assertNoPendingLease(firstProvider.registrationId());
        assertNoPendingLease(secondProvider.registrationId());
        assertEquals(ComponentState.DISPOSED, settledState(first));
        assertEquals(ComponentState.DISPOSED, settledState(second));
        assertEquals(2, attempts.get(), "postcommit fault must not skip retire of any lease");
    }

    private MountHandle mountConsumer(
            ContextHandle context,
            String id,
            CapabilityKey<String> firstKey,
            CapabilityKey<String> secondKey) {
        return publicRuntime.advanced().transact(transaction -> transaction.mount(
                context,
                id,
                MountFactory.of(id,
                        ComponentDescriptor.named(
                                id,
                                CapabilityRequirement.required(firstKey),
                                CapabilityRequirement.required(secondKey)),
                        ignored -> {
                        }))).value();
    }

    private void assertNoPendingLease(String registrationId) {
        PendingOperationsSnapshot pending = runtime.advanced().pendingOperations();
        assertFalse(pending.operations().stream().anyMatch(operation ->
                        operation.kind() == PendingOperationsSnapshot.Kind.PROVIDER_LEASE
                        && operation.targetId().equals(registrationId)),
                pending::render);
    }
    private void awaitActive(MountHandle handle) throws Exception {
        assertEquals(ComponentState.ACTIVE, handle.whenSettled()
                .toCompletableFuture().get(10, TimeUnit.SECONDS));
    }

    private ComponentState settledState(MountHandle handle) throws Exception {
        CompletableFuture<ComponentState> settled = handle.whenSettled()
                .toCompletableFuture();
        ComponentState state = settled.get(10, TimeUnit.SECONDS);
        assertNotNull(state);
        assertFalse(settled.isCompletedExceptionally());
        return state;
    }
}
