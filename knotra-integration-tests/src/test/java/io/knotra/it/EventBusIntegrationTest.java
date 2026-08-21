package io.knotra.it;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.example.integration.contract.ContractEvent;
import com.example.integration.contract.IntegrationCoordinator;
import io.knotra.ActivationContext;
import io.knotra.CapabilityRequirement;
import io.knotra.Component;
import io.knotra.ComponentDescriptor;
import io.knotra.ComponentFactory;
import io.knotra.ComponentHandle;
import io.knotra.ComponentState;
import io.knotra.KnotraRuntime;
import io.knotra.NoConfig;
import io.knotra.events.EventBus;
import io.knotra.events.EventCapabilities;
import io.knotra.events.EventDefinition;
import io.knotra.events.EventDispatch;
import io.knotra.events.EventSubscription;
import io.knotra.events.EventBusFactory;
import io.knotra.pf4j.ArtifactState;
import io.knotra.pf4j.Pf4jArtifactAdapter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

final class EventBusIntegrationTest {

    private static final EventDefinition.Serial<ContractEvent> CONTRACT_EVENTS =
            EventDefinition.serial(ContractEvent.class);

    private KnotraRuntime runtime;

    @BeforeEach
    void setUp() {
        IntegrationCoordinator.reset();
        IntegrationCoordinator.clearLoaders();
        runtime = KnotraRuntime.create();
    }

    @AfterEach
    void tearDown() throws Exception {
        IntegrationTestKit.drainIntegrations();
        runtime.close();
    }

    private ComponentHandle<NoConfig> mountBus(String mountId) {
        return runtime.mount(mountId, new EventBusFactory());
    }

    @Test
    void gatedDispatchBlocksProviderReplacementUntilOldListenerSettles() throws Exception {
        AtomicReference<EventBus> observed = new AtomicReference<>();
        AtomicInteger deliveries = new AtomicInteger();
        CountDownLatch listenerEntered = new CountDownLatch(1);
        CompletableFuture<Boolean> gate = new CompletableFuture<>();

        ComponentHandle<NoConfig> provider = mountBus("bus");
        ComponentHandle<NoConfig> consumer = mountGatedConsumer(
                observed, deliveries, listenerEntered, gate);
        assertEquals(ComponentState.ACTIVE, IntegrationTestKit.settle(provider));
        assertEquals(ComponentState.ACTIVE, IntegrationTestKit.settle(consumer));
        EventBus oldBus = observed.get();

        CompletableFuture<EventDispatch<ContractEvent>> held =
                oldBus.dispatch(CONTRACT_EVENTS, new ContractEvent("old")).toCompletableFuture();
        assertTrue(listenerEntered.await(10, TimeUnit.SECONDS));
        CompletableFuture<ComponentState> oldDisposal = provider.disposeAsync().toCompletableFuture();
        assertFalse(oldDisposal.isDone());

        ComponentHandle<NoConfig> replacement = mountBus("replacement-bus");
        assertEquals(ComponentState.ACTIVE, IntegrationTestKit.settle(replacement));
        assertSame(oldBus, observed.get(), "old activation must settle before reactivation");

        gate.complete(true);
        assertEquals(ComponentState.DISPOSED, oldDisposal.get(10, TimeUnit.SECONDS));
        assertTrue(held.get(10, TimeUnit.SECONDS).successful());
        assertEquals(ComponentState.ACTIVE, IntegrationTestKit.settle(consumer));
        EventBus newBus = observed.get();

        assertNotSame(oldBus, newBus);
        assertTrue(oldBus.snapshot().closed());
        assertEquals(0, oldBus.snapshot().subscriptionCount());
        assertThrows(IllegalStateException.class, () ->
                oldBus.dispatch(CONTRACT_EVENTS, new ContractEvent("rejected")));

        assertTrue(newBus.dispatch(CONTRACT_EVENTS, new ContractEvent("new"))
                .toCompletableFuture().get(10, TimeUnit.SECONDS).successful());
        assertEquals(2, deliveries.get(), "exactly one delivery per accepted dispatch");
        assertTrue(runtime.snapshot().registrations().stream()
                .anyMatch(item -> item.capability().name()
                        .equals(EventCapabilities.EVENT_BUS.name())));
    }

    @Test
    void pluginListenerGateBlocksArtifactDrainAndPreventsDuplicateDelivery(
            @TempDir Path pluginsRoot) throws Exception {
        ComponentHandle<NoConfig> busProvider = mountBus("bus");
        assertEquals(ComponentState.ACTIVE, IntegrationTestKit.settle(busProvider));
        EventBus bus = runtime.root().view().require(EventCapabilities.EVENT_BUS);

        try (Pf4jArtifactAdapter adapter = IntegrationTestKit.adapter(pluginsRoot, runtime)) {
            adapter.loadArtifactAsync(IntegrationTestKit.fixture()).toCompletableFuture().join();
            ComponentHandle<NoConfig> consumer = adapter.factories()
                    .resolve("integration-event-consumer", NoConfig.class).orElseThrow()
                    .mount(runtime.root(), "plugin-consumer", NoConfig.INSTANCE);
            assertEquals(ComponentState.ACTIVE, IntegrationTestKit.settle(consumer));

            CompletableFuture<EventDispatch<ContractEvent>> held =
                    bus.dispatch(CONTRACT_EVENTS, new ContractEvent("held")).toCompletableFuture();
            IntegrationCoordinator.eventEntered().get(10, TimeUnit.SECONDS);

            CompletableFuture<Void> unload = adapter
                    .unloadArtifactAsync(IntegrationTestKit.ARTIFACT_ID)
                    .toCompletableFuture();
            await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(() ->
                    assertEquals(ArtifactState.DRAINING, adapter.artifact(
                            IntegrationTestKit.ARTIFACT_ID).orElseThrow().state()));
            assertFalse(unload.isDone(), "drain must wait for the accepted plugin callback");
            assertEquals(0, bus.snapshot().subscriptionCount());

            URL testClasses = java.nio.file.Path.of("target", "test-classes")
                    .toUri().toURL();
            try (URLClassLoader independent = new URLClassLoader(new URL[]{testClasses}, null)) {
                Class<?> shadow = Class.forName(
                        ContractEvent.class.getName(), false, independent);
                Object event = shadow.getDeclaredConstructor(String.class)
                        .newInstance("shadow-while-held");
                @SuppressWarnings("unchecked")
                EventDefinition.Serial<Object> shadowDefinition = EventDefinition.serial(
                        (Class<Object>) (Class<?>) shadow);

                IllegalArgumentException rejected = assertThrows(
                        IllegalArgumentException.class,
                        () -> bus.dispatch(shadowDefinition, event));
                assertTrue(rejected.getMessage().contains(
                        "already bound to a different Class"), rejected::getMessage);
            }
            IntegrationCoordinator.releaseEvent();
            unload.get(10, TimeUnit.SECONDS);
            assertTrue(held.get(10, TimeUnit.SECONDS).successful());
            assertEquals(1, IntegrationCoordinator.eventDeliveries());
            assertEquals(ArtifactState.UNLOADED, adapter.artifact(
                    IntegrationTestKit.ARTIFACT_ID).orElseThrow().state());
            assertTrue(runtime.snapshot().components().stream()
                    .noneMatch(component -> component.mountId().equals("plugin-consumer")));

            URL releasedClasses = java.nio.file.Path.of("target", "test-classes")
                    .toUri().toURL();
            try (URLClassLoader independent = new URLClassLoader(new URL[]{releasedClasses}, null)) {
                Class<?> shadow = Class.forName(
                        ContractEvent.class.getName(), false, independent);
                Object event = shadow.getDeclaredConstructor(String.class)
                        .newInstance("shadow-after-release");
                @SuppressWarnings("unchecked")
                EventDefinition.Serial<Object> shadowDefinition = EventDefinition.serial(
                        (Class<Object>) (Class<?>) shadow);

                EventDispatch<Object> accepted = bus.dispatch(shadowDefinition, event)
                        .toCompletableFuture().get(10, TimeUnit.SECONDS);
                assertTrue(accepted.successful());
                assertEquals(0, accepted.listenerCount());
            }
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
            @TempDir Path pluginsRoot) throws Exception {
        ComponentHandle<NoConfig> busProvider = mountBus("bus");
        assertEquals(ComponentState.ACTIVE, IntegrationTestKit.settle(busProvider));
        EventBus bus = runtime.root().view().require(EventCapabilities.EVENT_BUS);

        try (Pf4jArtifactAdapter adapter = IntegrationTestKit.adapter(pluginsRoot, runtime)) {
            adapter.loadArtifactAsync(IntegrationTestKit.fixture()).toCompletableFuture().join();
            assertEquals(ComponentState.ACTIVE, IntegrationTestKit.settle(
                    adapter.factories().resolve("integration-event-consumer", NoConfig.class).orElseThrow()
                            .mount(runtime.root(), "reload-consumer", NoConfig.INSTANCE)));
            assertEquals(2, bus.snapshot().subscriptionCount());

            adapter.unloadArtifactAsync(IntegrationTestKit.ARTIFACT_ID).toCompletableFuture().join();
            assertEquals(ArtifactState.UNLOADED, adapter.artifact(
                    IntegrationTestKit.ARTIFACT_ID).orElseThrow().state());
            assertEquals(0, bus.snapshot().subscriptionCount());
            assertFalse(bus.snapshot().closed());

            await().atMost(java.time.Duration.ofSeconds(30))
                    .pollInterval(java.time.Duration.ofMillis(50))
                    .untilAsserted(() -> {
                        System.gc();
                        assertEquals(0, IntegrationCoordinator.liveLoaders(),
                                "an open host bus must not retain the plugin class loader");
                    });

            adapter.loadArtifactAsync(IntegrationTestKit.fixture()).toCompletableFuture().join();
            assertEquals(ComponentState.ACTIVE, IntegrationTestKit.settle(
                    adapter.factories().resolve("integration-event-consumer", NoConfig.class).orElseThrow()
                            .mount(runtime.root(), "reloaded-consumer", NoConfig.INSTANCE)));
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
    void eventIdentityIsTheExactJvmClassNotTheName(@TempDir Path pluginsRoot) throws Exception {
        ComponentHandle<NoConfig> busProvider = mountBus("bus");
        assertEquals(ComponentState.ACTIVE, IntegrationTestKit.settle(busProvider));

        try (Pf4jArtifactAdapter adapter = IntegrationTestKit.adapter(pluginsRoot, runtime)) {
            adapter.loadArtifactAsync(IntegrationTestKit.fixture()).toCompletableFuture().join();
            ComponentHandle<NoConfig> consumer = adapter.factories()
                    .resolve("integration-event-consumer", NoConfig.class).orElseThrow()
                    .mount(runtime.root(), "identity", NoConfig.INSTANCE);
            assertEquals(ComponentState.ACTIVE, IntegrationTestKit.settle(consumer));
            EventBus bus = runtime.root().view().require(EventCapabilities.EVENT_BUS);
            assertTrue(bus.snapshot().subscriptions().stream()
                    .anyMatch(item -> item.eventTypeName()
                            .equals("com.example.integration.plugin.PluginEvent")));

            URL jar = IntegrationTestKit.fixture().toUri().toURL();
            try (URLClassLoader independent = new URLClassLoader(new URL[]{jar}, null)) {
                Class<?> shadow = Class.forName(
                        "com.example.integration.plugin.PluginEvent", false, independent);
                Object event = shadow.getDeclaredConstructor(String.class)
                        .newInstance("shadow");
                @SuppressWarnings("unchecked")
                EventDefinition.Serial<Object> shadowDefinition = EventDefinition.serial(
                        (Class<Object>) (Class<?>) shadow);

                IllegalArgumentException failure = assertThrows(
                        IllegalArgumentException.class,
                        () -> bus.dispatch(shadowDefinition, event));
                assertTrue(failure.getMessage().contains("already bound to a different Class"),
                        failure::getMessage);
            }
        }
    }

    private ComponentHandle<NoConfig> mountGatedConsumer(
            AtomicReference<EventBus> observed,
            AtomicInteger deliveries,
            CountDownLatch listenerEntered,
            CompletableFuture<Boolean> gate) {
        Component<NoConfig> component = new Component<>() {
            @Override
            public ComponentDescriptor descriptor() {
                return ComponentDescriptor.named(
                        "gated-host-consumer",
                        CapabilityRequirement.required(EventCapabilities.EVENT_BUS));
            }

            @Override
            public void start(ActivationContext context, NoConfig config) {
                EventBus bus = context.require(EventCapabilities.EVENT_BUS);
                observed.set(bus);
                EventSubscription subscription = bus.subscribe(CONTRACT_EVENTS, event -> {
                    deliveries.incrementAndGet();
                    listenerEntered.countDown();
                    return gate;
                });
                context.lifecycle().manageAsync("gated-host-listener", subscription);
            }
        };
        ComponentFactory<NoConfig> factory = new ComponentFactory<>() {
            @Override
            public String factoryId() {
                return "gated-host-consumer";
            }

            @Override
            public Component<NoConfig> create() {
                return component;
            }
        };
        return runtime.mount("consumer", factory);
    }
}
