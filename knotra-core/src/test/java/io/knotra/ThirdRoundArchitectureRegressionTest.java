package io.knotra;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class ThirdRoundArchitectureRegressionTest {
    static final CapabilityKey<String> A = CapabilityKey.of("a", String.class);
    static final CapabilityKey<Integer> A_NUMBER =
            CapabilityKey.of("a", Integer.class);

    KnotraRuntime runtime = KnotraRuntime.create();

    @AfterEach
    void tearDown() throws Exception {
        try {
            runtime.close();
        } catch (Exception ignored) {
            // Failed cleanup tests intentionally leave a retryable terminal state.
        }
    }

    @Test
    void childCapabilityTypeConflictFailsParentAndCanCloseRuntime() throws Exception {
        AtomicReference<ComponentHandle<NoConfig>> first = new AtomicReference<>();
        AtomicReference<ComponentHandle<NoConfig>> second = new AtomicReference<>();
        var parent = TestKit.mount(runtime, runtime.rootContext(), "parent",
                (context, config) -> {
                    first.set(context.mountChild("first", TestKit.factory("first",
                            new TestKit.Scripted<>(ComponentDescriptor.of("first",
                                    CapabilityRequirement.required(A)),
                                    (childContext, childConfig) -> {})), NoConfig.INSTANCE));
                    second.set(context.mountChild("second", TestKit.factory("second",
                            new TestKit.Scripted<>(ComponentDescriptor.of("second",
                                    CapabilityRequirement.required(A_NUMBER)),
                                    (childContext, childConfig) -> {})), NoConfig.INSTANCE));
                });

        assertEquals(ComponentState.FAILED, TestKit.settle(parent).call());
        assertTrue(runtime.snapshot().components().stream()
                .noneMatch(component -> component.mountId().equals("first")
                        || component.mountId().equals("second")));
        runtime.closeAsync().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @Test
    void waitingProvideDisposeReservationRaceAlwaysSettles() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            for (int round = 0; round < 500; round++) {
                var handle = TestKit.mount(runtime, runtime.rootContext(),
                        "race-" + round, (context, config) -> {},
                        CapabilityRequirement.required(A));
                assertEquals(ComponentState.WAITING, TestKit.settle(handle).call());

                CompletableFuture<MutationResult<RegistrationHandle>> provide =
                        CompletableFuture.supplyAsync(() ->
                                runtime.mutate(mutation ->
                                        mutation.provide(runtime.rootContext(), A, "v")),
                                executor);
                CompletableFuture<ComponentState> dispose =
                        CompletableFuture.supplyAsync(() -> {
                            try {
                                return handle.dispose().toCompletableFuture()
                                        .get(10, TimeUnit.SECONDS);
                            } catch (Exception error) {
                                throw new IllegalStateException(error);
                            }
                        }, executor);

                provide.get(10, TimeUnit.SECONDS);
                assertEquals(ComponentState.DISPOSED, dispose.get(10, TimeUnit.SECONDS));
                RegistrationHandle registration = provide.join().value();
                TestKit.assertCommitted(runtime.mutate(mutation -> {
                    mutation.revoke(registration);
                    return null;
                }));
                assertEquals(ComponentState.DISPOSED, handle.state());
            }
        } finally {
            executor.shutdown();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void sequentialChildAndParallelAsyncCleanupFailuresContinueEarlierEntries() throws Exception {
        List<String> events = new java.util.concurrent.CopyOnWriteArrayList<>();
        var handle = TestKit.mount(runtime, runtime.rootContext(), "cleanup", (context, config) -> {
            context.lifecycle().onClose("root-earlier", () -> events.add("root-earlier"));

            var sequential = context.lifecycle().child("sequential");
            sequential.manageAsync("sequential-later", () -> {
                events.add("sequential-later");
                return CompletableFuture.completedFuture(null);
            });
            sequential.manageAsync("sequential-earlier", () ->
                    CompletableFuture.failedFuture(new IllegalStateException("sequential child failed")));

            var parallel = context.lifecycle().parallelChild("parallel");
            parallel.manageAsync("parallel-bad", () ->
                    CompletableFuture.failedFuture(new IllegalStateException("parallel child failed")));
            parallel.manageAsync("parallel-good", () -> {
                events.add("parallel-good");
                return CompletableFuture.completedFuture(null);
            });

            context.lifecycle().onClose("root-latest", () -> events.add("root-latest"));
        });

        assertEquals(ComponentState.ACTIVE, TestKit.settle(handle).call());
        assertEquals(ComponentState.FAILED, handle.dispose()
                .toCompletableFuture().get(10, TimeUnit.SECONDS));

        assertTrue(events.contains("root-latest"));
        assertTrue(events.contains("parallel-good"));
        assertTrue(events.contains("sequential-later"));
        assertTrue(events.contains("root-earlier"));
        String diagnostic = runtime.snapshot().diagnostics().stream()
                .filter(item -> item.targetId().equals(handle.handleId()))
                .map(RuntimeDiagnostic::message)
                .reduce("", (left, right) -> left + right);
        assertTrue(diagnostic.contains("sequential child failed"), diagnostic);
        assertTrue(diagnostic.contains("parallel child failed"), diagnostic);
    }

    @Test
    void startingConsumerIsStaledByCommittedShadowProviderAndReconciles() throws Exception {
        ContextHandle child = TestKit.child(runtime, runtime.rootContext(), "shadow");
        CountDownLatch consumerStarted = new CountDownLatch(1);
        CompletableFuture<Void> consumerGate = new CompletableFuture<>();
        AtomicReference<String> observed = new AtomicReference<>();

        var consumer = TestKit.mount(runtime, child, "consumer",
                (context, config) -> {
                    consumerStarted.countDown();
                    consumerGate.get();
                    observed.set(context.require(A));
                }, CapabilityRequirement.required(A));
        var rootRegistration = TestKit.provide(runtime, runtime.rootContext(), A, "root");
        assertTrue(consumerStarted.await(10, TimeUnit.SECONDS));
        assertEquals(ComponentState.STARTING, consumer.state());

        var provider = TestKit.mount(runtime, child, "provider",
                (context, config) -> context.provide(A, "child"));
        assertEquals(ComponentState.ACTIVE, TestKit.settle(provider).call());
        String startingActivation = TestKit.component(runtime, consumer).currentActivationId();

        consumerGate.complete(null);
        assertEquals(ComponentState.ACTIVE, TestKit.settle(consumer).call());
        assertNotEquals(startingActivation,
                TestKit.component(runtime, consumer).currentActivationId());
        assertEquals("child", observed.get());

        TestKit.assertCommitted(runtime.mutate(mutation -> {
            mutation.revoke(rootRegistration);
            return null;
        }));
    }

    @Test
    void contextDisposeSettlementWaitsForSubtreeAndFinalizesNamespace() throws Exception {
        ContextHandle child = TestKit.child(runtime, runtime.rootContext(), "workspace");
        CountDownLatch cleanupEntered = new CountDownLatch(1);
        CompletableFuture<Void> cleanupGate = new CompletableFuture<>();
        var handle = TestKit.mount(runtime, child, "component", (context, config) ->
                context.lifecycle().manageAsync("cleanup", () -> {
                    cleanupEntered.countDown();
                    return cleanupGate;
                }));
        assertEquals(ComponentState.ACTIVE, TestKit.settle(handle).call());

        MutationResult<Void> result = runtime.mutate(mutation -> {
            mutation.dispose(child);
            return null;
        });
        TestKit.assertCommitted(result);
        assertTrue(cleanupEntered.await(10, TimeUnit.SECONDS));
        assertFalse(result.settlement().toCompletableFuture().isDone());

        cleanupGate.complete(null);
        result.settlement().toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertEquals(ContextState.DISPOSED, child.state());
        assertTrue(runtime.snapshot().contexts().stream()
                .noneMatch(context -> context.contextId().equals(child.contextId())));
        assertTrue(runtime.snapshot().components().isEmpty());
    }

    @Test
    void invalidContextNamesAndCanonicalPathsAreRejected() {
        List<String> names = List.of("a/b", "a\\b", "a\tb", ".", "..");
        for (String name : names) {
            var result = runtime.mutate(mutation ->
                    mutation.childContext(runtime.rootContext(), name));
            TestKit.assertRejected(result, DiagnosticCode.INVALID_LIFECYCLE_OPERATION);
        }
        assertTrue(runtime.snapshot().contexts().stream()
                .allMatch(context -> context.contextId().equals("ctx-root")));
    }

    @Test
    void repeatedAndNestedContextDisposalsDeduplicateToOneSettlement() throws Exception {
        ContextHandle parent = TestKit.child(runtime, runtime.rootContext(), "parent");
        ContextHandle child = TestKit.child(runtime, parent, "child");
        TestKit.provide(runtime, child, A, "child-value");

        MutationResult<Void> result = runtime.mutate(mutation -> {
            mutation.dispose(child);
            mutation.dispose(child);
            mutation.dispose(parent);
            return null;
        });
        TestKit.assertCommitted(result);
        result.settlement().toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertEquals(ContextState.DISPOSED, parent.state());
        assertEquals(ContextState.DISPOSED, child.state());
        assertTrue(runtime.snapshot().contexts().stream().noneMatch(context ->
                context.contextId().equals(parent.contextId())
                        || context.contextId().equals(child.contextId())));
    }

    @Test
    void multipleReconfigureIntentsKeepNormalizedConfigAndSequentialRevisions() throws Exception {
        AtomicInteger validations = new AtomicInteger();
        List<Object> configs = new java.util.concurrent.CopyOnWriteArrayList<>();
        ComponentFactory<String> factory = new ComponentFactory<>() {
            @Override
            public String factoryId() {
                return "configured";
            }

            @Override
            public Component<String> create() {
                return new TestKit.Scripted<>(
                        ComponentDescriptor.of("configured"),
                        (context, config) -> configs.add(config));
            }

            @Override
            public Optional<ConfigSchema<String>> configSchema() {
                ConfigSchema<String> schema = raw -> {
                    validations.incrementAndGet();
                    return ((String) raw).trim();
                };
                return Optional.of(schema);
            }
        };

        var result = runtime.mutate(mutation -> {
            var handle = mutation.mount(runtime.rootContext(), "configured", factory, " one ");
            mutation.reconfigure(handle, " two ");
            mutation.reconfigure(handle, " three ");
            return handle;
        });
        TestKit.assertCommitted(result);
        assertEquals(ComponentState.ACTIVE, TestKit.settle(result.value()).call());

        assertEquals(3, validations.get());
        assertEquals(3, result.value().configRevision());
        assertEquals(List.of("three"), configs);
    }
}
