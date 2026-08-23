package io.knotra;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class OwnedChildSemanticsTest {
    static final CapabilityKey<String> A = CapabilityKey.of("owned-a", String.class);

    KnotraRuntime runtime = KnotraRuntime.create();

    @AfterEach
    void tearDown() throws Exception {
        runtime.close();
    }

    @Test
    void reconfigureDisposesOldOwnedSubtreeAndReusesMountId() throws Exception {
        AtomicReference<MountHandle> firstChild = new AtomicReference<>();
        AtomicReference<MountHandle> firstGrandchild = new AtomicReference<>();
        AtomicReference<MountHandle> secondChild = new AtomicReference<>();
        AtomicReference<MountHandle> secondGrandchild = new AtomicReference<>();
        AtomicInteger starts = new AtomicInteger();

        var parent = runtime.advanced().transact(mutation -> mutation.mount(
                runtime.root(),
                "parent",
                TestKit.factory("parent", new TestKit.Scripted<>(
                        ComponentDescriptor.named("parent"),
                        (context, config) -> {
                            starts.incrementAndGet();
                            MountHandle child = context.mountChild(
                                    "child",
                                    starts.get() == 1
                                            ? childFactory("first-grandchild", firstGrandchild)
                                            : childFactory("second-grandchild", secondGrandchild),
                                    NoConfig.INSTANCE);
                            if (starts.get() == 1) {
                                firstChild.set(child);
                            } else {
                                secondChild.set(child);
                            }
                        })),
                new Config("one"))).value();
        assertEquals(ComponentState.ACTIVE, TestKit.settle(parent).call());
        assertEquals(ComponentState.ACTIVE, TestKit.settle(firstChild.get()).call());
        assertEquals(ComponentState.ACTIVE, TestKit.settle(firstGrandchild.get()).call());
        String firstParentActivation = TestKit.component(runtime, parent).currentActivationId();

        assertEquals(ComponentState.ACTIVE, parent.reconfigureAsync(new Config("two"))
                .toCompletableFuture().get(10, TimeUnit.SECONDS));
        assertEquals(ComponentState.ACTIVE, TestKit.settle(secondChild.get()).call());
        assertEquals(ComponentState.ACTIVE, TestKit.settle(secondGrandchild.get()).call());

        assertEquals(ComponentState.DISPOSED, firstChild.get().state(),
                () -> runtime.advanced().snapshot().toString());
        assertEquals(ComponentState.DISPOSED, firstGrandchild.get().state(),
                () -> runtime.advanced().snapshot().toString());
        assertNotEquals(firstChild.get().handleId(), secondChild.get().handleId());
        assertEquals("child", secondChild.get().mountId());

        String secondParentActivation = TestKit.component(runtime, parent).currentActivationId();
        assertNotEquals(firstParentActivation, secondParentActivation);
        RuntimeSnapshot.MountSnapshot newChild = TestKit.component(runtime, secondChild.get());
        assertEquals(secondParentActivation, newChild.ownerActivationId());
        assertEquals(parent.handleId(), newChild.parentHandleId());
        assertTrue(runtime.advanced().snapshot().activations().stream().noneMatch(activation ->
                activation.handleId().equals(firstChild.get().handleId())));
        assertTrue(runtime.advanced().snapshot().activations().stream().noneMatch(activation ->
                activation.handleId().equals(firstGrandchild.get().handleId())));
        assertEquals(1, runtime.advanced().snapshot().mounts().stream()
                .filter(component -> component.mountId().equals("child"))
                .count());
        assertEquals(1, runtime.advanced().snapshot().mounts().stream()
                .filter(component -> component.mountId().contains("grandchild"))
                .count());
    }

    @Test
    void providerReplacementDisposesOwnedChildrenAndClearsPublicBindings() throws Exception {
        AtomicReference<MountHandle> oldChild = new AtomicReference<>();
        AtomicReference<MountHandle> newChild = new AtomicReference<>();
        AtomicInteger starts = new AtomicInteger();

        RegistrationHandle firstProvider = TestKit.provide(
                runtime, runtime.root(), A, "one");
        var parent = TestKit.mount(runtime, runtime.root(), "parent",
                (context, config) -> {
                    MountHandle child = context.mountChild(
                            "child", childFactory("ignored-grandchild", new AtomicReference<>()),
                            NoConfig.INSTANCE);
                    if (starts.incrementAndGet() == 1) {
                        oldChild.set(child);
                    } else {
                        newChild.set(child);
                    }
                }, CapabilityRequirement.required(A));
        assertEquals(ComponentState.ACTIVE, TestKit.settle(parent).call());
        assertEquals(ComponentState.ACTIVE, TestKit.settle(oldChild.get()).call());

        var replacement = runtime.advanced().transact(mutation -> {
            mutation.revoke(firstProvider);
            mutation.provide(runtime.root(), A, "two");
            return null;
        });
        TestKit.assertCommitted(replacement);

        RuntimeSnapshot stopping = runtime.advanced().snapshot();
        RuntimeSnapshot.ActivationSnapshot oldActivation = stopping.activations().stream()
                .filter(activation -> activation.handleId().equals(parent.handleId()))
                .findFirst().orElseThrow();
        assertEquals(ActivationState.STOPPING, oldActivation.state());
        assertTrue(oldActivation.bindings().stream()
                .filter(binding -> binding.capability().name().equals(A.name()))
                .allMatch(binding -> !binding.present() && binding.registrationId() == null));

        replacement.settlement().whenSettled().toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertEquals(ComponentState.ACTIVE, TestKit.settle(parent).call(),
                () -> runtime.advanced().snapshot().toString());
        assertEquals(ComponentState.ACTIVE, TestKit.settle(newChild.get()).call());
        assertEquals(ComponentState.DISPOSED, oldChild.get().state());
        assertEquals(1, runtime.advanced().snapshot().mounts().stream()
                .filter(component -> component.mountId().equals("child"))
                .count());
    }

    @Test
    void failedOwnedChildCleanupMustRetryBeforeReplacementOwnsMountId() throws Exception {
        AtomicBoolean failCleanup = new AtomicBoolean(true);
        AtomicInteger cleanupAttempts = new AtomicInteger();
        AtomicReference<MountHandle> oldChild = new AtomicReference<>();
        AtomicReference<MountHandle> newChild = new AtomicReference<>();
        AtomicInteger starts = new AtomicInteger();

        ComponentFactory<NoConfig> failingChildFactory = TestKit.factory("child",
                new TestKit.Scripted<>(ComponentDescriptor.named("child"),
                        (context, config) -> context.lifecycle().onClose("cleanup", () -> {
                            cleanupAttempts.incrementAndGet();
                            if (failCleanup.getAndSet(false)) {
                                throw new IllegalStateException("child cleanup failed");
                            }
                        })));
        var parent = runtime.advanced().transact(mutation -> mutation.mount(
                runtime.root(),
                "parent",
                TestKit.factory("parent", new TestKit.Scripted<>(
                        ComponentDescriptor.named("parent"),
                        (context, config) -> {
                            MountHandle child = context.mountChild(
                                    "child", failingChildFactory, NoConfig.INSTANCE);
                            if (starts.incrementAndGet() == 1) {
                                oldChild.set(child);
                            } else {
                                newChild.set(child);
                            }
                        })),
                new Config("one"))).value();

        assertEquals(ComponentState.ACTIVE, TestKit.settle(parent).call());
        assertEquals(ComponentState.ACTIVE, TestKit.settle(oldChild.get()).call());

        assertEquals(ComponentState.FAILED, parent.reconfigureAsync(new Config("two"))
                .toCompletableFuture().get(10, TimeUnit.SECONDS));
        assertEquals(ComponentState.FAILED, oldChild.get().state());
        assertEquals(ComponentGoal.DISPOSED, oldChild.get().goal());
        assertEquals(1, cleanupAttempts.get());

        assertEquals(ComponentState.DISPOSED, oldChild.get().retryAsync()
                .toCompletableFuture().get(10, TimeUnit.SECONDS));
        assertEquals(2, cleanupAttempts.get());
        assertEquals(ComponentState.ACTIVE, parent.retryAsync()
                .toCompletableFuture().get(10, TimeUnit.SECONDS));
        assertEquals(ComponentState.ACTIVE, TestKit.settle(newChild.get()).call());

        assertNotEquals(oldChild.get().handleId(), newChild.get().handleId());
        assertTrue(runtime.advanced().snapshot().mounts().stream().noneMatch(component ->
                component.handleId().equals(oldChild.get().handleId())));
        assertTrue(runtime.advanced().snapshot().activations().stream().noneMatch(activation ->
                activation.handleId().equals(oldChild.get().handleId())));
        assertEquals(TestKit.component(runtime, parent).currentActivationId(),
                TestKit.component(runtime, newChild.get()).ownerActivationId());
    }

    @Test
    void runnableDisposerApiRequiresNoCast() throws Exception {
        var handle = TestKit.mount(runtime, runtime.root(), "scope",
                (context, config) -> {
                    context.lifecycle().onClose("plain", () -> { });
                    context.lifecycle().manage("resource", new TestResource());
                });
        assertEquals(ComponentState.ACTIVE, TestKit.settle(handle).call());
        handle.disposeAsync().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @Test
    void handlesCompareOnlyWithinExactRuntimeImplementation() throws Exception {
        KnotraRuntime other = KnotraRuntime.create();
        try {
            assertFalse(runtime.root().equals(other.root()));
            assertFalse(runtime.root().equals(new ExternalContext("ctx-root")));

            var component = TestKit.mount(runtime, runtime.root(), "component",
                    (context, config) -> { });
            assertFalse(component.equals(new ExternalComponent(component.handleId())));
            assertFalse(component.equals(null));
            assertEquals(component.hashCode(), component.hashCode());

            RegistrationHandle registration = TestKit.provide(
                    runtime, runtime.root(), A, "value");
            assertFalse(registration.equals(new ExternalRegistration(registration.registrationId())));
            assertEquals(registration.hashCode(), registration.hashCode());
        } finally {
            other.close();
        }
    }


    @Test
    void ownedChildSettlementWaitsForChildWithoutBlockingParentVisibility() throws Exception {
        CountDownLatch childEntered = new CountDownLatch(1);
        CompletableFuture<Void> childGate = new CompletableFuture<>();
        AtomicReference<MountHandle> child = new AtomicReference<>();

        TransactionReceipt<ConfiguredMountHandle<Config>> receipt =
                runtime.advanced().transact(mutation -> mutation.mount(
                        runtime.root(),
                        "parent",
                        TestKit.factory("parent", new TestKit.Scripted<>(
                                ComponentDescriptor.named("parent"),
                                (context, config) -> {
                                    context.provide(A, "parent-output");
                                    child.set(context.mountChild(
                                            "child",
                                            TestKit.factory("child", new TestKit.Scripted<>(
                                                    ComponentDescriptor.named(
                                                            "child",
                                                            CapabilityRequirement.required(A)),
                                                    (childContext, childConfig) -> {
                                                        childEntered.countDown();
                                                        assertEquals("parent-output",
                                                                childContext.require(A));
                                                        childGate.get();
                                                    })),
                                            NoConfig.INSTANCE));
                                })),
                        new Config("one")));
        ConfiguredMountHandle<Config> parent = receipt.value();

        assertTrue(childEntered.await(10, TimeUnit.SECONDS));
        assertEquals(ComponentState.ACTIVE, parent.whenSettled()
                .toCompletableFuture().get(10, TimeUnit.SECONDS));
        assertEquals(ComponentState.ACTIVE, parent.state());
        assertEquals(ComponentState.STARTING, child.get().state());
        assertFalse(receipt.settlement().whenSettled().toCompletableFuture().isDone());

        childGate.complete(null);
        SettlementReport report = receipt.settlement().whenSettled()
                .toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertEquals(ComponentState.ACTIVE, report.outcome(parent.handleId())
                .map(SettlementReport.MountOutcome::state).orElseThrow(), report::toString);
        assertEquals(ComponentState.ACTIVE, report.outcome(child.get().handleId())
                .map(SettlementReport.MountOutcome::state).orElseThrow(), report::toString);
        assertTrue(!report.hasFailedMounts() && !report.affectedMounts().isEmpty());
    }

    @Test
    void ownedChildFailureIsIncludedInParentOperationSettlement() throws Exception {
        AtomicReference<MountHandle> child = new AtomicReference<>();
        ComponentFactory<NoConfig> failingChild = TestKit.factory(
                "child",
                new TestKit.Scripted<>(
                        ComponentDescriptor.named("child"),
                        (childContext, childConfig) -> {
                            throw new IllegalStateException("child failed");
                        }));
        ComponentFactory<Config> parentFactory = TestKit.factory(
                "parent",
                new TestKit.Scripted<>(
                        ComponentDescriptor.named("parent"),
                        (context, config) -> child.set(context.mountChild(
                                "child", failingChild, NoConfig.INSTANCE))));
        TransactionReceipt<ConfiguredMountHandle<Config>> receipt =
                runtime.advanced().transact(mutation -> mutation.mount(
                        runtime.root(),
                        "parent",
                        parentFactory,
                        new Config("one")));

        SettlementReport report = receipt.settlement().whenSettled()
                .toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertTrue(report.hasFailedMounts(), report::toString);

        assertEquals(ComponentState.ACTIVE, report.outcome(receipt.value().handleId())
                .map(SettlementReport.MountOutcome::state).orElseThrow());
        assertEquals(ComponentState.FAILED, report.outcome(child.get().handleId())
                .map(SettlementReport.MountOutcome::state).orElseThrow());
        assertTrue(report.diagnostics().stream().anyMatch(diagnostic ->
                child.get().handleId().equals(diagnostic.targetId())
                        && diagnostic.message().contains("child failed")));
    }

    @Test
    void reconfigureSettlementIncludesNewOwnedChild() throws Exception {
        AtomicReference<MountHandle> firstChild = new AtomicReference<>();
        AtomicReference<MountHandle> secondChild = new AtomicReference<>();
        AtomicInteger starts = new AtomicInteger();
        ConfiguredMountHandle<Config> parent = runtime.advanced().transact(mutation -> mutation.mount(
                runtime.root(),
                "parent",
                TestKit.factory("parent", new TestKit.Scripted<>(
                        ComponentDescriptor.named("parent"),
                        (context, config) -> {
                            starts.incrementAndGet();
                            MountHandle child = context.mountChild(
                                     "child-" + starts.get(),
                                    TestKit.factory("child", new TestKit.Scripted<>(
                                            ComponentDescriptor.named("child"),
                                            (childContext, childConfig) -> { })),
                                    NoConfig.INSTANCE);
                            if (starts.get() == 1) {
                                firstChild.set(child);
                            } else {
                                secondChild.set(child);
                            }
                        })),
                new Config("one"))).value();
        assertEquals(ComponentState.ACTIVE, parent.whenSettled()
                .toCompletableFuture().get(10, TimeUnit.SECONDS));
        assertEquals(ComponentState.ACTIVE, firstChild.get().whenSettled()
                .toCompletableFuture().get(10, TimeUnit.SECONDS));

        TransactionReceipt<Void> reconfigured = runtime.advanced().transact(mutation -> {
            mutation.reconfigure(parent, new Config("two"));
            return null;
        });
        SettlementReport report = reconfigured.settlement().whenSettled()
                .toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertEquals(ComponentState.ACTIVE, report.outcome(parent.handleId())
                .map(SettlementReport.MountOutcome::state).orElseThrow());
        SettlementReport.MountOutcome oldOutcome = report
                .outcome(firstChild.get().handleId()).orElseThrow();
        assertEquals(ComponentState.DISPOSED, oldOutcome.state());
        assertEquals("child-1", oldOutcome.mountId());
        assertEquals(ComponentState.ACTIVE,
                report.outcome(secondChild.get().handleId())
                        .map(SettlementReport.MountOutcome::state).orElseThrow());

    }


    private ComponentFactory<NoConfig> childFactory(
            String mountId,
            AtomicReference<MountHandle> mounted) {
        return TestKit.factory("child-owner", new TestKit.Scripted<>(
                ComponentDescriptor.named("child-owner"),
                (context, config) -> mounted.set(context.mountChild(
                        mountId,
                        TestKit.factory("grandchild", new TestKit.Scripted<>(
                                ComponentDescriptor.named("grandchild"),
                                (grandchildContext, grandchildConfig) -> { })),
                        NoConfig.INSTANCE))));
    }

    private static final class TestResource implements AutoCloseable {
        @Override
        public void close() {
        }
    }

    private record Config(String value) {
    }

    private record ExternalContext(String id) implements ContextHandle {
        @Override
        public String contextId() {
            return id;
        }

        @Override
        public ContextInfo info() {
            throw new UnsupportedOperationException();
        }

        @Override
        public ContextView view() {
            throw new UnsupportedOperationException();
        }

        @Override
        public ContextState state() {
            return ContextState.DISPOSED;
        }

        @Override
        public CompletionStage<ContextState> disposeAsync() {
            throw new UnsupportedOperationException();
        }
    }

    private static final class ExternalComponent implements MountHandle {
        private final String id;

        private ExternalComponent(String id) {
            this.id = id;
        }

        @Override
        public String handleId() {
            return id;
        }

        @Override
        public String mountId() {
            return "external";
        }

        @Override
        public String componentId() {
            return "external";
        }

        @Override
        public String factoryId() {
            return "external";
        }

        @Override
        public String contextId() {
            return "ctx-root";
        }

        @Override
        public ComponentState state() {
            return ComponentState.DISPOSED;
        }

        @Override
        public ComponentGoal goal() {
            return ComponentGoal.DISPOSED;
        }

        @Override
        public long configRevision() {
            return 0;
        }

        @Override
        public CompletionStage<ComponentState> whenSettled() {
            throw new UnsupportedOperationException();
        }


        @Override
        public CompletionStage<ComponentState> retryAsync() {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletionStage<ComponentState> disposeAsync() {
            throw new UnsupportedOperationException();
        }
    }
    private record ExternalRegistration(String registrationId) implements RegistrationHandle {
    }
}
