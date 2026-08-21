package io.knotra;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

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
        AtomicReference<ComponentHandle<NoConfig>> firstChild = new AtomicReference<>();
        AtomicReference<ComponentHandle<NoConfig>> firstGrandchild = new AtomicReference<>();
        AtomicReference<ComponentHandle<NoConfig>> secondChild = new AtomicReference<>();
        AtomicReference<ComponentHandle<NoConfig>> secondGrandchild = new AtomicReference<>();
        AtomicInteger starts = new AtomicInteger();

        var parent = runtime.transact(mutation -> mutation.mount(
                runtime.root(),
                "parent",
                TestKit.factory("parent", new TestKit.Scripted<>(
                        ComponentDescriptor.named("parent"),
                        (context, config) -> {
                            starts.incrementAndGet();
                            ComponentHandle<NoConfig> child = context.mountChild(
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
                () -> runtime.snapshot().toString());
        assertEquals(ComponentState.DISPOSED, firstGrandchild.get().state(),
                () -> runtime.snapshot().toString());
        assertNotEquals(firstChild.get().handleId(), secondChild.get().handleId());
        assertEquals("child", secondChild.get().mountId());

        String secondParentActivation = TestKit.component(runtime, parent).currentActivationId();
        assertNotEquals(firstParentActivation, secondParentActivation);
        RuntimeSnapshot.ComponentSnapshot newChild = TestKit.component(runtime, secondChild.get());
        assertEquals(secondParentActivation, newChild.ownerActivationId());
        assertEquals(parent.handleId(), newChild.parentHandleId());
        assertTrue(runtime.snapshot().activations().stream().noneMatch(activation ->
                activation.handleId().equals(firstChild.get().handleId())));
        assertTrue(runtime.snapshot().activations().stream().noneMatch(activation ->
                activation.handleId().equals(firstGrandchild.get().handleId())));
        assertEquals(1, runtime.snapshot().components().stream()
                .filter(component -> component.mountId().equals("child"))
                .count());
        assertEquals(1, runtime.snapshot().components().stream()
                .filter(component -> component.mountId().contains("grandchild"))
                .count());
    }

    @Test
    void providerReplacementDisposesOwnedChildrenAndClearsPublicBindings() throws Exception {
        AtomicReference<ComponentHandle<NoConfig>> oldChild = new AtomicReference<>();
        AtomicReference<ComponentHandle<NoConfig>> newChild = new AtomicReference<>();
        AtomicInteger starts = new AtomicInteger();

        RegistrationHandle firstProvider = TestKit.provide(
                runtime, runtime.root(), A, "one");
        var parent = TestKit.mount(runtime, runtime.root(), "parent",
                (context, config) -> {
                    ComponentHandle<NoConfig> child = context.mountChild(
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

        var replacement = runtime.transact(mutation -> {
            mutation.revoke(firstProvider);
            mutation.provide(runtime.root(), A, "two");
            return null;
        });
        TestKit.assertCommitted(replacement);

        RuntimeSnapshot stopping = runtime.snapshot();
        RuntimeSnapshot.ActivationSnapshot oldActivation = stopping.activations().stream()
                .filter(activation -> activation.handleId().equals(parent.handleId()))
                .findFirst().orElseThrow();
        assertEquals(ActivationState.STOPPING, oldActivation.state());
        assertTrue(oldActivation.bindings().stream()
                .filter(binding -> binding.capability().name().equals(A.name()))
                .allMatch(binding -> !binding.present() && binding.registrationId() == null));

        replacement.settlement().toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertEquals(ComponentState.ACTIVE, TestKit.settle(parent).call(),
                () -> runtime.snapshot().toString());
        assertEquals(ComponentState.ACTIVE, TestKit.settle(newChild.get()).call());
        assertEquals(ComponentState.DISPOSED, oldChild.get().state());
        assertEquals(1, runtime.snapshot().components().stream()
                .filter(component -> component.mountId().equals("child"))
                .count());
    }

    @Test
    void failedOwnedChildCleanupMustRetryBeforeReplacementOwnsMountId() throws Exception {
        AtomicBoolean failCleanup = new AtomicBoolean(true);
        AtomicInteger cleanupAttempts = new AtomicInteger();
        AtomicReference<ComponentHandle<NoConfig>> oldChild = new AtomicReference<>();
        AtomicReference<ComponentHandle<NoConfig>> newChild = new AtomicReference<>();
        AtomicInteger starts = new AtomicInteger();

        ComponentFactory<NoConfig> failingChildFactory = TestKit.factory("child",
                new TestKit.Scripted<>(ComponentDescriptor.named("child"),
                        (context, config) -> context.lifecycle().onClose("cleanup", () -> {
                            cleanupAttempts.incrementAndGet();
                            if (failCleanup.getAndSet(false)) {
                                throw new IllegalStateException("child cleanup failed");
                            }
                        })));
        var parent = runtime.transact(mutation -> mutation.mount(
                runtime.root(),
                "parent",
                TestKit.factory("parent", new TestKit.Scripted<>(
                        ComponentDescriptor.named("parent"),
                        (context, config) -> {
                            ComponentHandle<NoConfig> child = context.mountChild(
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
        assertTrue(runtime.snapshot().components().stream().noneMatch(component ->
                component.handleId().equals(oldChild.get().handleId())));
        assertTrue(runtime.snapshot().activations().stream().noneMatch(activation ->
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

    private ComponentFactory<NoConfig> childFactory(
            String mountId,
            AtomicReference<ComponentHandle<NoConfig>> mounted) {
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

    private static final class ExternalComponent implements ComponentHandle<NoConfig> {
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
        public CompletionStage<ComponentState> reconfigureAsync(NoConfig config) {
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
