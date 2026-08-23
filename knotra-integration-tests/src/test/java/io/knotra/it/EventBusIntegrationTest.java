package io.knotra.it;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.example.integration.contract.ContractEvent;
import com.example.integration.contract.IntegrationCoordinator;
import io.knotra.ComponentDescriptor;
import io.knotra.ComponentState;
import io.knotra.KnotraRuntime;
import io.knotra.MountFactory;
import io.knotra.MountHandle;
import io.knotra.events.EventBus;
import io.knotra.events.EventCapabilities;
import io.knotra.events.EventDefinition;
import io.knotra.events.EventDispatch;
import io.knotra.events.EventSubscription;
import io.knotra.events.EventBusFactory;
import io.knotra.pf4j.ArtifactState;
import io.knotra.pf4j.Pf4jArtifactAdapter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

@org.junit.jupiter.api.parallel.ResourceLock(IntegrationTestKit.INTEGRATION_COORDINATOR_LOCK)
final class EventBusIntegrationTest {

    private static final Path TEST_CLASSES = Path.of("target", "test-classes");
    private static final EventDefinition.Serial<ContractEvent> CONTRACT_EVENTS =
            EventDefinition.serial(ContractEvent.class);

    @RegisterExtension
    private final KnotraIntegrationExtension runtimeExtension =
            KnotraIntegrationExtension.defaults();

    private MountHandle mountBus(KnotraRuntime runtime, String mountId) {
        return runtime.mount(mountId, new EventBusFactory());
    }

    @Test
    void gatedDispatchBlocksProviderReplacementUntilOldListenerSettles(
            KnotraRuntime runtime) throws Exception {
        AtomicReference<EventBus> observed = new AtomicReference<>();
        AtomicInteger deliveries = new AtomicInteger();
        CountDownLatch listenerEntered = new CountDownLatch(1);
        CompletableFuture<Boolean> gate = new CompletableFuture<>();

        MountHandle provider = mountBus(runtime, "bus");
        MountHandle consumer = mountGatedConsumer(
                runtime, observed, deliveries, listenerEntered, gate);
        assertEquals(ComponentState.ACTIVE, provider.awaitSettled(Duration.ofSeconds(30)));
        assertEquals(ComponentState.ACTIVE, consumer.awaitSettled(Duration.ofSeconds(30)));
        EventBus oldBus = observed.get();

        CompletableFuture<EventDispatch<ContractEvent>> held =
                oldBus.dispatch(CONTRACT_EVENTS, new ContractEvent("old")).toCompletableFuture();
        assertTrue(listenerEntered.await(10, TimeUnit.SECONDS));
        CompletableFuture<ComponentState> oldDisposal = provider.disposeAsync().toCompletableFuture();
        assertFalse(oldDisposal.isDone());

        MountHandle replacement = mountBus(runtime, "replacement-bus");
        assertEquals(ComponentState.ACTIVE, replacement.awaitSettled(Duration.ofSeconds(30)));
        assertSame(oldBus, observed.get(), "old activation must settle before reactivation");

        gate.complete(true);
        assertEquals(ComponentState.DISPOSED, oldDisposal.get(10, TimeUnit.SECONDS));
        assertTrue(held.get(10, TimeUnit.SECONDS).successful());
        assertEquals(ComponentState.ACTIVE, consumer.awaitSettled(Duration.ofSeconds(30)));
        EventBus newBus = observed.get();

        assertNotSame(oldBus, newBus);
        assertTrue(oldBus.snapshot().closed());
        assertEquals(0, oldBus.snapshot().subscriptionCount());
        Throwable closedFailure = ShadowEvents.failedStageCause(
                oldBus.dispatch(CONTRACT_EVENTS, new ContractEvent("rejected")));
        assertTrue(closedFailure instanceof IllegalStateException,
                () -> String.valueOf(closedFailure));

        assertTrue(newBus.dispatch(CONTRACT_EVENTS, new ContractEvent("new"))
                .toCompletableFuture().get(10, TimeUnit.SECONDS).successful());
        assertEquals(2, deliveries.get(), "exactly one delivery per accepted dispatch");
        assertTrue(runtime.advanced().snapshot().registrations().stream()
                .anyMatch(item -> item.capability().name()
                        .equals(EventCapabilities.EVENT_BUS.name())));
    }

    @Test
    void pluginListenerGateBlocksArtifactDrainAndPreventsDuplicateDelivery(
            KnotraRuntime runtime,
            @TempDir Path pluginsRoot) throws Exception {
        MountHandle busProvider = mountBus(runtime, "bus");
        assertEquals(ComponentState.ACTIVE, busProvider.awaitSettled(Duration.ofSeconds(30)));
        EventBus bus = runtime.root().view().require(EventCapabilities.EVENT_BUS);

        try (Pf4jArtifactAdapter adapter = IntegrationTestKit.adapter(pluginsRoot, runtime)) {
            adapter.loadArtifactAsync(IntegrationTestKit.fixture()).toCompletableFuture().join();
            MountHandle consumer = adapter.factories()
                    .resolveNoConfig("integration-event-consumer").orElseThrow()
                    .mount(runtime.root(), "plugin-consumer");
            assertEquals(ComponentState.ACTIVE, consumer.awaitSettled(Duration.ofSeconds(30)));

            CompletableFuture<EventDispatch<ContractEvent>> held =
                    bus.dispatch(CONTRACT_EVENTS, new ContractEvent("held")).toCompletableFuture();
            IntegrationCoordinator.eventEntered().get(10, TimeUnit.SECONDS);

            CompletableFuture<Void> unload = adapter
                    .unloadArtifactAsync(IntegrationTestKit.ARTIFACT_ID)
                    .toCompletableFuture();
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                    assertEquals(ArtifactState.DRAINING, adapter.artifact(
                            IntegrationTestKit.ARTIFACT_ID).orElseThrow().state()));
            assertFalse(unload.isDone(), "drain must wait for the accepted plugin callback");
            assertEquals(0, bus.snapshot().subscriptionCount());

            ShadowEvents.ShadowEvent<?> shadowWhileHeld = ShadowEvents.load(
                    TEST_CLASSES,
                    ContractEvent.class.getName(),
                    "shadow-while-held");
            Throwable rejected = ShadowEvents.failedStageCause(
                    dispatchShadow(bus, shadowWhileHeld));
            assertTrue(rejected instanceof IllegalArgumentException,
                    () -> String.valueOf(rejected));
            assertTrue(rejected.getMessage().contains("already bound to a different Class"),
                    rejected::getMessage);

            IntegrationCoordinator.releaseEvent();
            unload.get(10, TimeUnit.SECONDS);
            assertTrue(held.get(10, TimeUnit.SECONDS).successful());
            assertEquals(1, IntegrationCoordinator.eventDeliveries());
            assertEquals(ArtifactState.UNLOADED, adapter.artifact(
                    IntegrationTestKit.ARTIFACT_ID).orElseThrow().state());
            assertTrue(runtime.advanced().snapshot().mounts().stream()
                    .noneMatch(mount -> mount.mountId().equals("plugin-consumer")));

            ShadowEvents.ShadowEvent<?> shadowAfterRelease = ShadowEvents.load(
                    TEST_CLASSES,
                    ContractEvent.class.getName(),
                    "shadow-after-release");
            EventDispatch<?> accepted = dispatchShadow(bus, shadowAfterRelease)
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertTrue(accepted.successful());
            assertEquals(0, accepted.listenerCount());

            EventDispatch<ContractEvent> afterUnload =
                    bus.dispatch(CONTRACT_EVENTS, new ContractEvent("after"))
                            .toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertTrue(afterUnload.successful());
            assertEquals(0, afterUnload.listenerCount());
            assertEquals(1, IntegrationCoordinator.eventDeliveries());
            assertFalse(bus.snapshot().closed());
        }
    }

    @Test
    void openHostBusReleasesPluginClassLoaderAndReloadsSameEventName(
            KnotraRuntime runtime,
            @TempDir Path pluginsRoot) throws Exception {
        MountHandle busProvider = mountBus(runtime, "bus");
        assertEquals(ComponentState.ACTIVE, busProvider.awaitSettled(Duration.ofSeconds(30)));
        EventBus bus = runtime.root().view().require(EventCapabilities.EVENT_BUS);

        try (Pf4jArtifactAdapter adapter = IntegrationTestKit.adapter(pluginsRoot, runtime)) {
            adapter.loadArtifactAsync(IntegrationTestKit.fixture()).toCompletableFuture().join();
            MountHandle reloadConsumer = adapter.factories()
                    .resolveNoConfig("integration-event-consumer").orElseThrow()
                    .mount(runtime.root(), "reload-consumer");
            assertEquals(ComponentState.ACTIVE,
                    reloadConsumer.awaitSettled(Duration.ofSeconds(30)));
            assertEquals(2, bus.snapshot().subscriptionCount());

            adapter.unloadArtifactAsync(IntegrationTestKit.ARTIFACT_ID)
                    .toCompletableFuture().join();
            assertEquals(ArtifactState.UNLOADED, adapter.artifact(
                    IntegrationTestKit.ARTIFACT_ID).orElseThrow().state());
            assertEquals(0, bus.snapshot().subscriptionCount());
            assertFalse(bus.snapshot().closed());

            await().atMost(Duration.ofSeconds(30))
                    .pollInterval(Duration.ofMillis(50))
                    .untilAsserted(() -> {
                        System.gc();
                        assertEquals(0, IntegrationCoordinator.liveLoaders(),
                                "an open host bus must not retain the plugin class loader");
                    });

            adapter.loadArtifactAsync(IntegrationTestKit.fixture()).toCompletableFuture().join();
            MountHandle reloadedConsumer = adapter.factories()
                    .resolveNoConfig("integration-event-consumer").orElseThrow()
                    .mount(runtime.root(), "reloaded-consumer");
            assertEquals(ComponentState.ACTIVE,
                    reloadedConsumer.awaitSettled(Duration.ofSeconds(30)));
            assertEquals(2, bus.snapshot().subscriptionCount());

            CompletableFuture<EventDispatch<ContractEvent>> reloaded =
                    bus.dispatch(CONTRACT_EVENTS, new ContractEvent("reloaded"))
                            .toCompletableFuture();
            IntegrationCoordinator.eventEntered().get(10, TimeUnit.SECONDS);
            IntegrationCoordinator.releaseEvent();
            assertTrue(reloaded.get(10, TimeUnit.SECONDS).successful());
        }
    }

    @Test
    void eventIdentityIsTheExactJvmClassNotTheName(
            KnotraRuntime runtime,
            @TempDir Path pluginsRoot) throws Exception {
        MountHandle busProvider = mountBus(runtime, "bus");
        assertEquals(ComponentState.ACTIVE, busProvider.awaitSettled(Duration.ofSeconds(30)));

        try (Pf4jArtifactAdapter adapter = IntegrationTestKit.adapter(pluginsRoot, runtime)) {
            adapter.loadArtifactAsync(IntegrationTestKit.fixture()).toCompletableFuture().join();
            MountHandle consumer = adapter.factories()
                    .resolveNoConfig("integration-event-consumer").orElseThrow()
                    .mount(runtime.root(), "identity");
            assertEquals(ComponentState.ACTIVE, consumer.awaitSettled(Duration.ofSeconds(30)));
            EventBus bus = runtime.root().view().require(EventCapabilities.EVENT_BUS);
            assertTrue(bus.snapshot().subscriptions().stream()
                    .anyMatch(item -> item.eventTypeName()
                            .equals("com.example.integration.plugin.PluginEvent")));

            ShadowEvents.ShadowEvent<?> shadow = ShadowEvents.load(
                    IntegrationTestKit.fixture(),
                    "com.example.integration.plugin.PluginEvent",
                    "shadow");
            Throwable failureCause = ShadowEvents.failedStageCause(
                    dispatchShadow(bus, shadow));
            assertTrue(failureCause instanceof IllegalArgumentException,
                    () -> String.valueOf(failureCause));
            assertTrue(failureCause.getMessage().contains("already bound to a different Class"),
                    failureCause::getMessage);
        }
    }

    private static <T> CompletionStage<EventDispatch<T>> dispatchShadow(
            EventBus bus,
            ShadowEvents.ShadowEvent<T> shadow) {
        return bus.dispatch(shadow.definition(), shadow.value());
    }

    private MountHandle mountGatedConsumer(
            KnotraRuntime runtime,
            AtomicReference<EventBus> observed,
            AtomicInteger deliveries,
            CountDownLatch listenerEntered,
            CompletableFuture<Boolean> gate) {
        MountFactory factory = MountFactory.of(
                "gated-host-consumer",
                ComponentDescriptor.named(
                        "gated-host-consumer",
                        io.knotra.CapabilityRequirement.required(EventCapabilities.EVENT_BUS)),
                context -> {
                    EventBus bus = context.require(EventCapabilities.EVENT_BUS);
                    observed.set(bus);
                    EventSubscription subscription = bus.subscribe(CONTRACT_EVENTS, event -> {
                        deliveries.incrementAndGet();
                        listenerEntered.countDown();
                        return gate;
                    });
                    context.lifecycle().manageAsync("gated-host-listener", subscription);
                });
        return runtime.mount("consumer", factory);
    }
}
