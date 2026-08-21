package io.knotra.events;

import io.knotra.ActivationContext;
import io.knotra.CapabilityRequirement;
import io.knotra.Component;
import io.knotra.ComponentDescriptor;
import io.knotra.ComponentFactory;
import io.knotra.ComponentHandle;
import io.knotra.ComponentState;
import io.knotra.KnotraRuntime;
import io.knotra.MutationResult;
import io.knotra.NoConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class EventBusLifecycleTest {
    private static final EventDefinition<StringEvent> EVENTS =
            EventDefinition.sync(EventKey.of(StringEvent.class));

    private KnotraRuntime runtime = KnotraRuntime.create();

    @AfterEach
    void tearDown() throws Exception {
        runtime.close();
    }

    private ComponentHandle<NoConfig> mountBus(String mountId) {
        MutationResult<ComponentHandle<NoConfig>> result = runtime.mutate(mutation ->
                mutation.mount(runtime.rootContext(), mountId, new EventBusFactory(),
                        NoConfig.INSTANCE));
        assertTrue(result.committed(), () -> result.diagnostics().toString());
        return result.value();
    }

    private ComponentHandle<NoConfig> mountConsumer(
            String mountId,
            AtomicReference<EventBus> observedBus,
            AtomicInteger deliveries) {
        Component<NoConfig> component = new Component<>() {
            private final ComponentDescriptor descriptor = ComponentDescriptor.of(
                    "event-consumer", CapabilityRequirement.required(EventCapabilities.EVENT_BUS));

            @Override
            public ComponentDescriptor descriptor() {
                return descriptor;
            }

            @Override
            public void start(ActivationContext context, NoConfig config) {
                EventBus bus = context.require(EventCapabilities.EVENT_BUS);
                observedBus.set(bus);
                EventSubscription subscription = bus.on(EVENTS,
                        event -> deliveries.incrementAndGet());
                context.lifecycle().manageAsync("listener", subscription::closeAsync);
            }
        };
        ComponentFactory<NoConfig> factory = new ComponentFactory<>() {
            @Override
            public String factoryId() {
                return "event-consumer";
            }

            @Override
            public Component<NoConfig> create() {
                return component;
            }
        };
        MutationResult<ComponentHandle<NoConfig>> result = runtime.mutate(mutation ->
                mutation.mount(runtime.rootContext(), mountId, factory, NoConfig.INSTANCE));
        assertTrue(result.committed(), () -> result.diagnostics().toString());
        return result.value();
    }

    private String currentActivation(ComponentHandle<?> handle) {
        return runtime.snapshot().components().stream()
                .filter(component -> component.handleId().equals(handle.handleId()))
                .findFirst()
                .orElseThrow()
                .currentActivationId();
    }

    @Test
    void providerReplacementClosesOldBusAfterConsumerAndReactivatesConsumer() throws Exception {
        AtomicReference<EventBus> observedBus = new AtomicReference<>();
        AtomicInteger deliveries = new AtomicInteger();
        ComponentHandle<NoConfig> provider = mountBus("bus");
        ComponentHandle<NoConfig> consumer = mountConsumer("consumer", observedBus, deliveries);

        assertEquals(ComponentState.ACTIVE, provider.whenSettled()
                .toCompletableFuture().get(10, TimeUnit.SECONDS));
        assertEquals(ComponentState.ACTIVE, consumer.whenSettled()
                .toCompletableFuture().get(10, TimeUnit.SECONDS));
        EventBus oldBus = observedBus.get();
        assertTrue(oldBus.emit(EVENTS, new StringEvent("old")).successful());
        assertEquals(1, deliveries.get());
        String oldActivation = currentActivation(consumer);

        assertEquals(ComponentState.DISPOSED, provider.dispose()
                .toCompletableFuture().get(10, TimeUnit.SECONDS));
        assertEquals(ComponentState.WAITING, consumer.whenSettled()
                .toCompletableFuture().get(10, TimeUnit.SECONDS));
        assertTrue(oldBus.snapshot().closed());

        provider = mountBus("replacement-bus");
        assertEquals(ComponentState.ACTIVE, provider.whenSettled()
                .toCompletableFuture().get(10, TimeUnit.SECONDS));
        assertEquals(ComponentState.ACTIVE, consumer.whenSettled()
                .toCompletableFuture().get(10, TimeUnit.SECONDS));
        EventBus newBus = observedBus.get();

        assertNotSame(oldBus, newBus);
        assertNotEquals(oldActivation, currentActivation(consumer));
        assertTrue(oldBus.snapshot().closed());
        assertEquals(0, oldBus.snapshot().subscriptionCount());
        assertFalse(newBus.snapshot().closed());
        assertThrows(IllegalStateException.class, () ->
                oldBus.emit(EVENTS, new StringEvent("old-again")));
        assertTrue(newBus.emit(EVENTS, new StringEvent("new")).successful());
        assertEquals(2, deliveries.get());

        List<io.knotra.RuntimeSnapshot.RegistrationSnapshot> registrations =
                runtime.snapshot().registrations().stream()
                        .filter(item -> item.capability().name()
                                .equals(EventCapabilities.EVENT_BUS.name()))
                        .toList();
        assertEquals(1, registrations.size());
    }

    @Test
    void providerDisposeDetachesConsumerBeforeClosingOwnedBus() throws Exception {
        AtomicReference<EventBus> observedBus = new AtomicReference<>();
        AtomicInteger deliveries = new AtomicInteger();
        ComponentHandle<NoConfig> provider = mountBus("bus");
        ComponentHandle<NoConfig> consumer = mountConsumer("consumer", observedBus, deliveries);

        assertEquals(ComponentState.ACTIVE, provider.whenSettled()
                .toCompletableFuture().get(10, TimeUnit.SECONDS));
        assertEquals(ComponentState.ACTIVE, consumer.whenSettled()
                .toCompletableFuture().get(10, TimeUnit.SECONDS));
        EventBus oldBus = observedBus.get();

        assertEquals(ComponentState.DISPOSED, provider.dispose()
                .toCompletableFuture().get(10, TimeUnit.SECONDS));
        assertEquals(ComponentState.WAITING, consumer.whenSettled()
                .toCompletableFuture().get(10, TimeUnit.SECONDS));
        assertTrue(oldBus.snapshot().closed());
        assertEquals(0, oldBus.snapshot().subscriptionCount());
        assertTrue(runtime.snapshot().registrations().stream()
                .noneMatch(item -> item.capability().name()
                        .equals(EventCapabilities.EVENT_BUS.name())));
    }
    @Test
    void gatedSubscriptionPreventsProviderDisposeFromSettling() throws Exception {
        AtomicReference<EventBus> observedBus = new AtomicReference<>();
        AtomicReference<EventSubscription> subscription = new AtomicReference<>();
        CountDownLatch started = new CountDownLatch(1);
        CompletableFuture<Boolean> gate = new CompletableFuture<>();
        ComponentHandle<NoConfig> provider = mountBus("bus");
        ComponentHandle<NoConfig> consumer = mountGatedConsumer(
                "consumer", observedBus, subscription, started, gate);
        assertEquals(ComponentState.ACTIVE, provider.whenSettled()
                .toCompletableFuture().get(10, TimeUnit.SECONDS));
        assertEquals(ComponentState.ACTIVE, consumer.whenSettled()
                .toCompletableFuture().get(10, TimeUnit.SECONDS));

        var dispatch = observedBus.get().serial(
                        EventDefinition.serial(EventKey.of(StringEvent.class)),
                        new StringEvent("held"))
                .toCompletableFuture();
        assertTrue(started.await(10, TimeUnit.SECONDS));
        var disposal = provider.dispose().toCompletableFuture();

        assertFalse(disposal.isDone());
        assertEquals(ComponentState.STOPPING, componentState(consumer));
        gate.complete(true);

        assertEquals(ComponentState.DISPOSED, disposal.get(10, TimeUnit.SECONDS));
        assertTrue(dispatch.get(10, TimeUnit.SECONDS).successful());
        assertFalse(subscription.get().active());
        assertTrue(observedBus.get().snapshot().closed());
    }

    @Test
    void replacementActivationWaitsForGatedOldConsumerTeardown() throws Exception {
        AtomicReference<EventBus> observedBus = new AtomicReference<>();
        AtomicReference<EventSubscription> subscription = new AtomicReference<>();
        CountDownLatch started = new CountDownLatch(1);
        CompletableFuture<Boolean> gate = new CompletableFuture<>();
        ComponentHandle<NoConfig> oldProvider = mountBus("old-bus");
        ComponentHandle<NoConfig> consumer = mountGatedConsumer(
                "consumer", observedBus, subscription, started, gate);
        assertEquals(ComponentState.ACTIVE, oldProvider.whenSettled()
                .toCompletableFuture().get(10, TimeUnit.SECONDS));
        assertEquals(ComponentState.ACTIVE, consumer.whenSettled()
                .toCompletableFuture().get(10, TimeUnit.SECONDS));
        EventBus oldBus = observedBus.get();

        var dispatch = oldBus.serial(
                        EventDefinition.serial(EventKey.of(StringEvent.class)),
                        new StringEvent("replacement"))
                .toCompletableFuture();
        assertTrue(started.await(10, TimeUnit.SECONDS));
        var oldDisposal = oldProvider.dispose().toCompletableFuture();

        ComponentHandle<NoConfig> newProvider = mountBus("new-bus");
        assertEquals(ComponentState.ACTIVE, newProvider.whenSettled()
                .toCompletableFuture().get(10, TimeUnit.SECONDS));
        assertSame(oldBus, observedBus.get());
        assertFalse(oldDisposal.isDone());

        gate.complete(true);
        assertEquals(ComponentState.DISPOSED, oldDisposal.get(10, TimeUnit.SECONDS));
        assertEquals(ComponentState.ACTIVE, consumer.whenSettled()
                .toCompletableFuture().get(10, TimeUnit.SECONDS));
        assertNotSame(oldBus, observedBus.get());
        assertTrue(dispatch.get(10, TimeUnit.SECONDS).successful());
    }

    private ComponentHandle<NoConfig> mountGatedConsumer(
            String mountId,
            AtomicReference<EventBus> observedBus,
            AtomicReference<EventSubscription> subscription,
            CountDownLatch started,
            CompletableFuture<Boolean> gate) {
        Component<NoConfig> component = new Component<>() {
            private final ComponentDescriptor descriptor = ComponentDescriptor.of(
                    "gated-event-consumer",
                    CapabilityRequirement.required(EventCapabilities.EVENT_BUS));

            @Override
            public ComponentDescriptor descriptor() {
                return descriptor;
            }

            @Override
            public void start(ActivationContext context, NoConfig config) {
                EventBus bus = context.require(EventCapabilities.EVENT_BUS);
                observedBus.set(bus);
                subscription.set(bus.onSerial(
                        EventDefinition.serial(EventKey.of(StringEvent.class)),
                        event -> {
                            started.countDown();
                            return gate;
                        }));
                context.lifecycle().manageAsync(
                        "gated-listener", () -> subscription.get().closeAsync());
            }
        };
        ComponentFactory<NoConfig> factory = new ComponentFactory<>() {
            @Override
            public String factoryId() {
                return "gated-event-consumer";
            }

            @Override
            public Component<NoConfig> create() {
                return component;
            }
        };
        MutationResult<ComponentHandle<NoConfig>> result = runtime.mutate(mutation ->
                mutation.mount(runtime.rootContext(), mountId, factory, NoConfig.INSTANCE));
        assertTrue(result.committed(), () -> result.diagnostics().toString());
        return result.value();
    }

    private ComponentState componentState(ComponentHandle<?> handle) {
        return runtime.snapshot().components().stream()
                .filter(component -> component.handleId().equals(handle.handleId()))
                .findFirst()
                .orElseThrow()
                .state();
    }

    private record StringEvent(String value) {
    }
}
