package io.knotra.internal;

import io.knotra.CapabilityKey;
import io.knotra.CapabilityRequirement;
import io.knotra.ComponentDescriptor;
import io.knotra.ComponentState;
import io.knotra.ContextHandle;
import io.knotra.KnotraRuntime;
import io.knotra.MountFactory;
import io.knotra.MountHandle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Activation 最终提交管线的阶段故障契约：
 * prepublish 失败不得发布半候选；final publish 之后的任何故障不得回滚已提交结构。
 */
final class ActivationCommitPipelineTest {
    private final KnotraRuntime publicRuntime = KnotraRuntime.create();
    private final DefaultKnotraRuntime runtime =
            (DefaultKnotraRuntime) publicRuntime;

    @AfterEach
    void tearDown() {
        runtime.activationCoordinator().activationPrepublishProbe = null;
        runtime.activationCoordinator().activationFinalPublishProbe = null;
        runtime.activationCoordinator().activationPostPublishEffectProbe = null;
        runtime.activationCoordinator().scheduler().transitionReservationFaultProbe = null;
        runtime.activationCoordinator().scheduler().transitionReservationProbe = null;
        runtime.activationCoordinator().transitionPublicationProbe = null;
        runtime.activationCoordinator().activationRollbackCommitProbe = null;
        publicRuntime.close();
    }

    @Test
    void postpublishProbeFailureKeepsCommittedActivationState() throws Exception {
        CapabilityKey<String> key =
                CapabilityKey.of("postpublish-capability", String.class);
        AtomicReference<String> childId = new AtomicReference<>();
        AtomicBoolean injected = new AtomicBoolean();
        List<PublishedKernelState> generations =
                Collections.synchronizedList(new ArrayList<>());
        CountDownLatch startEntered = new CountDownLatch(1);
        CountDownLatch releaseStart = new CountDownLatch(1);
        runtime.activationCoordinator().transitionPublicationProbe = () -> {
            PublishedKernelState state = runtime.publishedState();
            state.validateInvariants();
            generations.add(state);
            if (activationOwnerHandle(state, key.name()) != null
                    && !injected.getAndSet(true)) {
                throw new IllegalStateException("injected postpublish failure");
            }
        };

        MountHandle parent = publicRuntime.advanced().transact(
                transaction -> transaction.mount(
                        publicRuntime.root(),
                        "parent",
                        MountFactory.of("parent",
                                ComponentDescriptor.named("parent"),
                                context -> {
                                    startEntered.countDown();
                                    releaseStart.await();
                                    context.provide(key, "value");
                                    childId.set(context.mountChild(
                                            "child",
                                            idleFactory("child")).handleId());
                                }))).value();
        // start() 被门控，transact 返回后可确定性捕获仍在 pending 的原始过渡 future。
        assertTrue(startEntered.await(10, TimeUnit.SECONDS));
        CompletableFuture<ComponentState> transition =
                parent.whenSettled().toCompletableFuture();
        assertFalse(transition.isDone());
        releaseStart.countDown();

        ExecutionException failure = assertThrows(
                ExecutionException.class,
                () -> transition.get(10, TimeUnit.SECONDS));
        assertTrue(rootCause(failure) instanceof IllegalStateException,
                () -> String.valueOf(failure.getCause()));
        assertTrue(rootCause(failure).getMessage()
                        .contains("injected postpublish failure"),
                () -> String.valueOf(failure.getCause()));

        // 已提交结构不得回滚：owner 保持 ACTIVE，注册与 owned child 仍在同代结构里。
        awaitState(childId.get(), ComponentState.ACTIVE);
        PublishedKernelState state = runtime.publishedState();
        state.validateInvariants();
        assertEquals(ComponentState.ACTIVE,
                state.view.components.get(parent.handleId()).state());
        assertTrue(hasActivationRegistration(state, key.name()));
        assertNotNull(state.view.components.get(childId.get()));
        assertNotNull(state.index.components.get(childId.get()));
        assertNotNull(state.index.componentHandles.get(childId.get()));

        // 注入点之后的每个观察代际都必须保留已提交资源，且不得出现回滚 STOPPING。
        boolean committedSeen = false;
        for (PublishedKernelState observed : generations) {
            if (hasActivationRegistration(observed, key.name())) {
                committedSeen = true;
                assertNotNull(observed.view.components.get(childId.get()));
                assertNotNull(observed.index.components.get(childId.get()));
            }
            if (committedSeen) {
                RuntimeView.ComponentData owner =
                        observed.view.components.get(parent.handleId());
                assertNotNull(owner, "committed owner must never be unpublished");
                assertTrue(owner.state() != ComponentState.STOPPING,
                        "committed owner rolled back to STOPPING");
            }
        }
        assertTrue(committedSeen, "probe never observed the committed generation");
    }

    @Test
    void prepublishProbeFailurePublishesNoChildOrRegistration() throws Exception {
        CapabilityKey<String> key =
                CapabilityKey.of("prepublish-capability", String.class);
        AtomicReference<String> childId = new AtomicReference<>();
        runtime.activationCoordinator().activationPrepublishProbe = () -> {
            throw new IllegalStateException("injected prepublish failure");
        };

        MountHandle parent = publicRuntime.advanced().transact(
                transaction -> transaction.mount(
                        publicRuntime.root(),
                        "parent",
                        parentProviding(key, "child", childId))).value();
        assertEquals(ComponentState.FAILED, parent.whenSettled()
                .toCompletableFuture().get(10, TimeUnit.SECONDS));

        PublishedKernelState state = runtime.publishedState();
        state.validateInvariants();
        assertFalse(hasActivationRegistration(state, key.name()));
        assertFalse(state.view.components.containsKey(childId.get()));
        assertFalse(state.index.components.containsKey(childId.get()));
    }

    @Test
    void startErrorPublishesNoChildOrRegistration() throws Exception {
        CapabilityKey<String> key =
                CapabilityKey.of("start-error-capability", String.class);
        AtomicReference<String> childId = new AtomicReference<>();

        MountHandle parent = publicRuntime.advanced().transact(
                transaction -> transaction.mount(
                        publicRuntime.root(),
                        "parent",
                        MountFactory.of("parent",
                                ComponentDescriptor.named("parent"),
                                context -> {
                                    context.provide(key, "value");
                                    childId.set(context.mountChild("child",
                                            idleFactory("child")).handleId());
                                    throw new IllegalStateException("user start failed");
                                }))).value();
        assertEquals(ComponentState.FAILED, parent.whenSettled()
                .toCompletableFuture().get(10, TimeUnit.SECONDS));

        PublishedKernelState state = runtime.publishedState();
        state.validateInvariants();
        assertFalse(hasActivationRegistration(state, key.name()));
        assertFalse(state.view.components.containsKey(childId.get()));
        assertFalse(state.index.components.containsKey(childId.get()));
    }

    @Test
    void cycleRejectedStartKeepsExplicitRetrySemantics() throws Exception {
        CapabilityKey<String> a =
                CapabilityKey.of("cycle-a", String.class);
        CapabilityKey<String> b =
                CapabilityKey.of("cycle-b", String.class);
        publicRuntime.advanced().transact(transaction -> transaction.provide(
                publicRuntime.root(), a, "root-a"));
        ContextHandle cycleContext = publicRuntime.advanced().transact(
                transaction -> transaction.childContext(
                        publicRuntime.root(), "cycle-workspace")).value();
        MountHandle provider = publicRuntime.advanced().transact(
                transaction -> transaction.mount(
                        cycleContext,
                        "provider",
                        MountFactory.of("provider",
                                ComponentDescriptor.named(
                                        "provider",
                                        CapabilityRequirement.required(a)),
                                context -> {
                                    context.require(a);
                                    context.provide(b, "provider-b");
                                }))).value();
        assertEquals(ComponentState.ACTIVE, provider.whenSettled()
                .toCompletableFuture().get(10, TimeUnit.SECONDS));

        MountHandle cyclic = publicRuntime.advanced().transact(
                transaction -> transaction.mount(
                        cycleContext,
                        "cyclic",
                        MountFactory.of("cyclic",
                                ComponentDescriptor.named(
                                        "cyclic",
                                        CapabilityRequirement.required(b)),
                                context -> {
                                    context.require(b);
                                    context.provide(a, "cyclic-a");
                                }))).value();
        assertEquals(ComponentState.WAITING, cyclic.whenSettled()
                .toCompletableFuture().get(10, TimeUnit.SECONDS));
        ComponentRuntime cyclicRuntime = runtime.publishedState().index.components
                .get(cyclic.handleId());
        assertFalse(cyclicRuntime.pendingStartFailure(),
                "cycle rejection must stay suppressed until topology changes");
        assertTrue(cyclicRuntime.suppressAutoRestart(),
                "cycle rejection must suppress automatic restart");
    }

    @Test
    void postpublishFailureStillDrivesShadowedConsumers() throws Exception {
        CapabilityKey<String> key =
                CapabilityKey.of("dirty-shadow-capability", String.class);
        publicRuntime.advanced().transact(transaction -> transaction.provide(
                publicRuntime.root(), key, "host"));
        ContextHandle consumerContext = publicRuntime.advanced().transact(
                transaction -> transaction.childContext(
                        publicRuntime.root(), "shadow-workspace")).value();
        MountHandle first = publicRuntime.advanced().transact(
                transaction -> transaction.mount(
                        consumerContext,
                        "first",
                        consumerFactory("first", key))).value();
        MountHandle second = publicRuntime.advanced().transact(
                transaction -> transaction.mount(
                        consumerContext,
                        "second",
                        consumerFactory("second", key))).value();
        assertEquals(ComponentState.ACTIVE, first.whenSettled()
                .toCompletableFuture().get(10, TimeUnit.SECONDS));
        assertEquals(ComponentState.ACTIVE, second.whenSettled()
                .toCompletableFuture().get(10, TimeUnit.SECONDS));

        AtomicBoolean injected = new AtomicBoolean();
        CountDownLatch shadowStartEntered = new CountDownLatch(1);
        CountDownLatch releaseShadowStart = new CountDownLatch(1);
        runtime.activationCoordinator().transitionPublicationProbe = () -> {
            PublishedKernelState state = runtime.publishedState();
            if (activationOwnerHandle(state, key.name()) != null
                    && !injected.getAndSet(true)) {
                throw new IllegalStateException("injected dirty postpublish failure");
            }
        };

        MountHandle shadow = publicRuntime.advanced().transact(
                transaction -> transaction.mount(
                        consumerContext,
                        "shadow",
                        MountFactory.of("shadow",
                                ComponentDescriptor.named("shadow"),
                                context -> {
                                    shadowStartEntered.countDown();
                                    releaseShadowStart.await();
                                    context.provide(key, "shadow");
                                }))).value();
        assertTrue(shadowStartEntered.await(10, TimeUnit.SECONDS));
        CompletableFuture<ComponentState> shadowTransition =
                shadow.whenSettled().toCompletableFuture();
        assertFalse(shadowTransition.isDone());
        releaseShadowStart.countDown();

        ExecutionException failure = assertThrows(
                ExecutionException.class,
                () -> shadowTransition.get(10, TimeUnit.SECONDS));
        assertTrue(rootCause(failure).getMessage()
                .contains("injected dirty postpublish failure"));

        // 两个被遮蔽的 dirty reservation 必须照常驱动并收敛，而不是永久 pending。
        awaitState(first.handleId(), ComponentState.ACTIVE);
        awaitState(second.handleId(), ComponentState.ACTIVE);
        assertEquals(ComponentState.ACTIVE, runtime.publishedState()
                .view.components.get(shadow.handleId()).state());
    }

    @Test
    void finalPublishFaultCancelsCreatedReservationsWithoutLeakingSlots()
            throws Exception {
        CapabilityKey<String> key =
                CapabilityKey.of("final-publish-fault-capability", String.class);
        publicRuntime.advanced().transact(transaction -> transaction.provide(
                publicRuntime.root(), key, "host"));
        ContextHandle consumerContext = publicRuntime.advanced().transact(
                transaction -> transaction.childContext(
                        publicRuntime.root(), "final-publish-workspace")).value();
        MountHandle first = publicRuntime.advanced().transact(
                transaction -> transaction.mount(
                        consumerContext,
                        "first",
                        consumerFactory("first", key))).value();
        MountHandle second = publicRuntime.advanced().transact(
                transaction -> transaction.mount(
                        consumerContext,
                        "second",
                        consumerFactory("second", key))).value();
        awaitState(first.handleId(), ComponentState.ACTIVE);
        awaitState(second.handleId(), ComponentState.ACTIVE);

        AtomicBoolean injected = new AtomicBoolean();
        runtime.activationCoordinator().activationFinalPublishProbe = () -> {
            if (!injected.getAndSet(true)) {
                throw new IllegalStateException("injected final publish fault");
            }
        };

        MountHandle parent = publicRuntime.advanced().transact(
                transaction -> transaction.mount(
                        consumerContext,
                        "parent",
                        MountFactory.of("parent",
                                ComponentDescriptor.named("parent"),
                                context -> context.provide(key, "parent"))))
                .value();
        assertEquals(ComponentState.FAILED, parent.whenSettled()
                .toCompletableFuture().get(10, TimeUnit.SECONDS));

        PublishedKernelState state = runtime.publishedState();
        state.validateInvariants();
        // 首候选的成功注册从未发布；owner 走中止清理收敛到 FAILED。
        assertFalse(state.view.registrations.values().stream().anyMatch(
                registration -> registration.key().name().equals(key.name())
                        && registration.contextId().equals(consumerContext.contextId())));
        // 被遮蔽组件的首候选预约已取消：槽位释放后 observer 立即收敛，而不是永久 pending。
        assertEquals(ComponentState.ACTIVE, first.whenSettled()
                .toCompletableFuture().get(10, TimeUnit.SECONDS));
        assertEquals(ComponentState.ACTIVE, second.whenSettled()
                .toCompletableFuture().get(10, TimeUnit.SECONDS));
    }

    @Test
    void postPublishEffectFaultKeepsCommittedStructureAndFailsFuture()
            throws Exception {
        CapabilityKey<String> key =
                CapabilityKey.of("effect-fault-capability", String.class);
        AtomicReference<String> childId = new AtomicReference<>();
        AtomicBoolean injected = new AtomicBoolean();
        runtime.activationCoordinator().activationPostPublishEffectProbe = () -> {
            if (!injected.getAndSet(true)) {
                throw new IllegalStateException("injected effect fault");
            }
        };

        MountHandle parent = publicRuntime.advanced().transact(
                transaction -> transaction.mount(
                        publicRuntime.root(),
                        "parent",
                        parentProviding(key, "child", childId))).value();
        CompletableFuture<ComponentState> transition =
                parent.whenSettled().toCompletableFuture();

        ExecutionException failure = assertThrows(
                ExecutionException.class,
                () -> transition.get(10, TimeUnit.SECONDS));
        assertTrue(rootCause(failure).getMessage().contains("injected effect fault"),
                () -> String.valueOf(failure.getCause()));
        assertTrue(rootCause(failure).getMessage()
                .contains("activation postcommit failed after publish"),
                () -> String.valueOf(failure.getCause()));

        // final publish 已不可逆：注册与 owned child 保持在同代已提交结构里。
        awaitState(childId.get(), ComponentState.ACTIVE);
        PublishedKernelState state = runtime.publishedState();
        state.validateInvariants();
        assertEquals(ComponentState.ACTIVE,
                state.view.components.get(parent.handleId()).state());
        assertTrue(hasActivationRegistration(state, key.name()));
        assertNotNull(state.view.components.get(childId.get()));
        assertNotNull(state.index.components.get(childId.get()));
    }

    @Test
    void nthReservationFaultCancelsPartialReservationsAndObserverCompletes()
            throws Exception {
        CapabilityKey<String> key =
                CapabilityKey.of("reservation-fault-capability", String.class);
        publicRuntime.advanced().transact(transaction -> transaction.provide(
                publicRuntime.root(), key, "host"));
        ContextHandle consumerContext = publicRuntime.advanced().transact(
                transaction -> transaction.childContext(
                        publicRuntime.root(), "reservation-fault-workspace")).value();
        MountHandle first = publicRuntime.advanced().transact(
                transaction -> transaction.mount(
                        consumerContext,
                        "first",
                        consumerFactory("first", key))).value();
        MountHandle second = publicRuntime.advanced().transact(
                transaction -> transaction.mount(
                        consumerContext,
                        "second",
                        consumerFactory("second", key))).value();
        awaitState(first.handleId(), ComponentState.ACTIVE);
        awaitState(second.handleId(), ComponentState.ACTIVE);

        CountDownLatch reservationReached = new CountDownLatch(1);
        CountDownLatch observerCaptured = new CountDownLatch(1);
        AtomicReference<CompletableFuture<ComponentState>> captured =
                new AtomicReference<>();
        AtomicBoolean injected = new AtomicBoolean();
        runtime.activationCoordinator().scheduler().transitionReservationFaultProbe = index -> {
            if (index == 1 && !injected.getAndSet(true)) {
                reservationReached.countDown();
                try {
                    observerCaptured.await();
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("capture interrupted", error);
                }
                throw new IllegalStateException("injected reservation fault");
            }
        };

        MountHandle parent = publicRuntime.advanced().transact(
                transaction -> transaction.mount(
                        consumerContext,
                        "parent",
                        MountFactory.of("parent",
                                ComponentDescriptor.named("parent"),
                                context -> context.provide(key, "parent"))))
                .value();

        assertTrue(reservationReached.await(10, TimeUnit.SECONDS));
        CompletableFuture<ComponentState> firstObserver =
                first.whenSettled().toCompletableFuture();
        CompletableFuture<ComponentState> secondObserver =
                second.whenSettled().toCompletableFuture();
        if (!firstObserver.isDone()) {
            captured.set(firstObserver);
        } else if (!secondObserver.isDone()) {
            captured.set(secondObserver);
        }
        observerCaptured.countDown();

        assertEquals(ComponentState.FAILED, parent.whenSettled()
                .toCompletableFuture().get(10, TimeUnit.SECONDS));
        CompletableFuture<ComponentState> observer = captured.get();
        assertNotNull(observer, "expected one shadowed consumer to hold a pending reservation");
        ExecutionException cancelled = assertThrows(
                ExecutionException.class,
                () -> observer.get(10, TimeUnit.SECONDS));
        assertTrue(rootCause(cancelled) instanceof TransitionCancelledStateException,
                () -> String.valueOf(cancelled.getCause()));

        // 中途失败不泄漏槽位：两个消费者都保持可观察收敛。
        assertEquals(ComponentState.ACTIVE, first.whenSettled()
                .toCompletableFuture().get(10, TimeUnit.SECONDS));
        assertEquals(ComponentState.ACTIVE, second.whenSettled()
                .toCompletableFuture().get(10, TimeUnit.SECONDS));
        PublishedKernelState state = runtime.publishedState();
        state.validateInvariants();
        assertFalse(state.view.registrations.values().stream().anyMatch(
                registration -> registration.key().name().equals(key.name())
                        && registration.contextId().equals(consumerContext.contextId())));
    }

    @Test
    void emergencyRollbackPreservesSuppressAutoRestart() throws Exception {
        CountDownLatch startEntered = new CountDownLatch(1);
        CompletableFuture<Void> releaseStart = new CompletableFuture<>();
        MountHandle handle = publicRuntime.advanced().transact(
                transaction -> transaction.mount(
                        publicRuntime.root(),
                        "emergency-suppress",
                        MountFactory.of("emergency-suppress-factory",
                                ComponentDescriptor.named("emergency-suppress"),
                                context -> {
                                    startEntered.countDown();
                                    releaseStart.get(10, TimeUnit.SECONDS);
                                }))).value();
        assertTrue(startEntered.await(10, TimeUnit.SECONDS));
        ComponentRuntime component = runtime.publishedState()
                .index.components.get(handle.handleId());
        synchronized (runtime.coordinator) {
            component.suppressAutoRestartLocked(true);
        }

        java.util.concurrent.atomic.AtomicInteger attempts =
                new java.util.concurrent.atomic.AtomicInteger();
        runtime.activationCoordinator().scheduler().transitionReservationProbe = () -> {
            if (attempts.incrementAndGet() < 3) {
                throw new IllegalStateException("injected candidate preparation failure");
            }
            runtime.activationCoordinator().scheduler().transitionReservationProbe = null;
        };
        releaseStart.complete(null);

        assertTrue(rootCause(assertThrows(ExecutionException.class, () ->
                        handle.whenSettled().toCompletableFuture().get(10, TimeUnit.SECONDS)))
                .getMessage().contains("emergency activation rollback"));
        assertTrue(component.suppressAutoRestart(),
                "emergency rollback must not reset cycle suppression");
        assertEquals(ComponentState.FAILED, handle.state());
    }
    private static MountFactory parentProviding(
            CapabilityKey<String> key,
            String childMountId,
            AtomicReference<String> childId) {
        return MountFactory.of("parent",
                ComponentDescriptor.named("parent"),
                context -> {
                    context.provide(key, "value");
                    childId.set(context.mountChild(
                            childMountId,
                            idleFactory(childMountId)).handleId());
                });
    }

    private static MountFactory consumerFactory(String id, CapabilityKey<String> key) {
        return MountFactory.of(id,
                ComponentDescriptor.named(id, CapabilityRequirement.required(key)),
                context -> {
                });
    }

    private static MountFactory idleFactory(String id) {
        return MountFactory.of(id, ComponentDescriptor.named(id), context -> {
        });
    }

    private static boolean hasActivationRegistration(
            PublishedKernelState state,
            String keyName) {
        return state.view.registrations.values().stream().anyMatch(registration ->
                registration.key().name().equals(keyName)
                        && registration.owner()
                                instanceof RuntimeView.OwnerData.Activation);
    }

    private static String activationOwnerHandle(
            PublishedKernelState state,
            String keyName) {
        return state.view.registrations.values().stream()
                .filter(registration -> registration.key().name().equals(keyName)
                        && registration.owner() instanceof RuntimeView.OwnerData.Activation)
                .map(registration -> state.view.activations.get(
                        ((RuntimeView.OwnerData.Activation) registration.owner())
                                .activationId()))
                .filter(Objects::nonNull)
                .map(RuntimeView.ActivationData::handleId)
                .findFirst()
                .orElse(null);
    }
    private static Throwable rootCause(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current != current.getCause()) {
            current = current.getCause();
        }
        return current;
    }

    private void awaitState(String handleId, ComponentState expected) {
        deadlineSpin(10_000, () -> {
            RuntimeView.ComponentData data =
                    runtime.publishedState().view.components.get(handleId);
            return data != null && data.state() == expected;
        }, () -> "handle " + handleId + " never reached " + expected + ": "
                + runtime.pendingOperations());
    }

    private static void deadlineSpin(
            long timeoutMillis,
            java.util.function.BooleanSupplier condition,
            java.util.function.Supplier<String> message) {
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new AssertionError(message.get(), error);
            }
        }
        throw new AssertionError(message.get());
    }
}
