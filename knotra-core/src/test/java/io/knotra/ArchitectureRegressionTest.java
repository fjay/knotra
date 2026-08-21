package io.knotra;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

final class ArchitectureRegressionTest {
    static final CapabilityKey<String> A = CapabilityKey.of("a", String.class);
    static final CapabilityKey<String> B = CapabilityKey.of("b", String.class);
    static final CapabilityKey<String> UNRELATED = CapabilityKey.of("unrelated", String.class);

    KnotraRuntime runtime = KnotraRuntime.create();

    @AfterEach
    void tearDown() throws Exception {
        try {
            runtime.close();
        } catch (Exception ignored) {
            // tests with intentionally failed cleanup retry through handles
        }
    }

    private ComponentHandle<NoConfig> mount(String id, TestKit.Start<NoConfig> start,
                                             CapabilityRequirement... requirements) {
        return TestKit.mount(runtime, runtime.rootContext(), id, id, start, requirements);
    }

    @Test
    void failedReconfigureTransactionDoesNotTouchExecutableState() throws Exception {
        ComponentFactory<EquivalentConfig> factory = TestKit.factory(
                "configured",
                new TestKit.Scripted<>(
                        ComponentDescriptor.of("configured"),
                        (context, config) -> {}));
        var handle = runtime.mutate(mutation -> mutation.mount(
                runtime.rootContext(), "configured", factory, new EquivalentConfig("one"))).value();
        assertEquals(ComponentState.ACTIVE, TestKit.settle(handle).call());
        String activation = TestKit.component(runtime, handle).currentActivationId();
        long generation = runtime.snapshot().generation();

        var rejected = runtime.mutate(mutation -> {
            mutation.reconfigure(handle, new EquivalentConfig("two"));
            mutation.mount(runtime.rootContext(), "configured",
                    TestKit.factory("duplicate", new TestKit.Scripted<>(
                            ComponentDescriptor.of("duplicate"), (context, config) -> {})),
                    NoConfig.INSTANCE);
            return null;
        });
        TestKit.assertRejected(rejected, DiagnosticCode.INVALID_MOUNT_ID);

        assertEquals(generation, runtime.snapshot().generation());
        assertEquals(1, handle.configRevision());
        assertEquals(ComponentState.ACTIVE, handle.state());
        assertEquals(activation, TestKit.component(runtime, handle).currentActivationId());
    }

    @Test
    void sameTransactionCanUseAndRevokeProvisionalRegistrationsAndContexts() {
        ContextHandle[] workspace = new ContextHandle[1];
        var result = runtime.mutate(mutation -> {
            ContextHandle child = mutation.childContext(runtime.rootContext(), "workspace");
            workspace[0] = child;
            var first = mutation.provide(child, A, "first");
            mutation.revoke(first);
            var second = mutation.provide(child, A, "second");
            mutation.mount(child, "component", TestKit.factory("component",
                    new TestKit.Scripted<>(ComponentDescriptor.of("component",
                            CapabilityRequirement.required(A)), (context, config) -> {})),
                    NoConfig.INSTANCE);
            return second;
        });
        TestKit.assertCommitted(result);
        assertEquals("second", workspace[0].context().require(A));
        assertEquals(1, runtime.snapshot().registrations().size());
    }

    @Test
    void mountThenDisposeDoesNotCreateExecutableComponent() throws Exception {
        AtomicInteger creates = new AtomicInteger();
        ComponentFactory<NoConfig> factory = new ComponentFactory<>() {
            @Override public String factoryId() { return "temporary"; }

            @Override public Component<NoConfig> create() {
                creates.incrementAndGet();
                return new TestKit.Scripted<>(ComponentDescriptor.of("temporary"),
                        (context, config) -> {});
            }
        };
        var result = runtime.mutate(mutation -> {
            var handle = mutation.mount(runtime.rootContext(), "temporary", factory, NoConfig.INSTANCE);
            mutation.dispose(handle);
            return handle;
        });
        TestKit.assertCommitted(result);
        assertEquals(1, creates.get());
        assertEquals(ComponentState.DISPOSED, result.value().state());
        assertTrue(runtime.snapshot().components().isEmpty());

        var replacement = mount("temporary", (context, config) -> {});
        assertEquals(ComponentState.ACTIVE, TestKit.settle(replacement).call());
    }

    @Test
    void waitingComponentDisposeRemovesExecutableReferences() throws Exception {
        var handle = mount("waiting", (context, config) -> {},
                CapabilityRequirement.required(A));
        assertEquals(ComponentState.WAITING, TestKit.settle(handle).call());
        assertEquals(ComponentState.DISPOSED, handle.dispose()
                .toCompletableFuture().get(10, TimeUnit.SECONDS));
        assertTrue(runtime.snapshot().components().isEmpty());

        var replacement = mount("waiting", (context, config) -> {},
                CapabilityRequirement.required(A));
        assertEquals(ComponentState.WAITING, TestKit.settle(replacement).call());
    }

    @Test
    void equivalentReconfigurationIsANoOpEvenWhenEqualsBlocks() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CompletableFuture<Void> gate = new CompletableFuture<>();
        ComponentFactory<BlockingEqualConfig> factory = TestKit.factory(
                "configured",
                new TestKit.Scripted<>(ComponentDescriptor.of("configured"),
                        (context, config) -> {}));
        var handle = runtime.mutate(mutation -> mutation.mount(
                runtime.rootContext(), "configured", factory,
                new BlockingEqualConfig("same", null, null))).value();
        assertEquals(ComponentState.ACTIVE, TestKit.settle(handle).call());
        String activation = TestKit.component(runtime, handle).currentActivationId();

        ExecutorService worker = Executors.newSingleThreadExecutor();
        var reconfiguredFuture = worker.submit(() -> handle.reconfigure(
                new BlockingEqualConfig("same", entered, gate)));
        assertTrue(entered.await(10, TimeUnit.SECONDS));
        TestKit.provide(runtime, runtime.rootContext(), UNRELATED, "independent");
        long generation = runtime.snapshot().generation();
        gate.complete(null);
        var reconfigured = reconfiguredFuture.get(10, TimeUnit.SECONDS);
        worker.shutdown();
        assertTrue(worker.awaitTermination(10, TimeUnit.SECONDS));

        assertEquals(ComponentState.ACTIVE, reconfigured.toCompletableFuture()
                .get(10, TimeUnit.SECONDS));
        assertEquals(generation, runtime.snapshot().generation());
        assertEquals(1, handle.configRevision());
        assertEquals(activation, TestKit.component(runtime, handle).currentActivationId());
    }

    @Test
    void staleDecisionHasPriorityOverStartError() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CompletableFuture<Void> gate = new CompletableFuture<>();
        AtomicBoolean originalAttempt = new AtomicBoolean(true);
        AtomicInteger starts = new AtomicInteger();
        var handle = mount("consumer", (context, config) -> {
            starts.incrementAndGet();
            started.countDown();
            gate.get();
            if (originalAttempt.getAndSet(false)) {
                throw new IllegalStateException("user failure after stale");
            }
        }, CapabilityRequirement.required(A));
        var first = TestKit.provide(runtime, runtime.rootContext(), A, "one");
        assertTrue(started.await(10, TimeUnit.SECONDS));
        TestKit.assertCommitted(runtime.mutate(mutation -> {
            mutation.revoke(first);
            return null;
        }));
        gate.complete(null);

        assertEquals(ComponentState.WAITING, TestKit.settle(handle).call());
        assertTrue(runtime.snapshot().diagnostics().stream().noneMatch(diagnostic ->
                diagnostic.code() == DiagnosticCode.ACTIVATION_FAILED
                        && diagnostic.targetId().equals(handle.handleId())));

        TestKit.provide(runtime, runtime.rootContext(), A, "two");
        assertEquals(ComponentState.ACTIVE, TestKit.settle(handle).call());
        assertEquals(2, starts.get());
    }

    @Test
    void nestedChildCleanupFailureMarksHandleFailedAndIsRetryable() throws Exception {
        AtomicBoolean failOnce = new AtomicBoolean(true);
        var handle = mount("nested", (context, config) -> {
            context.lifecycle().child("nested")
                    .onClose("bad", () -> {
                        if (failOnce.getAndSet(false)) {
                            throw new IllegalStateException("nested cleanup");
                        }
                    });
        });
        assertEquals(ComponentState.ACTIVE, TestKit.settle(handle).call());
        assertEquals(ComponentState.FAILED, handle.dispose()
                .toCompletableFuture().get(10, TimeUnit.SECONDS));
        assertEquals(ComponentState.DISPOSED, handle.retry()
                .toCompletableFuture().get(10, TimeUnit.SECONDS));
    }

    @Test
    void asyncCleanupFailureDoesNotShortCircuitSiblingEntry() throws Exception {
        List<String> events = new java.util.concurrent.CopyOnWriteArrayList<>();
        var handle = mount("async", (context, config) -> {
            var group = context.lifecycle().parallelChild("group");
            group.manageAsync("bad", () -> CompletableFuture.failedFuture(
                    new IllegalStateException("bad")));
            group.manageAsync("good", () -> {
                events.add("good");
                return CompletableFuture.completedFuture(null);
            });
        });
        assertEquals(ComponentState.ACTIVE, TestKit.settle(handle).call());
        assertEquals(ComponentState.FAILED, handle.dispose()
                .toCompletableFuture().get(10, TimeUnit.SECONDS));
        assertEquals(List.of("good"), events);
    }

    @Test
    void dependentBeforeProviderOrderSurvivesThousandsOfRounds() throws Exception {
        int rounds = 1000;
        for (int round = 0; round < rounds; round++) {
            List<String> order = new java.util.concurrent.CopyOnWriteArrayList<>();
            var provider = TestKit.mount(runtime, runtime.rootContext(),
                    "p-" + round, (context, config) -> {
                        context.provide(A, "a");
                        context.lifecycle().onClose("p", () -> order.add("p"));
                    });
            var consumer = TestKit.mount(runtime, runtime.rootContext(),
                    "c-" + round, (context, config) -> {
                        context.require(A);
                        context.lifecycle().onClose("c", () -> order.add("c"));
                    }, CapabilityRequirement.required(A));
            assertEquals(ComponentState.ACTIVE, TestKit.settle(provider).call());
            assertEquals(ComponentState.ACTIVE, TestKit.settle(consumer).call());
            int currentRound = round;
            assertEquals(ComponentState.DISPOSED, provider.dispose()
                    .toCompletableFuture().get(10, TimeUnit.SECONDS),
                    () -> "provider round " + currentRound + ": " + runtime.snapshot());
            assertEquals(List.of("c", "p"), order);
            assertEquals(ComponentState.WAITING, consumer.state());
            var replacement = TestKit.provide(
                    runtime, runtime.rootContext(), A, "host-" + round);
            assertEquals(ComponentState.ACTIVE, TestKit.settle(consumer).call());
            assertEquals(ComponentState.DISPOSED, consumer.dispose()
                    .toCompletableFuture().get(10, TimeUnit.SECONDS),
                    () -> "consumer round " + currentRound + ": " + runtime.snapshot());
            var revoke = runtime.mutate(mutation -> {
                mutation.revoke(replacement);
                return null;
            });
            TestKit.assertCommitted(revoke);
        }
    }

    @Test
    void snapshotIsSortedAndCarriesProvenanceRequirementsAndPaths() throws Exception {
        ContextHandle child = TestKit.child(runtime, runtime.rootContext(), "workspace");
        var origin = ComponentOrigin.artifact("artifact-a", "1.2.3", "test artifact");
        AtomicReference<ComponentHandle<NoConfig>> mountedChild =
                new AtomicReference<>();
        var parent = runtime.mutate(mutation -> mutation.mount(
                runtime.rootContext(),
                "parent",
                TestKit.factory("parent", new TestKit.Scripted<>(
                        ComponentDescriptor.of("parent", CapabilityRequirement.required(A)),
                        (context, config) -> {
                            context.provide(B, "b");
                            mountedChild.set(context.mountChild("child",
                                    TestKit.factory("child",
                                            new TestKit.Scripted<>(ComponentDescriptor.of("child"),
                                                    (childContext, childConfig) -> {})),
                                    NoConfig.INSTANCE));
                        })),
                NoConfig.INSTANCE,
                new MountOptions(origin, java.util.Map.of("source", "test")))).value();
        TestKit.provide(runtime, runtime.rootContext(), A, "a");
        assertEquals(ComponentState.ACTIVE, TestKit.settle(parent).call());
        assertEquals(ComponentState.ACTIVE, TestKit.settle(mountedChild.get()).call());
        var childHandle = runtime.snapshot().components().stream()
                .filter(component -> component.mountId().equals("child"))
                .findFirst().orElseThrow();
        assertEquals(ComponentState.ACTIVE, childHandle.state());

        RuntimeSnapshot snapshot = runtime.snapshot();
        assertEquals(List.of("a", "b"), snapshot.registrations().stream()
                .map(registration -> registration.capability().name())
                .filter(name -> name.equals("a") || name.equals("b"))
                .toList());
        assertTrue(IntStream.range(0, snapshot.components().size() - 1)
                .allMatch(index -> snapshot.components().get(index).handleId()
                        .compareTo(snapshot.components().get(index + 1).handleId()) <= 0));
        assertEquals(origin, TestKit.component(runtime, parent).origin());
        assertEquals(origin, childHandle.origin());
        assertEquals("test", TestKit.component(runtime, parent).mountOptions().metadata("source"));
        assertEquals(parent.handleId(), childHandle.parentHandleId());
        assertEquals("/root", runtime.rootContext().contextInfo().canonicalPath());
        assertEquals("/root/workspace", child.contextInfo().canonicalPath());
        assertEquals(CapabilityRequirement.Mode.REQUIRED,
                TestKit.component(runtime, parent).requirements().getFirst().mode());
    }

    @Test
    void disposedChildContextLeavesNamespaceAndSameNameCanBeRebuilt() throws Exception {
        ContextHandle first = TestKit.child(runtime, runtime.rootContext(), "workspace");
        first.close();
        assertEquals(ContextState.DISPOSED, first.state());
        assertTrue(runtime.snapshot().contexts().stream()
                .noneMatch(context -> context.contextId().equals(first.contextId())));

        ContextHandle second = TestKit.child(runtime, runtime.rootContext(), "workspace");
        assertNotEquals(first.contextId(), second.contextId());
        assertEquals("/root/workspace", second.contextInfo().canonicalPath());
    }

    @Test
    void contextCanBeDisposedInsideStructuralTransaction() throws Exception {
        ContextHandle child = TestKit.child(runtime, runtime.rootContext(), "temporary");
        TestKit.provide(runtime, child, A, "value");
        var result = runtime.mutate(mutation -> {
            mutation.mount(child, "component", TestKit.factory("component",
                    new TestKit.Scripted<>(ComponentDescriptor.of("component"), (c, config) -> {})),
                    NoConfig.INSTANCE);
            mutation.dispose(child);
            return null;
        });
        TestKit.assertCommitted(result);
        result.settlement().toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertEquals(ContextState.DISPOSED, child.state());
        assertTrue(runtime.context().find(A).isEmpty());
        assertTrue(runtime.snapshot().components().isEmpty());
    }

    @Test
    void concurrentChildPlansCommitOnlyOneAndFailCollisionParent() throws Exception {
        CountDownLatch bothStaged = new CountDownLatch(2);
        CompletableFuture<Void> gate = new CompletableFuture<>();
        AtomicInteger childStarts = new AtomicInteger();
        List<ComponentHandle<NoConfig>> staged =
                new java.util.concurrent.CopyOnWriteArrayList<>();
        ComponentFactory<NoConfig> childFactory = TestKit.factory("child",
                new TestKit.Scripted<>(ComponentDescriptor.of("child"),
                        (context, config) -> childStarts.incrementAndGet()));
        TestKit.Start<NoConfig> parentStart = (context, config) -> {
            staged.add(context.mountChild("collision", childFactory, NoConfig.INSTANCE));
            bothStaged.countDown();
            gate.get();
        };
        var first = mount("first", parentStart);
        var second = mount("second", parentStart);
        assertTrue(bothStaged.await(10, TimeUnit.SECONDS));
        gate.complete(null);

        var firstState = TestKit.settle(first).call();
        var secondState = TestKit.settle(second).call();
        assertTrue(firstState == ComponentState.ACTIVE || secondState == ComponentState.ACTIVE);
        assertTrue(firstState == ComponentState.FAILED || secondState == ComponentState.FAILED);
        assertEquals(1, runtime.snapshot().components().stream()
                .filter(component -> component.mountId().equals("collision"))
                .count());
        assertEquals(1, childStarts.get());
        assertEquals(1, staged.stream().filter(handle ->
                handle.state() == ComponentState.ACTIVE).count());
    }

    @Test
    void bindingCycleRetriesOnlyWhenRelevantTopologyChanges() throws Exception {
        var rootA = TestKit.provide(runtime, runtime.rootContext(), A, "root-a");
        ContextHandle child = TestKit.child(runtime, runtime.rootContext(), "cycle");
        var provider = TestKit.mount(runtime, child, "provider",
                (context, config) -> {
                    context.require(A);
                    context.provide(B, "provider-b");
                }, CapabilityRequirement.required(A));
        assertEquals(ComponentState.ACTIVE, TestKit.settle(provider).call());
        AtomicInteger starts = new AtomicInteger();
        var cyclic = TestKit.mount(runtime, child, "cyclic",
                (context, config) -> {
                    starts.incrementAndGet();
                    context.require(B);
                    context.provide(A, "cyclic-a");
                }, CapabilityRequirement.required(B));
        assertEquals(ComponentState.WAITING, TestKit.settle(cyclic).call());
        assertEquals(1, starts.get());

        TestKit.provide(runtime, runtime.rootContext(), UNRELATED, "unrelated");
        assertEquals(ComponentState.WAITING, TestKit.settle(cyclic).call());
        assertEquals(1, starts.get());

        TestKit.assertCommitted(runtime.mutate(mutation -> {
            mutation.revoke(rootA);
            return null;
        }));
        assertEquals(ComponentState.WAITING, TestKit.settle(provider).call());
        TestKit.provide(runtime, runtime.rootContext(), A, "root-a-new");
        assertEquals(ComponentState.ACTIVE, TestKit.settle(provider).call());
        assertEquals(ComponentState.WAITING, TestKit.settle(cyclic).call());
        assertEquals(2, starts.get());
    }

    @Test
    void startFailureAndCleanupFailureAreDistinctDiagnostics() throws Exception {
        AtomicBoolean failCleanup = new AtomicBoolean(true);
        var handle = mount("both", (context, config) -> {
            context.lifecycle().onClose("cleanup", () -> {
                if (failCleanup.getAndSet(false)) {
                    throw new IllegalStateException("cleanup problem");
                }
            });
            throw new IllegalStateException("start problem");
        });
        assertEquals(ComponentState.FAILED, TestKit.settle(handle).call());
        var diagnostics = runtime.snapshot().diagnostics().stream()
                .filter(diagnostic -> diagnostic.targetId().equals(handle.handleId()))
                .toList();
        assertTrue(diagnostics.stream().anyMatch(diagnostic ->
                diagnostic.code() == DiagnosticCode.ACTIVATION_FAILED
                        && diagnostic.message().contains("start problem")));
        assertTrue(diagnostics.stream().anyMatch(diagnostic ->
                diagnostic.code() == DiagnosticCode.CLEANUP_FAILED
                        && diagnostic.message().contains("cleanup problem")),
                diagnostics::toString);
    }

    @Test
    void childMountOptionsOverloadPreservesExplicitProvenance() throws Exception {
        var inherited = ComponentOrigin.artifact("parent-artifact", "2.0", "parent");
        var explicit = ComponentOrigin.artifact("child-artifact", "3.0", "child");
        AtomicReference<ComponentHandle<NoConfig>> child = new AtomicReference<>();
        var parent = runtime.mutate(mutation -> mutation.mount(
                runtime.rootContext(),
                "parent",
                TestKit.factory("parent", new TestKit.Scripted<>(
                        ComponentDescriptor.of("parent"),
                        (context, config) -> child.set(context.mountChild(
                                "child",
                                TestKit.factory("child", new TestKit.Scripted<>(
                                        ComponentDescriptor.of("child"),
                                        (childContext, childConfig) -> {})),
                                NoConfig.INSTANCE,
                                new MountOptions(explicit))))),
                NoConfig.INSTANCE,
                new MountOptions(inherited))).value();
        assertEquals(ComponentState.ACTIVE, TestKit.settle(parent).call());
        assertEquals(ComponentState.ACTIVE, TestKit.settle(child.get()).call());
        assertEquals(explicit, TestKit.component(runtime, child.get()).origin());
        assertEquals(inherited, TestKit.component(runtime, parent).origin());
    }

    @Test
    void deterministicLifoUsesOneGlobalManagedNodeSequence() throws Exception {
        List<String> events = new ArrayList<>();
        var handle = mount("lifo", (context, config) -> {
            context.lifecycle().onClose("first", () -> events.add("first"));
            var nested = context.lifecycle().child("nested");
            nested.onClose("nested-entry", () -> events.add("nested-entry"));
            nested.child("deeper").onClose("deeper-entry", () -> events.add("deeper-entry"));
            context.lifecycle().onClose("last", () -> events.add("last"));
        });
        assertEquals(ComponentState.ACTIVE, TestKit.settle(handle).call());
        handle.dispose().toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertEquals(List.of("last", "deeper-entry", "nested-entry", "first"), events);
    }

    private static final class EquivalentConfig {
        private final String value;

        private EquivalentConfig(String value) {
            this.value = value;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof EquivalentConfig config && value.equals(config.value);
        }

        @Override
        public int hashCode() {
            return Objects.hash(value);
        }
    }

    private static final class BlockingEqualConfig {
        private final String value;
        private final CountDownLatch entered;
        private final CompletableFuture<Void> gate;

        private BlockingEqualConfig(
                String value,
                CountDownLatch entered,
                CompletableFuture<Void> gate) {
            this.value = value;
            this.entered = entered;
            this.gate = gate;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof BlockingEqualConfig config)) {
                return false;
            }
            if (entered != null) {
                entered.countDown();
            }
            if (gate != null) {
                gate.join();
            }
            return value.equals(config.value);
        }

        @Override
        public int hashCode() {
            return Objects.hash(value);
        }
    }
}
