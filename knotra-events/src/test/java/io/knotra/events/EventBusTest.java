package io.knotra.events;

import io.knotra.CapabilityRequirement;
import io.knotra.Component;
import io.knotra.ComponentDescriptor;
import io.knotra.ComponentFactory;
import io.knotra.ComponentHandle;
import io.knotra.KnotraRuntime;
import io.knotra.MutationResult;
import io.knotra.NoConfig;
import io.knotra.RuntimeSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class EventBusTest {
    private static final EventDefinition<TextEvent> SYNC =
            EventDefinition.sync(EventKey.of(TextEvent.class));
    private static final EventDefinition<TextEvent> PARALLEL =
            EventDefinition.parallel(EventKey.of(TextEvent.class));
    private static final EventDefinition<TextEvent> SERIAL =
            EventDefinition.serial(EventKey.of(TextEvent.class));
    private static final EventDefinition<TextEvent> BAIL =
            EventDefinition.bail(EventKey.of(TextEvent.class));
    private static final EventDefinition<TextEvent> WATERFALL =
            EventDefinition.waterfall(EventKey.of(TextEvent.class));

    private KnotraRuntime runtime;
    private EventBus bus;

    @BeforeEach
    void setUp() throws Exception {
        runtime = KnotraRuntime.create();
        ComponentHandle<NoConfig> handle = mountBus();
        assertEquals(io.knotra.ComponentState.ACTIVE,
                handle.whenSettled().toCompletableFuture().get(10, TimeUnit.SECONDS));
        Optional<EventBus> found = runtime.context().find(EventCapabilities.EVENT_BUS);
        bus = found.orElseThrow();
    }

    @AfterEach
    void tearDown() throws Exception {
        runtime.close();
    }

    private ComponentHandle<NoConfig> mountBus() {
        MutationResult<ComponentHandle<NoConfig>> result = runtime.mutate(mutation ->
                mutation.mount(runtime.rootContext(), "event-bus", new EventBusFactory(),
                        NoConfig.INSTANCE));
        assertTrue(result.committed(), () -> result.diagnostics().toString());
        return result.value();
    }

    private ComponentHandle<NoConfig> mountConsumer(
            java.util.function.BiConsumer<io.knotra.ActivationContext, EventBus> start) {
        Component<NoConfig> component = new Component<>() {
            private final ComponentDescriptor descriptor = ComponentDescriptor.of(
                    "event-consumer", CapabilityRequirement.required(EventCapabilities.EVENT_BUS));

            @Override
            public ComponentDescriptor descriptor() {
                return descriptor;
            }

            @Override
            public void start(io.knotra.ActivationContext context, NoConfig config) {
                start.accept(context, context.require(EventCapabilities.EVENT_BUS));
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
                mutation.mount(runtime.rootContext(), "event-consumer", factory, NoConfig.INSTANCE));
        assertTrue(result.committed(), () -> result.diagnostics().toString());
        return result.value();
    }

    @Test
    void eventKeyUsesJvmClassIdentityAndDefinitionsFixMode() {
        EventKey<TextEvent> key = EventKey.of(TextEvent.class);
        assertEquals(TextEvent.class.getName(), key.name());
        assertEquals(EventMode.SYNC, SYNC.mode());
        assertEquals(EventMode.PARALLEL, PARALLEL.mode());
        assertEquals(EventMode.SERIAL, SERIAL.mode());
        assertEquals(EventMode.BAIL, BAIL.mode());
        assertEquals(EventMode.WATERFALL, WATERFALL.mode());
        assertThrows(IllegalArgumentException.class, () -> bus.onParallel(
                SYNC, event -> CompletableFuture.completedFuture(null)));
    }

    @Test
    void syncEmitRunsListenersInRegistrationOrder() {
        List<String> values = new ArrayList<>();
        bus.on(SYNC, event -> values.add("first:" + event.text()));
        bus.on(SYNC, event -> values.add("second:" + event.text()));

        EventDispatch<TextEvent> result = bus.emit(SYNC, new TextEvent("hello"));

        assertTrue(result.successful());
        assertEquals(2, result.listenerCount());
        assertEquals(2, result.completedCount());
        assertEquals(List.of("first:hello", "second:hello"), values);
    }

    @Test
    void syncListenerFailureContinuesAndDoesNotCorruptRegistry() {
        List<String> values = new ArrayList<>();
        bus.on(SYNC, event -> {
            throw new IllegalStateException("first failed");
        });
        bus.on(SYNC, event -> values.add(event.text()));

        EventDispatch<TextEvent> result = bus.emit(SYNC, new TextEvent("after-error"));

        assertEquals(1, result.failureCount());
        assertEquals(1, result.completedCount());
        assertEquals(List.of("after-error"), values);
        assertEquals(2, bus.snapshot().subscriptionCount());
    }

    @Test
    void subscriptionCloseIsIdempotentAndRemovesFutureListenersOnly() {
        List<String> values = new ArrayList<>();
        EventSubscription subscription = bus.on(SYNC, event -> values.add(event.text()));
        subscription.close();
        subscription.close();

        assertFalse(subscription.active());
        assertTrue(bus.emit(SYNC, new TextEvent("none")).successful());
        assertTrue(values.isEmpty());

        bus.on(SYNC, event -> values.add(event.text()));
        bus.emit(SYNC, new TextEvent("new"));
        assertEquals(List.of("new"), values);
    }

    @Test
    void snapshotContainsStableSubscriptionDtos() {
        EventSubscription first = bus.on(SYNC, event -> {});
        EventSubscription second = bus.onParallel(PARALLEL, event -> CompletableFuture.completedFuture(null));

        EventBusSnapshot snapshot = bus.snapshot();

        assertEquals(bus.busId(), snapshot.busId());
        assertFalse(snapshot.closed());
        assertEquals(2, snapshot.subscriptionCount());
        assertEquals(List.of(first.subscriptionId(), second.subscriptionId()),
                snapshot.subscriptions().stream().map(EventBusSnapshot.Item::subscriptionId).toList());
        assertEquals(TextEvent.class.getName(), snapshot.subscriptions().getFirst().eventName());
        assertEquals(1, first.sequence());
        assertEquals(2, second.sequence());
    }

    @Test
    void parallelDispatchWaitsForEveryListener() throws Exception {
        CountDownLatch started = new CountDownLatch(2);
        CompletableFuture<Void> gate = new CompletableFuture<>();
        bus.onParallel(PARALLEL, event -> {
            started.countDown();
            return gate;
        });
        bus.onParallel(PARALLEL, event -> {
            started.countDown();
            return gate;
        });

        var result = bus.parallel(PARALLEL, new TextEvent("parallel")).toCompletableFuture();
        assertTrue(started.await(10, TimeUnit.SECONDS));
        assertFalse(result.isDone());
        gate.complete(null);

        EventDispatch<TextEvent> dispatch = result.get(10, TimeUnit.SECONDS);
        assertTrue(dispatch.successful());
        assertEquals(2, dispatch.listenerCount());
        assertEquals(2, dispatch.completedCount());
        assertSame(bus, runtime.context().require(EventCapabilities.EVENT_BUS));
    }

    @Test
    void parallelFailureAggregatesAfterAllListenersFinish() throws Exception {
        CountDownLatch started = new CountDownLatch(2);
        CompletableFuture<Void> gate = new CompletableFuture<>();
        bus.onParallel(PARALLEL, event -> {
            started.countDown();
            return gate.thenApply(ignored -> {
                throw new IllegalStateException("async failed");
            });
        });
        bus.onParallel(PARALLEL, event -> {
            started.countDown();
            gate.complete(null);
            return gate;
        });

        EventDispatch<TextEvent> result = bus.parallel(PARALLEL, new TextEvent("aggregate"))
                .toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertTrue(started.await(10, TimeUnit.SECONDS));
        assertEquals(1, result.failureCount());
        assertEquals(1, result.completedCount());
        assertEquals("async failed", result.failures().getFirst().message());
        assertEquals(2, bus.snapshot().subscriptionCount());
    }

    @Test
    void inFlightParallelDispatchCompletesAfterClose() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CompletableFuture<Void> gate = new CompletableFuture<>();
        bus.onParallel(PARALLEL, event -> {
            started.countDown();
            return gate;
        });

        var result = bus.parallel(PARALLEL, new TextEvent("in-flight")).toCompletableFuture();
        assertTrue(started.await(10, TimeUnit.SECONDS));
        var closing = bus.closeAsync().toCompletableFuture();
        assertTrue(bus.snapshot().closed());
        assertEquals(0, bus.snapshot().subscriptionCount());
        assertFalse(closing.isDone());
        gate.complete(null);

        closing.get(10, TimeUnit.SECONDS);
        assertTrue(result.get(10, TimeUnit.SECONDS).successful());
        assertThrows(IllegalStateException.class, () ->
                bus.parallel(PARALLEL, new TextEvent("rejected")));
    }

    @Test
    void inFlightDispatchSeesSnapshotButNextDispatchExcludesUnsubscribedListener()
            throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CompletableFuture<Void> gate = new CompletableFuture<>();
        List<String> values = new ArrayList<>();
        EventSubscription subscription = bus.onParallel(PARALLEL, event -> {
            started.countDown();
            return gate.thenRun(() -> values.add("included"));
        });

        var first = bus.parallel(PARALLEL, new TextEvent("snapshot")).toCompletableFuture();
        assertTrue(started.await(10, TimeUnit.SECONDS));
        subscription.unsubscribe();
        gate.complete(null);
        assertTrue(first.get(10, TimeUnit.SECONDS).successful());

        bus.onParallel(PARALLEL, event -> CompletableFuture.runAsync(() -> values.add("new")));
        bus.parallel(PARALLEL, new TextEvent("next")).toCompletableFuture()
                .get(10, TimeUnit.SECONDS);
        assertEquals(List.of("included", "new"), values);
    }

    @Test
    void serialDispatchRunsAllListenersWhenEveryListenerContinues() throws Exception {
        List<String> values = new ArrayList<>();
        bus.onSerial(SERIAL, event -> CompletableFuture.completedFuture(true)
                .thenApply(ignored -> {
                    values.add("first");
                    return true;
                }));
        bus.onSerial(SERIAL, event -> CompletableFuture.completedFuture(true)
                .thenApply(ignored -> {
                    values.add("second");
                    return true;
                }));

        EventDispatch<TextEvent> result = bus.serial(SERIAL, new TextEvent("serial"))
                .toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertTrue(result.successful());
        assertFalse(result.stoppedEarly());
        assertEquals(2, result.completedCount());
        assertEquals(List.of("first", "second"), values);
    }

    @Test
    void serialStopPreventsLaterListenersWithoutFailure() throws Exception {
        List<String> values = new ArrayList<>();
        bus.onSerial(SERIAL, event -> CompletableFuture.completedFuture(false)
                .thenApply(ignored -> {
                    values.add("first");
                    return false;
                }));
        bus.onSerial(SERIAL, event -> CompletableFuture.completedFuture(true)
                .thenApply(ignored -> {
                    values.add("second");
                    return true;
                }));

        EventDispatch<TextEvent> result = bus.serial(SERIAL, new TextEvent("stop"))
                .toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertTrue(result.successful());
        assertTrue(result.stoppedEarly());
        assertEquals(1, result.completedCount());
        assertEquals(List.of("first"), values);
    }

    @Test
    void bailStopsAtFirstClaimedResult() throws Exception {
        List<String> values = new ArrayList<>();
        bus.onBail(BAIL, event -> {
            values.add("first");
            return true;
        });
        bus.onBail(BAIL, event -> {
            values.add("second");
            return true;
        });

        EventDispatch<TextEvent> result = bus.bail(BAIL, new TextEvent("claim"))
                .toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertTrue(result.successful());
        assertTrue(result.stoppedEarly());
        assertEquals(List.of("first"), values);
    }

    @Test
    void bailRunsAllWhenNoListenerClaimsResult() throws Exception {
        List<String> values = new ArrayList<>();
        bus.onBail(BAIL, event -> {
            values.add("first");
            return false;
        });
        bus.onBail(BAIL, event -> {
            values.add("second");
            return false;
        });

        EventDispatch<TextEvent> result = bus.bail(BAIL, new TextEvent("no-claim"))
                .toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertTrue(result.successful());
        assertFalse(result.stoppedEarly());
        assertEquals(List.of("first", "second"), values);
    }

    @Test
    void waterfallTransformsValueThroughRegisteredListeners() throws Exception {
        bus.onWaterfall(WATERFALL, event -> CompletableFuture.completedFuture(
                new TextEvent(event.text() + "-first")));
        bus.onWaterfall(WATERFALL, event -> CompletableFuture.completedFuture(
                new TextEvent(event.text() + "-second")));

        EventDispatch<TextEvent> result = bus.waterfall(WATERFALL, new TextEvent("value"))
                .toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertTrue(result.successful());
        assertEquals(new TextEvent("value"), result.initialEvent());
        assertEquals(new TextEvent("value-first-second"), result.finalEvent());
        assertEquals(2, result.completedCount());
    }

    @Test
    void waterfallFailureStopsTransformationAndReportsFailure() throws Exception {
        bus.onWaterfall(WATERFALL, event -> CompletableFuture.completedFuture(
                new TextEvent(event.text() + "-first")));
        bus.onWaterfall(WATERFALL, event -> CompletableFuture.failedFuture(
                new IllegalStateException("transform failed")));

        EventDispatch<TextEvent> result = bus.waterfall(WATERFALL, new TextEvent("value"))
                .toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertEquals(1, result.failureCount());
        assertTrue(result.stoppedEarly());
        assertEquals(new TextEvent("value-first"), result.finalEvent());
        assertEquals("transform failed", result.failures().getFirst().message());
    }

    @Test
    void closeRejectsNewSubscriptionsAndAllDispatchModes() {
        bus.close();

        assertThrows(IllegalStateException.class, () ->
                bus.on(SYNC, event -> {}));
        assertThrows(IllegalStateException.class, () ->
                bus.emit(SYNC, new TextEvent("sync")));
        assertThrows(IllegalStateException.class, () ->
                bus.parallel(PARALLEL, new TextEvent("parallel")));
        assertThrows(IllegalStateException.class, () ->
                bus.serial(SERIAL, new TextEvent("serial")));
        assertThrows(IllegalStateException.class, () ->
                bus.bail(BAIL, new TextEvent("bail")));
        assertThrows(IllegalStateException.class, () ->
                bus.waterfall(WATERFALL, new TextEvent("waterfall")));
    }

    @Test
    void closeClearsSubscriptionsAndIsIdempotent() {
        EventSubscription subscription = bus.on(SYNC, event -> {});
        bus.close();
        bus.close();

        assertFalse(subscription.active());
        assertTrue(bus.snapshot().closed());
        assertEquals(0, bus.snapshot().subscriptionCount());
        assertFalse(subscription.active());
    }

    @Test
    void sameEventNameCanHoldDifferentModesIndependently() throws Exception {
        List<String> values = new ArrayList<>();
        bus.on(SYNC, event -> values.add("sync"));
        bus.onParallel(PARALLEL, event -> CompletableFuture.runAsync(() -> values.add("parallel")));
        bus.onSerial(SERIAL, event -> CompletableFuture.completedFuture(true)
                .thenApply(ignored -> {
                    values.add("serial");
                    return true;
                }));
        assertTrue(bus.emit(SYNC, new TextEvent("event")).successful());
        bus.parallel(PARALLEL, new TextEvent("event")).toCompletableFuture()
                .get(10, TimeUnit.SECONDS);
        bus.serial(SERIAL, new TextEvent("event")).toCompletableFuture()
                .get(10, TimeUnit.SECONDS);

        assertEquals(3, bus.snapshot().subscriptionCount());
        assertEquals(3, values.size());
    }

    @Test
    void concurrentRegistrationsProduceStableSubscriptionOrder() throws Exception {
        List<EventSubscription> subscriptions = new ArrayList<>();
        var executor = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            var futures = new ArrayList<java.util.concurrent.Future<EventSubscription>>();
            for (int index = 0; index < 12; index++) {
                futures.add(executor.submit(() ->
                        bus.on(SYNC, event -> {})));
            }
            for (var future : futures) {
                subscriptions.add(future.get(10, TimeUnit.SECONDS));
            }
        } finally {
            executor.shutdown();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }

        List<Long> sequences = subscriptions.stream().map(EventSubscription::sequence).toList();
        assertEquals(12, sequences.size());
        assertEquals(12, sequences.stream().distinct().count());
        long max = sequences.stream().mapToLong(Long::longValue).max().orElseThrow();
        long min = sequences.stream().mapToLong(Long::longValue).min().orElseThrow();
        assertEquals(11L, max - min);
        List<String> snapshotOrder = bus.snapshot().subscriptions().stream()
                .map(EventBusSnapshot.Item::subscriptionId)
                .toList();
        assertEquals(subscriptions.stream().map(EventSubscription::subscriptionId).toList()
                .stream().sorted().toList(), snapshotOrder.stream().sorted().toList());
    }

    @Test
    void runtimeCloseClosesManagedBus() throws Exception {
        EventSubscription subscription = bus.on(SYNC, event -> {});
        runtime.close();

        assertFalse(subscription.active());
        assertTrue(bus.snapshot().closed());
    }

    @Test
    void managedConsumerSubscriptionIsClosedOnDispose() throws Exception {
        var handle = mountConsumer((context, eventBus) -> {
            EventSubscription subscription = eventBus.on(SYNC, event -> {});
            context.lifecycle().manageAsync("listener", subscription::closeAsync);
        });

        assertEquals(io.knotra.ComponentState.ACTIVE,
                handle.whenSettled().toCompletableFuture().get(10, TimeUnit.SECONDS));
        assertEquals(1, bus.snapshot().subscriptionCount());

        handle.dispose().toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertEquals(0, bus.snapshot().subscriptionCount());
    }

    @Test
    void capabilityRegistrationIsOwnedByActivation() throws Exception {
        // The setup mount is already active and owns the single capability registration.

        RuntimeSnapshot snapshot = runtime.snapshot();
        List<RuntimeSnapshot.RegistrationSnapshot> registrations = snapshot.registrations()
                .stream()
                .filter(item -> item.capability().name().equals(EventCapabilities.EVENT_BUS.name()))
                .toList();
        assertEquals(1, registrations.size());
        assertEquals(RuntimeSnapshot.RegistrationOwnerKind.ACTIVATION,
                registrations.getFirst().owner().kind());
    }

    private record TextEvent(String text) {
    }
}
