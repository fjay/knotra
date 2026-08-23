package io.knotra.internal;

import io.knotra.ActivationContext;
import io.knotra.CapabilityKey;
import io.knotra.CapabilityRequirement;
import io.knotra.Component;
import io.knotra.ComponentDescriptor;
import io.knotra.ComponentFactory;
import io.knotra.ComponentState;
import io.knotra.ConfiguredMountHandle;
import io.knotra.DynamicCapability;
import io.knotra.KnotraRuntime;
import io.knotra.MountFactory;
import io.knotra.MountHandle;
import io.knotra.PendingOperationsSnapshot;
import io.knotra.RegistrationHandle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ProviderLeaseLifecycleTest {
    private static final CapabilityKey<String> KEY =
            CapabilityKey.of("provider-lease-lifecycle", String.class);

    private final KnotraRuntime runtime = KnotraRuntime.create();
    private final DefaultKnotraRuntime internal = (DefaultKnotraRuntime) runtime;

    @AfterEach
    void tearDown() {
        internal.activationCoordinator().activationPostPublishEffectProbe = null;
        runtime.close();
    }

    @Test
    void providerLeaseIndexMembershipMatchesRegistrationsAcrossHostAndActivationPublishes()
            throws Exception {
        RegistrationHandle host = runtime.advanced().transact(transaction ->
                transaction.provide(runtime.root(), KEY, "host")).value();
        assertSameGenerationLeases(host.registrationId());

        io.knotra.ContextHandle activationContext = runtime.advanced().transact(transaction ->
                transaction.childContext(runtime.root(), "activation-workspace")).value();
        MountHandle provider = mount(
                activationContext,
                "activation-provider",
                context -> context.provide(KEY, "activation"));
        assertEquals(ComponentState.ACTIVE, settled(provider));
        String owned = activationRegistrationId();
        assertSameGenerationLeases(host.registrationId());
        assertSameGenerationLeases(owned);
        runtime.advanced().transact(transaction -> {
            transaction.revoke(host);
            return null;
        }).awaitSettled(Duration.ofSeconds(10));
        assertSameGenerationLeases(owned);

        provider.disposeAsync().toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertSameGenerationLeases(null);
    }

    @Test
    void activeLiveLeaseIsInvisibleUntilRetiredAndRetiredLeaseClearsOnRelease()
            throws Exception {
        AtomicReference<DynamicCapability<String>> dynamic = new AtomicReference<>();
        MountHandle consumer = mount(
                "dynamic-consumer",
                context -> dynamic.set(context.subscribe(KEY)),
                CapabilityRequirement.dynamicOptional(KEY));
        RegistrationHandle provider = runtime.advanced().transact(transaction ->
                transaction.provide(runtime.root(), KEY, "value")).value();
        assertEquals(ComponentState.ACTIVE, settled(consumer));

        CountDownLatch callEntered = new CountDownLatch(1);
        CompletableFuture<Void> release = new CompletableFuture<>();
        CompletableFuture<String> call = CompletableFuture.supplyAsync(() ->
                dynamic.get().call(value -> {
                    callEntered.countDown();
                    release.join();
                    return value;
                }));
        assertTrue(callEntered.await(10, TimeUnit.SECONDS));
        assertFalse(findProviderLease(runtime.advanced().pendingOperations(),
                provider.registrationId()).isPresent());

        var revoked = runtime.advanced().transact(transaction -> {
            transaction.revoke(provider);
            return null;
        });
        PendingOperationsSnapshot blocked = runtime.advanced().pendingOperations();
        PendingOperationsSnapshot.Operation operation = findProviderLease(
                        blocked, provider.registrationId())
                .orElseThrow();
        assertEquals(PendingOperationsSnapshot.WaitType.LEASE_RELEASE, operation.waitsFor());

        release.complete(null);
        assertEquals("value", call.get(10, TimeUnit.SECONDS));
        revoked.settlement().whenSettled().toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertFalse(findProviderLease(runtime.advanced().pendingOperations(),
                provider.registrationId()).isPresent());
    }

    @Test
    void activationPostPublishFailureStillRetiresRemovedProviderLease() throws Exception {
        AtomicReference<DynamicCapability<String>> dynamic = new AtomicReference<>();
        MountHandle consumer = mount(
                "replacement-consumer",
                context -> dynamic.set(context.subscribe(KEY)),
                CapabilityRequirement.dynamicOptional(KEY));
        ConfiguredMountHandle<String> provider = mountProvider("v1");
        assertEquals(ComponentState.ACTIVE, settled(consumer));
        assertEquals(ComponentState.ACTIVE, settled(provider));

        CountDownLatch callEntered = new CountDownLatch(1);
        CompletableFuture<Void> release = new CompletableFuture<>();
        CompletableFuture<String> call = CompletableFuture.supplyAsync(() ->
                dynamic.get().call(value -> {
                    callEntered.countDown();
                    release.join();
                    return value;
                }));
        assertTrue(callEntered.await(10, TimeUnit.SECONDS));
        String oldRegistrationId = onlyRegistrationId();

        AtomicBoolean injected = new AtomicBoolean();
        internal.activationCoordinator().activationPostPublishEffectProbe = () -> {
            if (!injected.getAndSet(true)) {
                throw new IllegalStateException("injected effect fault");
            }
        };
        var reconfigured = provider.reconfigureAsync("v2").toCompletableFuture();
        PendingOperationsSnapshot blocked = runtime.advanced().pendingOperations();
        PendingOperationsSnapshot.Operation operation = findProviderLease(
                        blocked, oldRegistrationId)
                .orElseThrow();
        assertEquals(PendingOperationsSnapshot.WaitType.LEASE_RELEASE, operation.waitsFor());

        release.complete(null);
        assertEquals("v1", call.get(10, TimeUnit.SECONDS));
        org.junit.jupiter.api.Assertions.assertThrows(
                java.util.concurrent.ExecutionException.class,
                () -> reconfigured.get(10, TimeUnit.SECONDS));
        assertEquals("v2", dynamic.get().call(value -> value));
        assertFalse(findProviderLease(runtime.advanced().pendingOperations(),
                oldRegistrationId).isPresent());
    }

    @Test
    void prepublishFailureDoesNotLeaveRetiredLeasePendingAfterConvergence() throws Exception {
        AtomicReference<DynamicCapability<String>> dynamic = new AtomicReference<>();
        mount(
                "prepublish-consumer",
                context -> dynamic.set(context.subscribe(KEY)),
                CapabilityRequirement.dynamicOptional(KEY));
        ConfiguredMountHandle<String> provider = mountProvider("v1");
        assertEquals(ComponentState.ACTIVE, settled(provider));

        CountDownLatch callEntered = new CountDownLatch(1);
        CompletableFuture<Void> release = new CompletableFuture<>();
        CompletableFuture<String> call = CompletableFuture.supplyAsync(() ->
                dynamic.get().call(value -> {
                    callEntered.countDown();
                    release.join();
                    return value;
                }));
        assertTrue(callEntered.await(10, TimeUnit.SECONDS));
        String registrationId = onlyRegistrationId();

        AtomicBoolean injected = new AtomicBoolean();
        internal.activationCoordinator().activationPrepublishProbe = () -> {
            if (!injected.getAndSet(true)) {
                throw new IllegalStateException("injected prepublish failure");
            }
        };
        var reconfigured = provider.reconfigureAsync("v2").toCompletableFuture();
        PendingOperationsSnapshot blocked = runtime.advanced().pendingOperations();
        PendingOperationsSnapshot.Operation retired =
                findProviderLease(blocked, registrationId).orElseThrow();
        assertEquals(PendingOperationsSnapshot.WaitType.LEASE_RELEASE, retired.waitsFor());

        release.complete(null);
        assertEquals("v1", call.get(10, TimeUnit.SECONDS));
        assertEquals(ComponentState.FAILED, reconfigured.get(10, TimeUnit.SECONDS));
        internal.activationCoordinator().activationPrepublishProbe = null;

        PublishedKernelState state = internal.publishedState();
        state.validateInvariants();
        assertFalse(state.view.registrations.containsKey(registrationId));
        assertFalse(findProviderLease(runtime.advanced().pendingOperations(), registrationId)
                .isPresent());
    }

    @Test
    void runtimeCloseReportsRemovedProviderLeaseUntilDrainCompletes() throws Exception {
        AtomicReference<DynamicCapability<String>> dynamic = new AtomicReference<>();
        mount(
                "close-consumer",
                context -> dynamic.set(context.subscribe(KEY)),
                CapabilityRequirement.dynamicOptional(KEY));
        RegistrationHandle provider = runtime.advanced().transact(transaction ->
                transaction.provide(runtime.root(), KEY, "value")).value();
        assertEquals(ComponentState.ACTIVE, settled(runtime.advanced().transact(
                transaction -> transaction.mount(
                        runtime.root(),
                        "close-waiter",
                        MountFactory.of(
                                "close-waiter",
                                ComponentDescriptor.named("close-waiter"),
                                context -> {
                                }))).value()));

        CountDownLatch callEntered = new CountDownLatch(1);
        CompletableFuture<Void> release = new CompletableFuture<>();
        CompletableFuture<String> call = CompletableFuture.supplyAsync(() ->
                dynamic.get().call(value -> {
                    callEntered.countDown();
                    release.join();
                    return value;
                }));
        assertTrue(callEntered.await(10, TimeUnit.SECONDS));

        CompletableFuture<Void> closed = runtime.closeAsync().toCompletableFuture();
        PendingOperationsSnapshot.Operation operation =
                awaitProviderLease(provider.registrationId());
        assertEquals(PendingOperationsSnapshot.WaitType.LEASE_RELEASE, operation.waitsFor());

        release.complete(null);
        assertEquals("value", call.get(10, TimeUnit.SECONDS));
        closed.get(10, TimeUnit.SECONDS);
        assertFalse(findProviderLease(runtime.advanced().pendingOperations(),
                provider.registrationId()).isPresent());
    }

    private PendingOperationsSnapshot.Operation awaitProviderLease(String registrationId)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Optional<PendingOperationsSnapshot.Operation> operation =
                    findProviderLease(runtime.advanced().pendingOperations(), registrationId);
            if (operation.isPresent()) {
                return operation.orElseThrow();
            }
            Thread.sleep(10);
        }
        throw new AssertionError("provider lease never became pending: " + registrationId);
    }

    private static Optional<PendingOperationsSnapshot.Operation> findProviderLease(
            PendingOperationsSnapshot snapshot,
            String registrationId) {
        return snapshot.operations().stream()
                .filter(operation -> operation.kind() == PendingOperationsSnapshot.Kind.PROVIDER_LEASE
                        && operation.targetId().equals(registrationId))
                .findFirst();
    }

    private ComponentState settled(MountHandle handle) throws Exception {
        return handle.whenSettled().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    private MountHandle mount(
            String mountId,
            MountFactory.Start start,
            CapabilityRequirement... requirements) {
        return mount(runtime.root(), mountId, start, requirements);
    }

    private MountHandle mount(
            io.knotra.ContextHandle context,
            String mountId,
            MountFactory.Start start,
            CapabilityRequirement... requirements) {
        return runtime.advanced().transact(transaction -> transaction.mount(
                context,
                mountId,
                MountFactory.of(
                        mountId,
                        ComponentDescriptor.named(mountId, requirements),
                        start))).value();
    }

    private ConfiguredMountHandle<String> mountProvider(String value) {
        ComponentFactory<String> factory = new ComponentFactory<>() {
            @Override
            public String factoryId() {
                return "configured-provider";
            }

            @Override
            public Component<String> create() {
                return new Component<>() {
                    @Override
                    public ComponentDescriptor descriptor() {
                        return ComponentDescriptor.named("configured-provider");
                    }

                    @Override
                    public void start(ActivationContext context, String config) {
                        context.provide(KEY, config);
                    }
                };
            }
        };
        return runtime.advanced().transact(transaction -> transaction.mount(
                runtime.root(),
                "configured-provider",
                factory,
                value)).value();
    }

    private void assertSameGenerationLeases(String expectedLiveId) {
        PublishedKernelState state = internal.publishedState();
        state.validateInvariants();
        assertEquals(state.view.registrations.keySet(), state.index.providerLeases.keySet());
        state.view.registrations.forEach((registrationId, registration) ->
                assertSame(registration.leases(), state.index.providerLeases.get(registrationId)));
        if (expectedLiveId != null) {
            assertTrue(state.view.registrations.containsKey(expectedLiveId));
        }
    }

    private String activationRegistrationId() {
        return internal.publishedState().view.registrations.entrySet().stream()
                .filter(entry -> entry.getValue().owner()
                        instanceof RuntimeView.OwnerData.Activation)
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow();
    }

    private String onlyRegistrationId() {
        return internal.publishedState().view.registrations.keySet().iterator().next();
    }
}
