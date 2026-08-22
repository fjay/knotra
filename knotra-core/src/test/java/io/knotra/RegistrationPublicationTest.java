package io.knotra;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class RegistrationPublicationTest {
    static final CapabilityKey<String> TEXT = CapabilityKey.of("provided-text", String.class);
    static final CapabilityKey<Integer> TEXT_AS_INTEGER =
            CapabilityKey.of("provided-text", Integer.class);

    KnotraRuntime runtime = KnotraRuntime.create();

    @AfterEach
    void tearDown() throws Exception {
        runtime.close();
    }

    @Test
    void typedRegistrationReplacesInItsOriginalContextAndWaitsForRevokeDrain() throws Exception {
        ContextHandle child = runtime.advanced().childContext(runtime.root(), "child");
        Registration<String> first = runtime.advanced().register(child, TEXT, "one");
        assertEquals(child, first.context());
        assertEquals(TEXT, first.key());
        first.awaitSettled(Duration.ofSeconds(10));
        assertEquals("one", child.view().require(TEXT));

        Registration<String> second = first.replace("two");
        assertNotEquals(first.registrationId(), second.registrationId());
        assertEquals(child, second.context());
        assertEquals("two", child.view().require(TEXT));
        assertThrows(TransactionRejectedException.class, () -> first.replace("three"));
        assertThrows(TransactionRejectedException.class, first::revoke);

        second.awaitSettled(Duration.ofSeconds(10));
        Settlement revoked = second.revoke();
        assertNotNull(revoked.awaitSettled(Duration.ofSeconds(10)));
        assertThrows(TransactionRejectedException.class, second::revoke);
        assertTrue(child.view().find(TEXT).isEmpty());
    }

    @Test
    void publicationIsStableAcrossUpdatesAndUnpublishIsIdempotent() throws Exception {
        PublicationChange<String> published = runtime.publish(TEXT, "one");
        Publication<String> publication = published.publication();
        assertEquals(PublicationOperation.PUBLISH, published.operation());
        assertEquals(PublicationState.PUBLISHED, publication.state());
        published.awaitSettled(Duration.ofSeconds(10));

        PublicationChange<String> second = publication.update("two");
        assertSame(publication, second.publication());
        assertNotEquals(published.generation(), second.generation());
        second.awaitSettled(Duration.ofSeconds(10));
        assertEquals("two", runtime.root().view().require(TEXT));

        PublicationChange<String> removed = publication.unpublish();
        assertEquals(PublicationOperation.UNPUBLISH, removed.operation());
        removed.awaitSettled(Duration.ofSeconds(10));
        assertTrue(runtime.root().view().find(TEXT).isEmpty());
        assertSame(removed, publication.unpublish());
        assertEquals(PublicationState.UNPUBLISHED, publication.state());
        assertThrows(TransactionRejectedException.class, () -> publication.update("three"));
    }

    @Test
    void publicationKeepsChildContextAndShadowsParentUntilUnpublished() throws Exception {
        Registration<String> parent = runtime.advanced().register(TEXT, "parent");
        parent.awaitSettled(Duration.ofSeconds(10));
        ContextHandle child = runtime.advanced().childContext(runtime.root(), "child");
        PublicationChange<String> childChange = runtime.publish(child, TEXT, "child-value");
        childChange.awaitSettled(Duration.ofSeconds(10));

        assertEquals("child-value", child.view().require(TEXT));
        childChange.publication().unpublish().awaitSettled(Duration.ofSeconds(10));
        assertEquals("parent", child.view().require(TEXT));
    }

    @Test
    void publicationIsDisplacedByExternalRevokeAndRejectsLaterUpdate() throws Exception {
        Publication<String> publication = runtime.publish(TEXT, "one").publication();
        String regId = runtime.advanced().snapshot().registrations().stream()
                .filter(r -> r.capability().name().equals(TEXT.name()))
                .findFirst().orElseThrow().registrationId();
        runtime.advanced().revoke(() -> regId).awaitSettled(Duration.ofSeconds(10));
        assertEquals(PublicationState.DISPLACED, publication.state());
        assertThrows(TransactionRejectedException.class, () -> publication.update("two"));
    }



    @Test
    void publicationSettlementReportsFailedMountWithoutExceptionalCompletion() throws Exception {
        var consumer = TestKit.mount(runtime, runtime.root(), "failing-consumer",
                (context, config) -> {
                    if ("two".equals(context.require(TEXT))) {
                        throw new IllegalStateException("cannot bind replacement");
                    }
                },
                CapabilityRequirement.required(TEXT));

        PublicationChange<String> first = runtime.publish(TEXT, "one");
        first.awaitSettled(Duration.ofSeconds(10));
        assertEquals(ComponentState.ACTIVE, TestKit.settle(consumer).call());

        PublicationChange<String> second = first.publication().update("two");
        SettlementReport report = second.awaitSettled(Duration.ofSeconds(10));
        assertTrue(second.whenSettled().toCompletableFuture().isDone());
        assertTrue(report.hasFailedMounts());
        assertFalse(report.allAffectedActive());
        assertEquals(ComponentState.FAILED, consumer.state());
        assertTrue(report.failedMounts().stream()
                .anyMatch(outcome -> outcome.handleId().equals(consumer.handleId())));
        assertEquals("two", runtime.root().view().require(TEXT));
    }


    @Test
    void stagedRegistrationIsTypedAndDoesNotImpersonateACommittedRegistration() throws Exception {
        TransactionReceipt<StagedRegistration<String>> receipt =
                runtime.advanced().transact(transaction ->
                        transaction.provide(runtime.root(), TEXT, "expert"));
        StagedRegistration<String> staged = receipt.value();
        assertEquals(TEXT, staged.key());
        assertEquals(runtime.root(), staged.context());
        assertFalse(staged instanceof Registration);
        receipt.awaitSettled(Duration.ofSeconds(10));
        assertEquals("expert", runtime.root().view().require(TEXT));
        runtime.advanced().revoke(staged).awaitSettled(Duration.ofSeconds(10));
        assertTrue(runtime.root().view().find(TEXT).isEmpty());
    }

    @Test
    void rejectedReplacementKeepsCurrentRegistrationFresh() throws Exception {
        Registration<String> current = runtime.advanced().register(TEXT, "one");
        long generation = runtime.advanced().snapshot().generation();
        TestKit.assertRejected(() -> runtime.advanced().transact(transaction -> {
            transaction.revoke(current);
            return transaction.provide(runtime.root(), TEXT_AS_INTEGER, 2);
        }), DiagnosticCode.CAPABILITY_TYPE_CONFLICT);

        assertEquals("one", runtime.root().view().require(TEXT));
        assertEquals(generation, runtime.advanced().snapshot().generation());
        Registration<String> replacement = current.replace("two");
        assertEquals("two", runtime.root().view().require(TEXT));
        assertThrows(TransactionRejectedException.class, current::revoke);
        replacement.revoke().awaitSettled(Duration.ofSeconds(10));
    }

    @Test
    void typedHandleCannotBeRevokedThroughAForeignRuntime() throws Exception {
        KnotraRuntime other = KnotraRuntime.create();
        try {
            Registration<String> local = runtime.advanced().register(TEXT, "one");
            assertThrows(IllegalArgumentException.class,
                    () -> other.advanced().revoke(local));
            TestKit.assertRejected(() -> other.advanced().transact(transaction -> {
                transaction.revoke(local);
                return null;
            }), DiagnosticCode.INVALID_LIFECYCLE_OPERATION);
            assertEquals("one", runtime.root().view().require(TEXT));
            local.revoke().awaitSettled(Duration.ofSeconds(10));
            assertTrue(runtime.root().view().find(TEXT).isEmpty());
        } finally {
            other.close();
        }
    }

    @Test
    void concurrentRegistrationOperationsHaveExactlyOneWinner() throws Exception {
        int lanes = 4;
        int rounds = 40;
        ExecutorService executor = Executors.newFixedThreadPool(lanes);
        try {
            for (int round = 0; round < rounds; round++) {
                CapabilityKey<String> key =
                        CapabilityKey.of("replace-race-" + round, String.class);
                Registration<String> first = runtime.advanced().register(key, "first");
                first.awaitSettled(Duration.ofSeconds(10));

                CyclicBarrier barrier = new CyclicBarrier(lanes);
                AtomicReference<String> winner = new AtomicReference<>("revoked");
                AtomicReference<Registration<String>> winnerHandle = new AtomicReference<>();
                List<Future<Object>> outcomes = new ArrayList<>();
                for (int lane = 0; lane < lanes; lane++) {
                    int laneId = lane;
                    outcomes.add(executor.submit(() -> {
                        barrier.await(10, TimeUnit.SECONDS);
                        if (laneId == 0) {
                            first.revoke().awaitSettled(Duration.ofSeconds(10));
                            return null;
                        }
                        Registration<String> replacement = first.replace("lane-" + laneId);
                        winner.compareAndSet("revoked", "lane-" + laneId);
                        winnerHandle.compareAndSet(null, replacement);
                        return replacement;
                    }));
                }

                int successes = 0;
                int rejections = 0;
                for (Future<Object> outcome : outcomes) {
                    try {
                        outcome.get(10, TimeUnit.SECONDS);
                        successes++;
                    } catch (java.util.concurrent.ExecutionException error) {
                        assertInstanceOf(TransactionRejectedException.class, error.getCause());
                        rejections++;
                    }
                }
                assertEquals(1, successes, "round " + round);
                assertEquals(lanes - 1, rejections, "round " + round);

                if ("revoked".equals(winner.get())) {
                    assertTrue(runtime.root().view().find(key).isEmpty());
                } else {
                    assertEquals(winner.get(), runtime.root().view().require(key));
                    assertEquals(winnerHandle.get().registrationId(),
                            runtime.advanced().snapshot().registrations().stream()
                                    .filter(registration ->
                                            registration.capability().name().equals(key.name()))
                                    .findFirst()
                                    .orElseThrow()
                                    .registrationId());
                }
            }
        } finally {
            executor.shutdown();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void concurrentPublicationUpdatesAreLinearizedAndFailedUpdateKeepsCurrent() throws Exception {
        AtomicInteger failures = new AtomicInteger();
        RegistrationPublicationTest staticTest = this;
        for (int round = 0; round < 20; round++) {
            CapabilityKey<String> key = CapabilityKey.of("publication-race-" + round, String.class);
            Publication<String> publication = runtime.publish(key, "initial").publication();
            int lanes = 4;
            ExecutorService executor = Executors.newFixedThreadPool(lanes);
            CyclicBarrier barrier = new CyclicBarrier(lanes);
            List<Future<Object>> outcomes = new ArrayList<>();
            try {
                for (int lane = 0; lane < lanes; lane++) {
                    int laneId = lane;
                    outcomes.add(executor.submit(() -> {
                        barrier.await(5, TimeUnit.SECONDS);
                        try {
                            return publication.update("lane-" + laneId);
                        } finally {
                            if (laneId == 3) {
                                failures.incrementAndGet();
                            }
                        }
                    }));
                }
                int changes = 0;
                for (Future<Object> outcome : outcomes) {
                    try {
                        ((PublicationChange<String>) outcome.get(5, TimeUnit.SECONDS))
                                .awaitSettled(Duration.ofSeconds(10));
                        changes++;
                    } catch (java.util.concurrent.ExecutionException ignored) {
                        // Publication-level races are expected to be rejected.
                    }
                }
                assertEquals(lanes, changes);
                assertEquals(PublicationState.PUBLISHED, publication.state());
            } finally {
                executor.shutdown();
                assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void externalReplacementDisplacesPublicationTerminally() throws Exception {
        Publication<String> publication = runtime.publish(TEXT, "one").publication();
        String regId = runtime.advanced().snapshot().registrations().stream()
                .filter(r -> r.capability().name().equals(TEXT.name()))
                .findFirst().orElseThrow().registrationId();
        TransactionReceipt<StagedRegistration<String>> replacement = runtime.advanced().transact(transaction -> {
            transaction.revoke(() -> regId);
            return transaction.provide(runtime.root(), TEXT, "two");
        });
        replacement.awaitSettled(Duration.ofSeconds(10));

        assertEquals(PublicationState.DISPLACED, publication.state());
        assertThrows(TransactionRejectedException.class, () -> publication.update("three"));
        assertThrows(TransactionRejectedException.class, publication::unpublish);
        runtime.advanced().revoke(replacement.value()).awaitSettled(Duration.ofSeconds(10));
    }


    @Test
    void childContextDisposeDisplacesPublicationWithoutRebuild() throws Exception {
        ContextHandle child = runtime.advanced().childContext(runtime.root(), "dispose-publication");
        PublicationChange<String> change = runtime.publish(child, TEXT, "child");
        Publication<String> publication = change.publication();
        change.awaitSettled(Duration.ofSeconds(10));

        runtime.advanced().transact(transaction -> {
            transaction.dispose(child);
            return null;
        }).awaitSettled(Duration.ofSeconds(10));

        assertEquals(PublicationState.DISPLACED, publication.state());
        assertThrows(TransactionRejectedException.class, () -> publication.update("next"));
        assertThrows(TransactionRejectedException.class, publication::unpublish);
    }

    @Test
    void runtimeCloseDisplacesPublicationWithoutRebuild() throws Exception {
        KnotraRuntime closed = KnotraRuntime.create();
        Publication<String> publication;
        try {
            PublicationChange<String> change = closed.publish(TEXT, "value");
            publication = change.publication();
            change.awaitSettled(Duration.ofSeconds(10));
        } finally {
            closed.close();
        }

        assertEquals(PublicationState.DISPLACED, publication.state());
        assertThrows(TransactionRejectedException.class, () -> publication.update("next"));
        assertThrows(TransactionRejectedException.class, publication::unpublish);
    }

    @Test
    void concurrentUpdateAndUnpublishFollowOneLinearizationOrder() throws Exception {
        for (int round = 0; round < 40; round++) {
            CapabilityKey<String> key =
                    CapabilityKey.of("publication-unpublish-race-" + round, String.class);
            Publication<String> publication = runtime.publish(key, "initial").publication();
            AtomicReference<PublicationChange<String>> updateChange = new AtomicReference<>();
            AtomicReference<PublicationChange<String>> unpublishChange = new AtomicReference<>();
            ExecutorService executor = Executors.newFixedThreadPool(2);
            CyclicBarrier barrier = new CyclicBarrier(2);
            try {
                Future<Boolean> updated = executor.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        updateChange.set(publication.update("replacement"));
                        return true;
                    } catch (TransactionRejectedException expected) {
                        return false;
                    }
                });
                Future<Boolean> unpublished = executor.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    unpublishChange.set(publication.unpublish());
                    return true;
                });

                boolean updateSucceeded = updated.get(10, TimeUnit.SECONDS);
                assertTrue(unpublished.get(10, TimeUnit.SECONDS));
                assertEquals(updateSucceeded, updateChange.get() != null);
                if (updateSucceeded) {
                    updateChange.get().awaitSettled(Duration.ofSeconds(10));
                    assertTrue(updateChange.get().generation()
                            < unpublishChange.get().generation());
                }
                unpublishChange.get().awaitSettled(Duration.ofSeconds(10));

                assertEquals(PublicationState.UNPUBLISHED, publication.state());
            } finally {
                executor.shutdown();
                assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void capabilityClassShortcutUsesBinaryName() {
        assertEquals(String.class.getName(), runtime.publish(String.class, "one")
                .publication()
                .key()
                .name());
    }
}
