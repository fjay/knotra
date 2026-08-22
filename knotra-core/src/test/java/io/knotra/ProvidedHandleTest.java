package io.knotra;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class ProvidedHandleTest {
    static final CapabilityKey<String> TEXT = CapabilityKey.of("provided-text", String.class);
    static final CapabilityKey<Integer> TEXT_AS_INTEGER =
            CapabilityKey.of("provided-text", Integer.class);

    KnotraRuntime runtime = KnotraRuntime.create();

    @AfterEach
    void tearDown() throws Exception {
        runtime.close();
    }

    @Test
    void typedProvideReplaceAndRevokeUseNewRegistrationIdentities() throws Exception {
        Provided<String> first = runtime.provide(TEXT, "one");
        assertEquals(TEXT, first.key());
        assertNotNull(first.registrationId());
        first.whenSettled().toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertEquals("one", runtime.root().view().require(TEXT));

        Provided<String> second = first.replace("two");
        assertNotEquals(first.registrationId(), second.registrationId());
        assertEquals(TEXT, second.key());
        assertEquals("two", runtime.root().view().require(TEXT));
        assertThrows(TransactionRejectedException.class, () -> first.replace("three"));
        assertThrows(TransactionRejectedException.class, first::revoke);

        second.whenSettled().toCompletableFuture().get(10, TimeUnit.SECONDS);
        second.revoke();
        assertThrows(TransactionRejectedException.class, second::revoke);
        assertTrue(runtime.root().view().find(TEXT).isEmpty());
    }

    @Test
    void replacementCommitsOneGenerationAndDoesNotWaitForDownstreamDrain() throws Exception {
        CompletableFuture<Void> oldConsumerDrain = new CompletableFuture<>();
        AtomicReference<String> observed = new AtomicReference<>();
        var consumer = TestKit.mount(runtime, runtime.root(), "consumer",
                (context, config) -> {
                    observed.set(context.require(TEXT));
                    context.lifecycle().onCloseAsync("consumer", () -> oldConsumerDrain);
                },
                CapabilityRequirement.required(TEXT));

        Provided<String> first = runtime.provide(TEXT, "one");
        assertEquals(ComponentState.ACTIVE, TestKit.settle(consumer).call());
        assertEquals("one", observed.get());
        long generation = runtime.snapshot().generation();

        Provided<String> second;
        try {
            second = first.replace("two");
            assertEquals("two", runtime.root().view().require(TEXT));
            assertEquals(generation + 1, runtime.snapshot().generation());
            assertFalse(second.whenSettled().toCompletableFuture().isDone(),
                    "replace must not synchronously wait for downstream reactivation");
        } finally {
            oldConsumerDrain.complete(null);
        }

        second.whenSettled().toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertEquals(ComponentState.ACTIVE, TestKit.settle(consumer).call());
        assertEquals("two", observed.get());
    }

    @Test
    void rejectedReplacementKeepsTheCurrentTypedHandleFresh() throws Exception {
        Provided<String> current = runtime.provide(TEXT, "one");
        long generation = runtime.snapshot().generation();

        TestKit.assertRejected(() -> runtime.transact(transaction -> {
            transaction.revoke(current);
            return transaction.provide(runtime.root(), TEXT_AS_INTEGER, 2);
        }), DiagnosticCode.CAPABILITY_TYPE_CONFLICT);

        assertEquals("one", runtime.root().view().require(TEXT));
        assertEquals(generation, runtime.snapshot().generation());

        Provided<String> replacement = current.replace("two");
        assertEquals("two", runtime.root().view().require(TEXT));
        assertThrows(TransactionRejectedException.class, current::revoke);
        replacement.revoke();
    }

    @Test
    void typedHandleCannotBeRevokedThroughAForeignRuntime() throws Exception {
        KnotraRuntime other = KnotraRuntime.create();
        try {
            Provided<String> local = runtime.provide(TEXT, "one");

            TestKit.assertRejected(() -> other.revoke(local),
                    DiagnosticCode.INVALID_LIFECYCLE_OPERATION);
            TestKit.assertRejected(() -> other.transact(transaction -> {
                transaction.revoke(local);
                return null;
            }), DiagnosticCode.INVALID_LIFECYCLE_OPERATION);

            assertEquals("one", runtime.root().view().require(TEXT));
            local.revoke();
            assertTrue(runtime.root().view().find(TEXT).isEmpty());
        } finally {
            other.close();
        }
    }

    @Test
    void expertTransactionStillReturnsRegistrationHandle() throws Exception {
        RegistrationHandle registration = runtime.transact(transaction ->
                transaction.provide(runtime.root(), TEXT, "expert")).value();

        assertNotNull(registration.registrationId());
        assertEquals("expert", runtime.root().view().require(TEXT));
        runtime.revoke(registration);
        assertTrue(runtime.root().view().find(TEXT).isEmpty());
    }
}
