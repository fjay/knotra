package io.knotra.events;

import io.knotra.CapabilityRequirement;
import io.knotra.Component;
import io.knotra.ComponentDescriptor;
import io.knotra.ComponentFactory;
import io.knotra.ComponentHandle;
import io.knotra.KnotraRuntime;
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
    private static final EventDefinition.Sync<TextEvent> SYNC =
            EventDefinition.sync(TextEvent.class);
    private static final EventDefinition.Parallel<TextEvent> PARALLEL =
            EventDefinition.parallel(TextEvent.class);
    private static final EventDefinition.Serial<TextEvent> SERIAL =
            EventDefinition.serial(TextEvent.class);
    private static final EventDefinition.Bail<TextEvent> BAIL =
            EventDefinition.bail(TextEvent.class);
    private static final EventDefinition.Waterfall<TextEvent> WATERFALL =
            EventDefinition.waterfall(TextEvent.class);
    private KnotraRuntime runtime;
    private EventBus bus;

    @BeforeEach
    void setUp() throws Exception {
        runtime = KnotraRuntime.create();
        ComponentHandle<NoConfig> handle = mountBus();
        assertEquals(io.knotra.ComponentState.ACTIVE,
                handle.whenSettled().toCompletableFuture().get(10, TimeUnit.SECONDS));
        Optional<EventBus> found = runtime.root().view().find(EventCapabilities.EVENT_BUS);
        bus = found.orElseThrow();
    }

    @AfterEach
    void tearDown() throws Exception {
        runtime.close();
    }

    private ComponentHandle<NoConfig> mountBus() {
        return runtime.mount("event-bus", new EventBusFactory());
    }

    private ComponentHandle<NoConfig> mountConsumer(
            java.util.function.BiConsumer<io.knotra.ActivationContext, EventBus> start) {
        Component<NoConfig> component = new Component<>() {
            private final ComponentDescriptor descriptor = ComponentDescriptor.named(
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
        return runtime.mount("event-consumer", factory);
    }

    @Test
    void definitionsEncodeModeAndUseJvmClassIdentityByDefault() {
        assertEquals(TextEvent.class.getName(), SYNC.name());
        assertEquals(TextEvent.class, SYNC.eventType());
        assertEquals(EventMode.SYNC, SYNC.mode());
        assertEquals(EventMode.PARALLEL, PARALLEL.mode());
        assertEquals(EventMode.SERIAL, SERIAL.mode());
        assertEquals(EventMode.BAIL, BAIL.mode());
        assertEquals(EventMode.WATERFALL, WATERFALL.mode());
        assertEquals("app.text", EventDefinition.sync("app.text", TextEvent.class).name());
        assertThrows(IllegalArgumentException.class, () ->
                EventDefinition.sync(" ", TextEvent.class));
    }

    @Test
    void syncEmitRunsListenersInRegistrationOrder() {
        List<String> values = new ArrayList<>();
        bus.subscribe(SYNC, event -> values.add("first:" + event.text()));
        bus.subscribe(SYNC, event -> values.add("second:" + event.text()));

        EventDispatch<TextEvent> result = bus.dispatch(SYNC, new TextEvent("hello"));

        assertTrue(result.successful());
        assertEquals(2, result.listenerCount());
        assertEquals(2, result.completedCount());
        assertEquals(List.of("first:hello", "second:hello"), values);
    }

    @Test
    void syncListenerFailureContinuesAndDoesNotCorruptRegistry() {
        List<String> values = new ArrayList<>();
        bus.subscribe(SYNC, event -> {
            throw new IllegalStateException("first failed");
        });
        bus.subscribe(SYNC, event -> values.add(event.text()));

        EventDispatch<TextEvent> result = bus.dispatch(SYNC, new TextEvent("after-error"));

        assertEquals(1, result.failureCount());
        assertEquals(1, result.completedCount());
        assertEquals(List.of("after-error"), values);
        assertEquals(2, bus.snapshot().subscriptionCount());
    }

    @Test
    void subscriptionCloseIsIdempotentAndRemovesFutureListenersOnly() {
        List<String> values = new ArrayList<>();
        EventSubscription subscription = bus.subscribe(SYNC, event -> values.add(event.text()));
        subscription.close();
        subscription.close();

        assertFalse(subscription.active());
        assertTrue(bus.dispatch(SYNC, new TextEvent("none")).successful());
        assertTrue(values.isEmpty());

        bus.subscribe(SYNC, event -> values.add(event.text()));
        bus.dispatch(SYNC, new TextEvent("new"));
        assertEquals(List.of("new"), values);
    }

    @Test
    void snapshotContainsStableSubscriptionDtos() {
        EventSubscription first = bus.subscribe(SYNC, event -> {});
        EventSubscription second = bus.subscribe(PARALLEL, event -> CompletableFuture.completedFuture(null));

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
        bus.subscribe(PARALLEL, event -> {
            started.countDown();
            return gate;
        });
        bus.subscribe(PARALLEL, event -> {
            started.countDown();
            return gate;
        });

        var result = bus.dispatch(PARALLEL, new TextEvent("parallel")).toCompletableFuture();
        assertTrue(started.await(10, TimeUnit.SECONDS));
        assertFalse(result.isDone());
        gate.complete(null);

        EventDispatch<TextEvent> dispatch = result.get(10, TimeUnit.SECONDS);
        assertTrue(dispatch.successful());
        assertEquals(2, dispatch.listenerCount());
        assertEquals(2, dispatch.completedCount());
        assertSame(bus, runtime.root().view().require(EventCapabilities.EVENT_BUS));
    }

    @Test
    void parallelFailureAggregatesAfterAllListenersFinish() throws Exception {
        CountDownLatch started = new CountDownLatch(2);
        CompletableFuture<Void> gate = new CompletableFuture<>();
        bus.subscribe(PARALLEL, event -> {
            started.countDown();
            return gate.thenApply(ignored -> {
                throw new IllegalStateException("async failed");
            });
        });
        bus.subscribe(PARALLEL, event -> {
            started.countDown();
            gate.complete(null);
            return gate;
        });

        EventDispatch<TextEvent> result = bus.dispatch(PARALLEL, new TextEvent("aggregate"))
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
        bus.subscribe(PARALLEL, event -> {
            started.countDown();
            return gate;
        });

        var result = bus.dispatch(PARALLEL, new TextEvent("in-flight")).toCompletableFuture();
        assertTrue(started.await(10, TimeUnit.SECONDS));
        var closing = bus.closeAsync().toCompletableFuture();
        assertTrue(bus.snapshot().closed());
        assertEquals(0, bus.snapshot().subscriptionCount());
        assertFalse(closing.isDone());
        gate.complete(null);

        closing.get(10, TimeUnit.SECONDS);
        assertTrue(result.get(10, TimeUnit.SECONDS).successful());
        assertThrows(IllegalStateException.class, () ->
                bus.dispatch(PARALLEL, new TextEvent("rejected")));
    }

    @Test
    void inFlightDispatchSeesSnapshotButNextDispatchExcludesUnsubscribedListener()
            throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CompletableFuture<Void> gate = new CompletableFuture<>();
        List<String> values = new ArrayList<>();
        EventSubscription subscription = bus.subscribe(PARALLEL, event -> {
            started.countDown();
            return gate.thenRun(() -> values.add("included"));
        });

        var first = bus.dispatch(PARALLEL, new TextEvent("snapshot")).toCompletableFuture();
        assertTrue(started.await(10, TimeUnit.SECONDS));
        subscription.unsubscribe();
        gate.complete(null);
        assertTrue(first.get(10, TimeUnit.SECONDS).successful());

        bus.subscribe(PARALLEL, event -> CompletableFuture.runAsync(() -> values.add("new")));
        bus.dispatch(PARALLEL, new TextEvent("next")).toCompletableFuture()
                .get(10, TimeUnit.SECONDS);
        assertEquals(List.of("included", "new"), values);
    }

    @Test
    void serialDispatchRunsAllListenersWhenEveryListenerContinues() throws Exception {
        List<String> values = new ArrayList<>();
        bus.subscribe(SERIAL, event -> CompletableFuture.completedFuture(true)
                .thenApply(ignored -> {
                    values.add("first");
                    return true;
                }));
        bus.subscribe(SERIAL, event -> CompletableFuture.completedFuture(true)
                .thenApply(ignored -> {
                    values.add("second");
                    return true;
                }));

        EventDispatch<TextEvent> result = bus.dispatch(SERIAL, new TextEvent("serial"))
                .toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertTrue(result.successful());
        assertFalse(result.stoppedEarly());
        assertEquals(2, result.completedCount());
        assertEquals(List.of("first", "second"), values);
    }

    @Test
    void serialStopPreventsLaterListenersWithoutFailure() throws Exception {
        List<String> values = new ArrayList<>();
        bus.subscribe(SERIAL, event -> CompletableFuture.completedFuture(false)
                .thenApply(ignored -> {
                    values.add("first");
                    return false;
                }));
        bus.subscribe(SERIAL, event -> CompletableFuture.completedFuture(true)
                .thenApply(ignored -> {
                    values.add("second");
                    return true;
                }));

        EventDispatch<TextEvent> result = bus.dispatch(SERIAL, new TextEvent("stop"))
                .toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertTrue(result.successful());
        assertTrue(result.stoppedEarly());
        assertEquals(1, result.completedCount());
        assertEquals(List.of("first"), values);
    }

    @Test
    void bailStopsAtFirstClaimedResult() throws Exception {
        List<String> values = new ArrayList<>();
        bus.subscribe(BAIL, event -> {
            values.add("first");
            return true;
        });
        bus.subscribe(BAIL, event -> {
            values.add("second");
            return true;
        });

        EventDispatch<TextEvent> result = bus.dispatch(BAIL, new TextEvent("claim"))
                .toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertTrue(result.successful());
        assertTrue(result.stoppedEarly());
        assertEquals(List.of("first"), values);
    }

    @Test
    void bailRunsAllWhenNoListenerClaimsResult() throws Exception {
        List<String> values = new ArrayList<>();
        bus.subscribe(BAIL, event -> {
            values.add("first");
            return false;
        });
        bus.subscribe(BAIL, event -> {
            values.add("second");
            return false;
        });

        EventDispatch<TextEvent> result = bus.dispatch(BAIL, new TextEvent("no-claim"))
                .toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertTrue(result.successful());
        assertFalse(result.stoppedEarly());
        assertEquals(List.of("first", "second"), values);
    }

    @Test
    void waterfallTransformsValueThroughRegisteredListeners() throws Exception {
        bus.subscribe(WATERFALL, event -> CompletableFuture.completedFuture(
                new TextEvent(event.text() + "-first")));
        bus.subscribe(WATERFALL, event -> CompletableFuture.completedFuture(
                new TextEvent(event.text() + "-second")));

        EventDispatch<TextEvent> result = bus.dispatch(WATERFALL, new TextEvent("value"))
                .toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertTrue(result.successful());
        assertEquals(new TextEvent("value"), result.initialEvent());
        assertEquals(new TextEvent("value-first-second"), result.finalEvent());
        assertEquals(2, result.completedCount());
    }

    @Test
    void waterfallFailureStopsTransformationAndReportsFailure() throws Exception {
        bus.subscribe(WATERFALL, event -> CompletableFuture.completedFuture(
                new TextEvent(event.text() + "-first")));
        bus.subscribe(WATERFALL, event -> CompletableFuture.failedFuture(
                new IllegalStateException("transform failed")));

        EventDispatch<TextEvent> result = bus.dispatch(WATERFALL, new TextEvent("value"))
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
                bus.subscribe(SYNC, event -> {}));
        assertThrows(IllegalStateException.class, () ->
                bus.dispatch(SYNC, new TextEvent("sync")));
        assertThrows(IllegalStateException.class, () ->
                bus.dispatch(PARALLEL, new TextEvent("parallel")));
        assertThrows(IllegalStateException.class, () ->
                bus.dispatch(SERIAL, new TextEvent("serial")));
        assertThrows(IllegalStateException.class, () ->
                bus.dispatch(BAIL, new TextEvent("bail")));
        assertThrows(IllegalStateException.class, () ->
                bus.dispatch(WATERFALL, new TextEvent("waterfall")));
    }

    @Test
    void closeClearsSubscriptionsAndIsIdempotent() {
        EventSubscription subscription = bus.subscribe(SYNC, event -> {});
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
        bus.subscribe(SYNC, event -> values.add("sync"));
        bus.subscribe(PARALLEL, event -> CompletableFuture.runAsync(() -> values.add("parallel")));
        bus.subscribe(SERIAL, event -> CompletableFuture.completedFuture(true)
                .thenApply(ignored -> {
                    values.add("serial");
                    return true;
                }));
        assertTrue(bus.dispatch(SYNC, new TextEvent("event")).successful());
        bus.dispatch(PARALLEL, new TextEvent("event")).toCompletableFuture()
                .get(10, TimeUnit.SECONDS);
        bus.dispatch(SERIAL, new TextEvent("event")).toCompletableFuture()
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
                        bus.subscribe(SYNC, event -> {})));
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
        EventSubscription subscription = bus.subscribe(SYNC, event -> {});
        runtime.close();

        assertFalse(subscription.active());
        assertTrue(bus.snapshot().closed());
    }

    @Test
    void managedConsumerSubscriptionIsClosedOnDispose() throws Exception {
        var handle = mountConsumer((context, eventBus) -> {
            EventSubscription subscription = eventBus.subscribe(SYNC, event -> {});
            context.lifecycle().manageAsync("listener", subscription);
        });

        assertEquals(io.knotra.ComponentState.ACTIVE,
                handle.whenSettled().toCompletableFuture().get(10, TimeUnit.SECONDS));
        assertEquals(1, bus.snapshot().subscriptionCount());

        handle.disposeAsync().toCompletableFuture().get(10, TimeUnit.SECONDS);
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
