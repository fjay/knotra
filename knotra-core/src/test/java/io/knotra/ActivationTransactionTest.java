package io.knotra;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class ActivationTransactionTest {
    static final CapabilityKey<String> TEXT = CapabilityKey.of("text", String.class);
    static final CapabilityKey<String> OTHER = CapabilityKey.of("other", String.class);

    KnotraRuntime runtime = KnotraRuntime.create();

    @AfterEach
    void tearDown() throws Exception {
        runtime.close();
    }

    private MountHandle mount(
            String mountId,
            TestKit.Start<NoConfig> start,
            CapabilityRequirement... requirements) {
        return TestKit.mount(runtime, runtime.root(), mountId, mountId, start, requirements);
    }

    private static Optional<String> currentActivation(KnotraRuntime runtime, MountHandle handle) {
        return Optional.ofNullable(TestKit.component(runtime, handle).currentActivationId());
    }

    @Test
    void missingRequiredCapabilityDoesNotRunStart() throws Exception {
        AtomicInteger starts = new AtomicInteger();
        var handle = mount("consumer", (context, config) -> starts.incrementAndGet(),
                CapabilityRequirement.required(TEXT));
        assertEquals(ComponentState.WAITING, handle.whenSettled().toCompletableFuture().get(1, TimeUnit.SECONDS));
        assertEquals(0, starts.get());
        assertTrue(runtime.advanced().snapshot().diagnostics().stream()
                .anyMatch(diagnostic -> diagnostic.code() == DiagnosticCode.MISSING_CAPABILITY));
    }

    @Test
    void requiredCapabilityAppearanceCreatesNewActivation() throws Exception {
        AtomicReference<String> observed = new AtomicReference<>();
        var handle = mount("consumer", (context, config) ->
                observed.set(context.require(TEXT)), CapabilityRequirement.required(TEXT));
        TestKit.provide(runtime, runtime.root(), TEXT, "value");
        assertEquals(ComponentState.ACTIVE, TestKit.settle(handle).call());
        assertEquals("value", observed.get());
        var activation = TestKit.component(runtime, handle);
        assertTrue(TestKit.component(runtime, handle).lastActivationId() != null);
    }

    @Test
    void startRegistrationsAndStatePublishInOneGeneration() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CompletableFuture<Void> gate = new CompletableFuture<>();
        var handle = mount("provider", (context, config) -> {
            context.provide(TEXT, "text");
            context.provide(OTHER, "other");
            started.countDown();
            gate.get();
        });
        assertTrue(started.await(10, TimeUnit.SECONDS));
        try {
            long before = runtime.advanced().snapshot().generation();
            assertEquals(ComponentState.STARTING, handle.state());
            assertTrue(runtime.advanced().snapshot().registrations().isEmpty());
        } finally {
            gate.complete(null);
        }
        assertEquals(ComponentState.ACTIVE, TestKit.settle(handle).call());
        RuntimeSnapshot snapshot = runtime.advanced().snapshot();
        assertEquals(2, snapshot.registrations().size());
        assertEquals(ComponentState.ACTIVE, TestKit.component(runtime, handle).state());
    }

    @Test
    void startFailureRollsBackResourcesAndStagedRegistrations() throws Exception {
        AtomicInteger closed = new AtomicInteger();
        var handle = mount("bad", (context, config) -> {
            context.lifecycle().onClose("resource", closed::incrementAndGet);
            context.provide(TEXT, "invisible");
            throw new IllegalStateException("boom");
        });
        assertEquals(ComponentState.FAILED, TestKit.settle(handle).call());
        assertEquals(1, closed.get());
        assertTrue(runtime.root().view().find(TEXT).isEmpty());
        assertTrue(runtime.advanced().snapshot().registrations().isEmpty());
        assertTrue(runtime.advanced().snapshot().diagnostics().stream()
                .anyMatch(diagnostic -> diagnostic.code() == DiagnosticCode.ACTIVATION_FAILED));
    }

    @Test
    void undeclaredRequireIsAContractFailure() throws Exception {
        var handle = mount("undeclared", (context, config) -> context.require(TEXT));
        assertEquals(ComponentState.FAILED, TestKit.settle(handle).call());
    }

    @Test
    void undeclaredOptionalFindIsRejected() throws Exception {
        var handle = mount("undeclared", (context, config) -> context.find(TEXT));
        assertEquals(ComponentState.FAILED, TestKit.settle(handle).call());
    }

    @Test
    void optionalMissingBindingIsExplicitAndStillReactivates() throws Exception {
        AtomicReference<Optional<String>> observed = new AtomicReference<>();
        var handle = mount("optional", (context, config) ->
                observed.set(context.find(TEXT)), CapabilityRequirement.optional(TEXT));
        assertEquals(ComponentState.ACTIVE, TestKit.settle(handle).call());
        assertEquals(Optional.empty(), observed.get());
        var binding = runtime.advanced().snapshot().activations().getFirst().bindings().getFirst();
        assertFalse(binding.present());
        assertEquals(CapabilityRequirement.Mode.OPTIONAL, binding.mode());

        var registration = TestKit.provide(runtime, runtime.root(), TEXT, "one");
        assertEquals(ComponentState.ACTIVE, TestKit.settle(handle).call());
        assertEquals(Optional.of("one"), observed.get());
        String first = TestKit.component(runtime, handle).currentActivationId();

        TestKit.assertCommitted(runtime.advanced().transact(mutation -> {
            mutation.revoke(registration);
            return null;
        }));
        assertEquals(ComponentState.ACTIVE, TestKit.settle(handle).call());
        assertEquals(Optional.empty(), observed.get());
        assertNotEquals(first, TestKit.component(runtime, handle).currentActivationId());
    }

    @Test
    void sameValueWithNewRegistrationIdentityReactivates() throws Exception {
        AtomicReference<String> observed = new AtomicReference<>();
        var handle = mount("consumer", (context, config) ->
                observed.set(context.require(TEXT)), CapabilityRequirement.required(TEXT));
        var first = TestKit.provide(runtime, runtime.root(), TEXT, "same");
        assertEquals(ComponentState.ACTIVE, TestKit.settle(handle).call());
        String firstActivation = TestKit.component(runtime, handle).currentActivationId();
        TestKit.assertCommitted(runtime.advanced().transact(mutation -> {
            mutation.revoke(first);
            return null;
        }));
        assertEquals(ComponentState.WAITING, TestKit.settle(handle).call());
        TestKit.provide(runtime, runtime.root(), TEXT, "same");
        assertEquals(ComponentState.ACTIVE, TestKit.settle(handle).call());
        assertNotEquals(firstActivation, TestKit.component(runtime, handle).currentActivationId());
        assertEquals("same", observed.get());
    }

    @Test
    void staleStartRollsBackAndReconcilesLatestBinding() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CompletableFuture<Void> gate = new CompletableFuture<>();
        AtomicReference<String> observed = new AtomicReference<>();
        var handle = mount("consumer", (context, config) -> {
            started.countDown();
            gate.get();
            observed.set(context.require(TEXT));
        }, CapabilityRequirement.required(TEXT));
        var first = TestKit.provide(runtime, runtime.root(), TEXT, "one");
        assertTrue(started.await(10, TimeUnit.SECONDS));
        TestKit.assertCommitted(runtime.advanced().transact(mutation -> {
            mutation.revoke(first);
            return null;
        }));
        gate.complete(null);
        assertEquals(ComponentState.WAITING, TestKit.settle(handle).call(),
                () -> runtime.advanced().snapshot().toString());
        TestKit.provide(runtime, runtime.root(), TEXT, "two");
        assertEquals(ComponentState.ACTIVE, TestKit.settle(handle).call());
        assertEquals("two", observed.get());
        assertTrue(runtime.advanced().snapshot().diagnostics().stream().noneMatch(diagnostic ->
                diagnostic.code() == DiagnosticCode.ACTIVATION_FAILED
                        && diagnostic.targetId().equals(handle.handleId())));
    }

    @Test
    void failedStartCanBeRetriedAfterExternalProblemIsFixed() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        var handle = mount("retry", (context, config) -> {
            if (attempts.incrementAndGet() == 1) {
                throw new IllegalStateException("temporary");
            }
        });
        assertEquals(ComponentState.FAILED, TestKit.settle(handle).call());
        assertEquals(ComponentState.ACTIVE, handle.retryAsync().toCompletableFuture().get(10, TimeUnit.SECONDS));
        assertEquals(2, attempts.get());
    }

    @Test
    void observingFailedStartDoesNotImplicitlyRetryIt() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        var handle = mount("observe-start", (context, config) -> {
            if (attempts.incrementAndGet() == 1) {
                throw new IllegalStateException("temporary");
            }
        });

        awaitState(handle, ComponentState.FAILED);
        assertEquals(ComponentState.FAILED, handle.whenSettled()
                .toCompletableFuture().get(10, TimeUnit.SECONDS));
        assertEquals(1, attempts.get());

        assertEquals(ComponentState.ACTIVE, handle.retryAsync()
                .toCompletableFuture().get(10, TimeUnit.SECONDS));
        assertEquals(2, attempts.get());
    }

    @Test
    void dirtyPropagationDoesNotBypassExplicitStartRetry() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        AtomicReference<Config> observedConfig = new AtomicReference<>();
        Component<Config> component = new TestKit.Scripted<>(
                ComponentDescriptor.named("configured"), (context, config) -> {
                    observedConfig.set(config);
                    if (attempts.incrementAndGet() == 1) {
                        throw new IllegalStateException("temporary");
                    }
                });
        var handle = runtime.advanced().transact(mutation -> mutation.mount(
                runtime.root(), "dirty-retry", TestKit.factory("configured", component),
                new Config("one"))).value();

        awaitState(handle, ComponentState.FAILED);
        assertEquals(ComponentState.FAILED, handle.reconfigureAsync(new Config("two"))
                .toCompletableFuture().get(10, TimeUnit.SECONDS));
        assertEquals(1, attempts.get());

        assertEquals(ComponentState.ACTIVE, handle.retryAsync()
                .toCompletableFuture().get(10, TimeUnit.SECONDS));
        assertEquals(2, attempts.get());
        assertEquals(new Config("two"), observedConfig.get());
    }

    @Test
    void retryRejectedWhenComponentIsNotFailed() throws Exception {
        var handle = mount("active", (context, config) -> {});
        assertEquals(ComponentState.ACTIVE, TestKit.settle(handle).call());
        assertTrue(handle.retryAsync().toCompletableFuture().isCompletedExceptionally());
    }

    @Test
    void reconfigureUsesSameHandleAndNewActivation() throws Exception {
        var configs = new java.util.concurrent.CopyOnWriteArrayList<Object>();
        Component<Object> component = new TestKit.Scripted<>(
                ComponentDescriptor.named("configured"), (context, config) -> configs.add(config));
        ComponentFactory<Object> typedFactory = TestKit.factory("configured", component);
        var handle = runtime.advanced().transact(mutation -> mutation.mount(
                runtime.root(), "configured", typedFactory, new Object())).value();
        assertEquals(ComponentState.ACTIVE, TestKit.settle(handle).call());
        String first = TestKit.component(runtime, handle).currentActivationId();
        assertEquals(ComponentState.ACTIVE, handle.reconfigureAsync(new Object())
                .toCompletableFuture().get(10, TimeUnit.SECONDS));
        String second = TestKit.component(runtime, handle).currentActivationId();
        assertNotEquals(first, second);
        assertEquals(2, handle.configRevision());
        assertEquals(2, configs.size());
    }

    @Test
    void childMountIsStagedUntilParentCommit() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CompletableFuture<Void> gate = new CompletableFuture<>();
        AtomicReference<MountHandle> child = new AtomicReference<>();
        var parent = mount("parent", (context, config) -> {
            child.set(context.mountChild("child",
                    TestKit.factory("child", new TestKit.Scripted<>(
                            ComponentDescriptor.named("child"), (childContext, childConfig) -> {})),
                    NoConfig.INSTANCE));
            started.countDown();
            gate.get();
        });
        assertTrue(started.await(10, TimeUnit.SECONDS));
        assertTrue(runtime.advanced().snapshot().mounts().stream()
                .noneMatch(component -> component.mountId().equals("child")));
        gate.complete(null);
        assertEquals(ComponentState.ACTIVE, TestKit.settle(parent).call());
        assertEquals(ComponentState.ACTIVE, TestKit.settle(child.get()).call());
        assertEquals(ComponentOrigin.Kind.HOST, TestKit.component(runtime, child.get()).origin().kind());
        assertEquals(parent.handleId(), TestKit.component(runtime, child.get()).parentHandleId());
    }

    @Test
    void parentFailureTerminatesStagedChildAndReusesMountIdOnRetry() throws Exception {
        AtomicReference<MountHandle> firstChild = new AtomicReference<>();
        AtomicInteger attempts = new AtomicInteger();
        AtomicReference<MountHandle> secondChild = new AtomicReference<>();
        var parent = mount("parent", (context, config) -> {
            ComponentFactory<NoConfig> childFactory = TestKit.factory("child", new TestKit.Scripted<>(
                    ComponentDescriptor.named("child"), (childContext, childConfig) -> {}));
            if (attempts.incrementAndGet() == 1) {
                firstChild.set(context.mountChild("child", childFactory, NoConfig.INSTANCE));
                throw new IllegalStateException("parent failed");
            }
            secondChild.set(context.mountChild("child", childFactory, NoConfig.INSTANCE));
        });
        assertEquals(ComponentState.FAILED, TestKit.settle(parent).call());
        assertEquals(ComponentState.DISPOSED, firstChild.get().state());
        assertTrue(runtime.advanced().snapshot().mounts().stream()
                .noneMatch(component -> component.mountId().equals("child")));
        assertEquals(ComponentState.ACTIVE, parent.retryAsync().toCompletableFuture().get(10, TimeUnit.SECONDS));
        assertEquals(ComponentState.ACTIVE, TestKit.settle(secondChild.get()).call());
        assertNotEquals(firstChild.get().handleId(), secondChild.get().handleId());
        assertEquals("child", secondChild.get().mountId());
    }

    @Test
    void activationContextIsClosedAfterStartReturns() throws Exception {
        AtomicReference<ActivationContext> saved = new AtomicReference<>();
        var handle = mount("holder", (context, config) -> saved.set(context));
        assertEquals(ComponentState.ACTIVE, TestKit.settle(handle).call());
        assertThrows(IllegalStateException.class, () -> saved.get().require(TEXT));
    }

    @Test
    void failedCleanupReconfigureDoesNotStartNextActivationBeforeRetry() throws Exception {
        AtomicBoolean failCleanup = new AtomicBoolean(true);
        AtomicInteger cleanupAttempts = new AtomicInteger();
        AtomicInteger starts = new AtomicInteger();
        Component<Config> component = new TestKit.Scripted<>(
                ComponentDescriptor.named("configured"), (context, config) -> {
                    starts.incrementAndGet();
                    context.lifecycle().onClose("cleanup", () -> {
                        cleanupAttempts.incrementAndGet();
                        if (failCleanup.getAndSet(false)) {
                            throw new IllegalStateException("cleanup failed");
                        }
                    });
                });
        ComponentFactory<Config> typedFactory = TestKit.factory("configured", component);
        var handle = runtime.advanced().transact(mutation -> mutation.mount(
                runtime.root(), "configured", typedFactory, new Config("one"))).value();
        assertEquals(ComponentState.ACTIVE, TestKit.settle(handle).call());
        assertEquals(ComponentState.FAILED, handle.reconfigureAsync(new Config("two"))
                .toCompletableFuture().get(10, TimeUnit.SECONDS));
        assertEquals(1, starts.get());
        assertEquals(1, cleanupAttempts.get());
        assertEquals(ComponentState.ACTIVE, handle.retryAsync().toCompletableFuture().get(10, TimeUnit.SECONDS));
        assertEquals(2, starts.get());
        assertEquals(2, cleanupAttempts.get());
    }

    @Test
    void observingFailedCleanupDoesNotImplicitlyRetryIt() throws Exception {
        AtomicBoolean failCleanup = new AtomicBoolean(true);
        AtomicInteger cleanupAttempts = new AtomicInteger();
        var handle = mount("observe-cleanup", (context, config) ->
                context.lifecycle().onClose("cleanup", () -> {
                    cleanupAttempts.incrementAndGet();
                    if (failCleanup.getAndSet(false)) {
                        throw new IllegalStateException("temporary");
                    }
                }));

        assertEquals(ComponentState.ACTIVE, TestKit.settle(handle).call());
        runtime.advanced().transact(transaction -> {
            transaction.dispose(handle);
            return null;
        });

        awaitState(handle, ComponentState.FAILED);
        assertEquals(ComponentState.FAILED, handle.whenSettled()
                .toCompletableFuture().get(10, TimeUnit.SECONDS));
        assertEquals(1, cleanupAttempts.get());

        var firstDisposeAfterFailure = handle.disposeAsync();
        assertEquals(ComponentState.FAILED, firstDisposeAfterFailure
                .toCompletableFuture().get(10, TimeUnit.SECONDS));
        var secondDisposeAfterFailure = handle.disposeAsync();
        assertNotSame(firstDisposeAfterFailure, secondDisposeAfterFailure);
        assertEquals(ComponentState.FAILED, secondDisposeAfterFailure
                .toCompletableFuture().get(10, TimeUnit.SECONDS));
        assertEquals(1, cleanupAttempts.get());
        assertEquals(ComponentState.DISPOSED, handle.retryAsync()
                .toCompletableFuture().get(10, TimeUnit.SECONDS));
        assertEquals(2, cleanupAttempts.get());
    }

    private static void awaitState(MountHandle handle, ComponentState expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (handle.state() != expected) {
            if (System.nanoTime() - deadline >= 0) {
                fail("expected " + expected + " but remained " + handle.state());
            }
            Thread.yield();
        }
    }

    private record Config(String value) {}
}
