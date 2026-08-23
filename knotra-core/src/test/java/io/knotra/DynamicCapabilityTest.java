package io.knotra;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class DynamicCapabilityTest {

    static final CapabilityKey<Api> API = CapabilityKey.of("api", Api.class);

    KnotraRuntime runtime = KnotraRuntime.create();

    @AfterEach
    void tearDown() {
        runtime.close();
    }

    interface Api {
        String value();

        CompletionStage<String> valueAsync();

        default String failing() {
            throw new IllegalStateException("provider failure");
        }
    }

    interface ExtendedApi extends Api {
        String extra();
    }

    static final class ApiValue implements Api {
        final String value;

        ApiValue(String value) {
            this.value = value;
        }

        @Override
        public String value() {
            return value;
        }

        @Override
        public CompletionStage<String> valueAsync() {
            return CompletableFuture.completedFuture(value);
        }
    }

    record ProviderConfig(String value) {
    }

    @Test
    void dynamicRequirementsUseSubscribeAndDoNotUsePinnedAccess() throws Exception {
        AtomicReference<RuntimeException> requireError = new AtomicReference<>();
        AtomicReference<RuntimeException> findError = new AtomicReference<>();
        AtomicReference<DynamicCapability<Api>> dynamic = new AtomicReference<>();

        var handle = mount("consumer", (context, config) -> {
            requireError.set(catchRuntime(() -> context.require(API)));
            findError.set(catchRuntime(() -> context.find(API)));
            dynamic.set(context.subscribe(API));
        }, CapabilityRequirement.dynamicOptional(API));

        assertEquals(ComponentState.ACTIVE, TestKit.settle(handle).call());
        assertInstanceOf(IllegalArgumentException.class, requireError.get());
        assertInstanceOf(IllegalArgumentException.class, findError.get());
        assertNotNull(dynamic.get());
        assertFalse(dynamic.get().available());

        var component = TestKit.component(runtime, handle);
        assertEquals(CapabilityRequirement.CapabilityBinding.DYNAMIC,
                component.requirements().getFirst().binding());
        var binding = runtime.advanced().snapshot().activations().getFirst().bindings().getFirst();
        assertNull(binding.registrationId());
        assertFalse(binding.present());
        assertEquals(CapabilityRequirement.CapabilityBinding.DYNAMIC, binding.binding());
    }

    @Test
    void pinnedRequirementsStillUseCapturedValuesAndRestart() throws Exception {
        AtomicInteger starts = new AtomicInteger();
        AtomicReference<Api> captured = new AtomicReference<>();
        var handle = mount("pinned", (context, config) -> {
            starts.incrementAndGet();
            captured.set(context.require(API));
        }, CapabilityRequirement.required(API));

        var first = provide(new ApiValue("v1"));
        assertEquals(ComponentState.ACTIVE, TestKit.settle(handle).call());
        assertSame(runtime.root().view().require(API), captured.get());

        runtime.advanced().transact(transaction -> {
            transaction.revoke(first);
            transaction.provide(runtime.root(), API, new ApiValue("v2"));
            return null;
        }).settlement().whenSettled().toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertEquals(ComponentState.ACTIVE, TestKit.settle(handle).call());

        assertEquals(2, starts.get());
        assertEquals("v2", captured.get().value());
    }

    @Test
    void dynamicRequiredWaitsOnlyForFirstProvider() throws Exception {
        AtomicInteger starts = new AtomicInteger();
        AtomicReference<DynamicCapability<Api>> dynamic = new AtomicReference<>();
        var handle = mount("consumer", (context, config) -> {
            starts.incrementAndGet();
            dynamic.set(context.subscribe(API));
        }, CapabilityRequirement.dynamicRequired(API));

        assertEquals(ComponentState.WAITING,
                handle.whenSettled().toCompletableFuture().get(10, TimeUnit.SECONDS));
        assertEquals(0, starts.get());

        var first = provide(new ApiValue("v1"));
        assertEquals(ComponentState.ACTIVE, TestKit.settle(handle).call());
        assertEquals("v1", dynamic.get().call(Api::value));

        runtime.advanced().transact(transaction -> {
            transaction.revoke(first);
            return null;
        }).awaitSettled(Duration.ofSeconds(10));
        assertEquals(ComponentState.ACTIVE,
                handle.whenSettled().toCompletableFuture().get(10, TimeUnit.SECONDS));
        assertFalse(dynamic.get().available());
        CapabilityUnavailableException unavailable = assertThrows(
                CapabilityUnavailableException.class,
                () -> dynamic.get().call(Api::value));
        assertEquals(API, unavailable.key());

        provide(new ApiValue("v2"));
        assertEquals(ComponentState.ACTIVE,
                handle.whenSettled().toCompletableFuture().get(10, TimeUnit.SECONDS));
        assertEquals("v2", dynamic.get().call(Api::value));
        assertEquals(1, starts.get());
    }

    @Test
    void dynamicRequiredRollsBackWhenProviderDisappearsDuringStart() throws Exception {
        RegistrationHandle registration = provide(new ApiValue("v1"));
        CountDownLatch entered = new CountDownLatch(1);
        CompletableFuture<Void> release = new CompletableFuture<>();
        AtomicReference<DynamicCapability<Api>> dynamic = new AtomicReference<>();
        MountHandle handle = TestKit.mount(
                runtime,
                runtime.root(),
                "starting-dynamic-required",
                "starting-dynamic-required",
                (context, config) -> {
                    dynamic.set(context.subscribe(API));
                    entered.countDown();
                    release.join();
                },
                CapabilityRequirement.dynamicRequired(API));

        assertTrue(entered.await(10, TimeUnit.SECONDS));
        runtime.advanced().transact(transaction -> {
            transaction.revoke(registration);
            return null;
        });
        release.complete(null);

        assertEquals(ComponentState.WAITING, TestKit.settle(handle).call());
        DynamicCapabilityClosedException closed = assertThrows(
                DynamicCapabilityClosedException.class,
                () -> dynamic.get().call(Api::value));
        assertEquals(API.name(), closed.capabilityName());
        assertTrue(runtime.advanced().snapshot().diagnostics().stream().noneMatch(diagnostic ->
                diagnostic.code() == DiagnosticCode.ACTIVATION_FAILED
                        && diagnostic.targetId().equals(handle.handleId())));
    }

    @Test
    void blockedCallPinsOneProviderAcrossAtomicReplacement() throws Exception {
        AtomicReference<DynamicCapability<Api>> dynamic = new AtomicReference<>();
        mount("consumer", (context, config) -> dynamic.set(context.subscribe(API)),
                CapabilityRequirement.dynamicOptional(API));
        var old = provide(new ApiValue("v1"));

        CountDownLatch entered = new CountDownLatch(1);
        CompletableFuture<String> release = new CompletableFuture<>();
        CompletableFuture<String> call = CompletableFuture.supplyAsync(() ->
                dynamic.get().call(api -> {
                    String first = api.value();
                    entered.countDown();
                    release.join();
                    return first + "|" + api.value();
                }));
        assertTrue(entered.await(10, TimeUnit.SECONDS));
        TransactionReceipt<Void> receipt = runtime.advanced().transact(transaction -> {
            transaction.revoke(old);
            transaction.provide(runtime.root(), API, new ApiValue("v2"));
            return null;
        });
        assertFalse(receipt.settlement().whenSettled().toCompletableFuture().isDone());
        assertEquals("v2", dynamic.get().call(Api::value));

        release.complete(null);
        assertEquals("v1|v1", call.get(10, TimeUnit.SECONDS));
        receipt.settlement().whenSettled().toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertEquals("v2", dynamic.get().call(Api::value));
    }

    @Test
    void proxyUsesIdentityObjectMethodsAndUnwrapsProviderFailures() throws Exception {
        AtomicReference<DynamicCapability<Api>> dynamic = new AtomicReference<>();
        mount("consumer", (context, config) -> dynamic.set(context.subscribe(API)),
                CapabilityRequirement.dynamicOptional(API));
        provide(new ApiValue("v1"));
        Api proxy = dynamic.get().proxy(Api.class);

        assertEquals("v1", proxy.value());
        assertEquals(proxy, proxy);
        assertEquals(proxy.hashCode(), proxy.hashCode());
        assertTrue(proxy.toString().contains(Api.class.getName()));
        IllegalStateException error = assertThrows(IllegalStateException.class, proxy::failing);
        assertEquals("provider failure", error.getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> dynamic.get().proxy(ExtendedApi.class));
    }

    @Test
    void asyncCallHoldsLeaseUntilReturnedStageCompletes() throws Exception {
        AtomicReference<DynamicCapability<Api>> dynamic = new AtomicReference<>();
        mount("consumer", (context, config) -> dynamic.set(context.subscribe(API)),
                CapabilityRequirement.dynamicOptional(API));
        var registration = provide(new ApiValue("v1"));

        CompletableFuture<String> stage = new CompletableFuture<>();
        CompletionStage<String> result = dynamic.get().callAsync(api -> stage);
        TransactionReceipt<Void> receipt = runtime.advanced().transact(transaction -> {
            transaction.revoke(registration);
            return null;
        });
        assertFalse(result.toCompletableFuture().isDone());
        assertFalse(receipt.settlement().whenSettled().toCompletableFuture().isDone());

        stage.complete("done");
        assertEquals("done", result.toCompletableFuture().get(10, TimeUnit.SECONDS));
        receipt.settlement().whenSettled().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @Test
    @SuppressWarnings("unchecked")
    void asyncCompositionFailureReleasesProviderLease() throws Exception {
        AtomicReference<DynamicCapability<Api>> dynamic = new AtomicReference<>();
        mount("composition-consumer",
                (context, config) -> dynamic.set(context.subscribe(API)),
                CapabilityRequirement.dynamicOptional(API));
        RegistrationHandle registration = provide(new ApiValue("v1"));
        CompletionStage<String> broken = (CompletionStage<String>) Proxy.newProxyInstance(
                CompletionStage.class.getClassLoader(),
                new Class<?>[] {CompletionStage.class},
                (proxy, method, arguments) -> {
                    throw new UnsupportedOperationException("cannot compose");
                });

        CompletionStage<String> result = dynamic.get().callAsync(api -> broken);
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> result.toCompletableFuture().get(10, TimeUnit.SECONDS));
        assertInstanceOf(UnsupportedOperationException.class, failure.getCause());

        TransactionReceipt<Void> receipt = runtime.advanced().transact(transaction -> {
            transaction.revoke(registration);
            return null;
        });
        receipt.settlement().whenSettled().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @Test
    void dynamicEdgesParticipateInCycleDetection() throws Exception {
        CapabilityKey<String> a = CapabilityKey.of("dynamic-cycle-a", String.class);
        CapabilityKey<String> b = CapabilityKey.of("dynamic-cycle-b", String.class);
        MountHandle first = TestKit.mount(
                runtime,
                runtime.root(),
                "dynamic-cycle-first",
                "dynamic-cycle-first",
                (context, config) -> {
                    context.subscribe(b);
                    context.provide(a, "a");
                },
                CapabilityRequirement.dynamicOptional(b));
        assertEquals(ComponentState.ACTIVE, TestKit.settle(first).call());

        MountHandle second = TestKit.mount(
                runtime,
                runtime.root(),
                "dynamic-cycle-second",
                "dynamic-cycle-second",
                (context, config) -> {
                    context.require(a);
                    context.provide(b, "b");
                },
                CapabilityRequirement.required(a));

        assertEquals(ComponentState.WAITING, TestKit.settle(second).call(),
                () -> runtime.advanced().snapshot().toString());
        assertTrue(runtime.advanced().snapshot().diagnostics().stream().anyMatch(diagnostic ->
                diagnostic.code() == DiagnosticCode.BINDING_CYCLE
                        && diagnostic.targetId().equals(second.handleId())));
    }

    @Test
    void activeDynamicConsumerDoesNotRestartForProviderReplacement() throws Exception {
        AtomicInteger starts = new AtomicInteger();
        AtomicReference<DynamicCapability<Api>> dynamic = new AtomicReference<>();
        mount("replacement-consumer", (context, config) -> {
            starts.incrementAndGet();
            dynamic.set(context.subscribe(API));
        }, CapabilityRequirement.dynamicOptional(API));

        CountDownLatch staged = new CountDownLatch(1);
        CompletableFuture<Void> release = new CompletableFuture<>();
        var provider = mountProvider(
                "replacement-provider", new ProviderConfig("v1"), staged, release);
        assertTrue(staged.await(10, TimeUnit.SECONDS));
        release.complete(null);
        assertEquals(ComponentState.ACTIVE, TestKit.settle(provider).call());
        assertEquals("v1", dynamic.get().call(Api::value));

        provider.reconfigureAsync(new ProviderConfig("v2"))
                .toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertEquals(ComponentState.ACTIVE, TestKit.settle(provider).call());
        assertEquals("v2", dynamic.get().call(Api::value));
        assertEquals(1, starts.get());
    }


    @Test
    void proxyAsyncMethodHoldsLeaseUntilCompletion() throws Exception {
        AtomicReference<DynamicCapability<Api>> dynamic = new AtomicReference<>();
        mount("consumer", (context, config) -> dynamic.set(context.subscribe(API)),
                CapabilityRequirement.dynamicOptional(API));
        var registration = provide(new CompletionStageApi("v1"));

        Api proxy = dynamic.get().proxy(Api.class);
        CompletableFuture<String> stage = proxy.valueAsync().toCompletableFuture();
        var providerValue = runtime.root().view().require(API);
        TransactionReceipt<Void> receipt = runtime.advanced().transact(transaction -> {
            transaction.revoke(registration);
            return null;
        });
        assertFalse(receipt.settlement().whenSettled().toCompletableFuture().isDone());
        assertInstanceOf(CompletionStageApi.class, providerValue);
        ((CompletionStageApi) providerValue).release.complete(null);
        assertEquals("v1", stage.get(10, TimeUnit.SECONDS));
        receipt.settlement().whenSettled().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @Test
    void stagedProviderRegistrationIsNotVisibleUntilCommit() throws Exception {
        AtomicReference<DynamicCapability<Api>> dynamic = new AtomicReference<>();
        mount("consumer", (context, config) -> dynamic.set(context.subscribe(API)),
                CapabilityRequirement.dynamicOptional(API));

        CountDownLatch staged = new CountDownLatch(1);
        CompletableFuture<Void> release = new CompletableFuture<>();
        var provider = mountProvider("provider", new ProviderConfig("v1"), staged, release);

        assertTrue(staged.await(10, TimeUnit.SECONDS));
        assertEquals(ComponentState.STARTING, provider.state());
        assertFalse(dynamic.get().available());
        assertThrows(CapabilityUnavailableException.class, () -> dynamic.get().call(Api::value));
        release.complete(null);
        assertEquals(ComponentState.ACTIVE, TestKit.settle(provider).call());
        assertEquals("v1", dynamic.get().call(Api::value));
    }

    @Test
    void dynamicResolutionFollowsContextShadowingWithoutRestart() throws Exception {
        var child = TestKit.child(runtime, runtime.root(), "child");
        provide(new ApiValue("parent"));
        AtomicInteger starts = new AtomicInteger();
        AtomicReference<DynamicCapability<Api>> dynamic = new AtomicReference<>();
        var handle = TestKit.mount(runtime, child, "child-consumer", (context, config) -> {
            starts.incrementAndGet();
            dynamic.set(context.subscribe(API));
        }, CapabilityRequirement.dynamicOptional(API));
        assertEquals(ComponentState.ACTIVE, TestKit.settle(handle).call());
        assertEquals("parent", dynamic.get().call(Api::value));

        var shadow = runtime.advanced().transact(transaction ->
                transaction.provide(child, API, new ApiValue("child"))).value();
        assertEquals("child", dynamic.get().call(Api::value));
        runtime.advanced().transact(transaction -> {
            transaction.revoke(shadow);
            return null;
        }).awaitSettled(Duration.ofSeconds(10));
        assertEquals("parent", dynamic.get().call(Api::value));
        assertEquals(1, starts.get());
    }

    @Test
    void consumerStopWaitsForDynamicCallsAndRejectsNewCalls() throws Exception {
        AtomicReference<DynamicCapability<Api>> dynamic = new AtomicReference<>();
        List<String> events = new ArrayList<>();
        var handle = mount("consumer", (context, config) -> {
            dynamic.set(context.subscribe(API));
            context.lifecycle().onClose("consumer", () -> events.add("consumer-closed"));
        }, CapabilityRequirement.dynamicOptional(API));
        provide(new ApiValue("v1"));
        assertEquals(ComponentState.ACTIVE, TestKit.settle(handle).call());

        CountDownLatch entered = new CountDownLatch(1);
        CompletableFuture<Void> release = new CompletableFuture<>();
        CompletableFuture<String> call = CompletableFuture.supplyAsync(() ->
                dynamic.get().call(api -> {
                    entered.countDown();
                    release.join();
                    return api.value();
                }));
        assertTrue(entered.await(10, TimeUnit.SECONDS));

        var disposed = handle.disposeAsync().toCompletableFuture();
        assertFalse(disposed.isDone());
        assertThrows(DynamicCapabilityClosedException.class, () -> dynamic.get().call(Api::value));

        release.complete(null);
        assertEquals("v1", call.get(10, TimeUnit.SECONDS));
        assertEquals(ComponentState.DISPOSED, disposed.get(10, TimeUnit.SECONDS));
        assertEquals(List.of("consumer-closed"), events);
    }

    @Test
    void providerActivationCleanupWaitsForDynamicLease() throws Exception {
        AtomicReference<DynamicCapability<Api>> dynamic = new AtomicReference<>();
        mount("consumer", (context, config) -> dynamic.set(context.subscribe(API)),
                CapabilityRequirement.dynamicOptional(API));

        CountDownLatch staged = new CountDownLatch(1);
        CompletableFuture<Void> firstRelease = new CompletableFuture<>();
        List<String> events = new ArrayList<>();
        var provider = mountTrackedProvider("provider", "v1", staged, firstRelease, events);
        assertTrue(staged.await(10, TimeUnit.SECONDS));
        firstRelease.complete(null);
        assertEquals(ComponentState.ACTIVE, TestKit.settle(provider).call());
        assertEquals("v1", dynamic.get().call(Api::value));

        CountDownLatch callEntered = new CountDownLatch(1);
        CompletableFuture<Void> leaseRelease = new CompletableFuture<>();
        CompletableFuture<String> call = CompletableFuture.supplyAsync(() ->
                dynamic.get().call(api -> {
                    callEntered.countDown();
                    leaseRelease.join();
                    return api.value();
                }));
        assertTrue(callEntered.await(10, TimeUnit.SECONDS));

        var reconfigured = provider.reconfigureAsync(new ProviderConfig("v2"))
                .toCompletableFuture();
        assertFalse(reconfigured.isDone());
        leaseRelease.complete(null);
        assertEquals("v1", call.get(10, TimeUnit.SECONDS));
        assertEquals(ComponentState.ACTIVE, TestKit.settle(provider).call());
        assertEquals("v2", dynamic.get().call(Api::value));
        assertTrue(events.contains("v1-closed"));
    }

    @Test
    void runtimeCloseWaitsForDynamicCall() throws Exception {
        AtomicReference<DynamicCapability<Api>> dynamic = new AtomicReference<>();
        mount("consumer", (context, config) -> dynamic.set(context.subscribe(API)),
                CapabilityRequirement.dynamicOptional(API));
        provide(new ApiValue("v1"));

        CountDownLatch entered = new CountDownLatch(1);
        CompletableFuture<Void> release = new CompletableFuture<>();
        CompletableFuture<String> call = CompletableFuture.supplyAsync(() ->
                dynamic.get().call(api -> {
                    entered.countDown();
                    release.join();
                    return api.value();
                }));
        assertTrue(entered.await(10, TimeUnit.SECONDS));

        var closed = runtime.closeAsync().toCompletableFuture();
        assertFalse(closed.isDone());
        release.complete(null);
        assertEquals("v1", call.get(10, TimeUnit.SECONDS));
        closed.get(10, TimeUnit.SECONDS);
    }

    private RegistrationHandle provide(Api value) {
        return runtime.advanced().transact(transaction ->
                transaction.provide(runtime.root(), API, value)).value();
    }

    private MountHandle mount(
            String mountId,
            TestKit.Start<NoConfig> start,
            CapabilityRequirement... requirements) throws Exception {
        var handle = TestKit.mount(runtime, runtime.root(), mountId, mountId, start, requirements);
        TestKit.settle(handle).call();
        return handle;
    }

    private ConfiguredMountHandle<ProviderConfig> mountProvider(
            String mountId,
            ProviderConfig config,
            CountDownLatch staged,
            CompletableFuture<Void> release) {
        Component<ProviderConfig> component = new Component<>() {
            @Override
            public ComponentDescriptor descriptor() {
                return ComponentDescriptor.of();
            }

            @Override
            public void start(ActivationContext context, ProviderConfig configuration) {
                context.provide(API, new ApiValue(configuration.value()));
                staged.countDown();
                release.join();
            }
        };
        ComponentFactory<ProviderConfig> factory = TestKit.factory(mountId, component);
        return runtime.advanced().transact(transaction ->
                transaction.mount(runtime.root(), mountId, factory, config)).value();
    }

    private ConfiguredMountHandle<ProviderConfig> mountTrackedProvider(
            String mountId,
            String value,
            CountDownLatch staged,
            CompletableFuture<Void> release,
            List<String> events) {
        Component<ProviderConfig> component = new Component<>() {
            @Override
            public ComponentDescriptor descriptor() {
                return ComponentDescriptor.of();
            }

            @Override
            public void start(ActivationContext context, ProviderConfig configuration) {
                context.provide(API, new ApiValue(configuration.value()));
                staged.countDown();
                release.join();
                context.lifecycle().onClose(configuration.value() + "-closed",
                        () -> events.add(configuration.value() + "-closed"));
            }
        };
        ComponentFactory<ProviderConfig> factory = TestKit.factory(mountId, component);
        return runtime.advanced().transact(transaction ->
                transaction.mount(runtime.root(), mountId, factory,
                        new ProviderConfig(value))).value();
    }

    private static RuntimeException catchRuntime(Runnable action) {
        try {
            action.run();
            return null;
        } catch (RuntimeException error) {
            return error;
        }
    }

    static final class CompletionStageApi implements Api {
        final CompletableFuture<String> release = new CompletableFuture<>();
        final String value;

        CompletionStageApi(String value) {
            this.value = value;
        }

        @Override
        public String value() {
            return value;
        }

        @Override
        public CompletionStage<String> valueAsync() {
            return release.thenApply(ignored -> value);
        }
    }
}
