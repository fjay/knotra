package io.knotra.spring;

import io.knotra.ActivationContext;
import io.knotra.CapabilityKey;
import io.knotra.CapabilityUnavailableException;
import io.knotra.Component;
import io.knotra.ComponentDescriptor;
import io.knotra.ComponentFactory;
import io.knotra.ComponentHandle;
import io.knotra.ComponentState;
import io.knotra.DynamicCapabilityClosedException;
import io.knotra.KnotraRuntime;
import io.knotra.NoConfig;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class SpringDynamicBridgeTest {

    static final CapabilityKey<Api> SOURCE = CapabilityKey.of("bridge.source", Api.class);
    static final CapabilityKey<Api> BRIDGED = CapabilityKey.of("bridge.stable", Api.class);

    KnotraRuntime runtime = KnotraRuntime.create();

    @AfterEach
    void tearDown() {
        runtime.close();
    }

    interface Api {
        String value();

        CompletionStage<String> valueAsync();
    }

    record SimpleApi(String value) implements Api {
        @Override
        public CompletionStage<String> valueAsync() {
            return CompletableFuture.completedFuture(value);
        }
    }

    static final class GatedApi implements Api {
        private final String value;
        private final CompletableFuture<Void> release;
        private final CountDownLatch asyncEntered;

        GatedApi(String value, CompletableFuture<Void> release, CountDownLatch asyncEntered) {
            this.value = value;
            this.release = release;
            this.asyncEntered = asyncEntered;
        }

        @Override
        public String value() {
            return value;
        }

        @Override
        public CompletionStage<String> valueAsync() {
            asyncEntered.countDown();
            return release.thenApply(ignored -> value);
        }
    }

    record ProviderConfig(String value) {
    }

    @Test
    void bridgeStartsMissingThenFollowsAppearanceAndSupportsCallbacksAndClose()
            throws Exception {
        SpringDynamicBridge<Api> bridge = SpringDynamicBridge.mount(
                runtime, "bridge", SOURCE, BRIDGED);
        assertTrue(bridge.available() == false);
        assertThrows(CapabilityUnavailableException.class, () ->
                bridge.proxy().value());

        runtime.provide(SOURCE, new SimpleApi("v1"));
        assertTrue(bridge.available());
        assertEquals("v1", bridge.proxy().value());
        assertEquals("v1", bridge.withCurrent(Api::value));
        assertEquals("v1", bridge.withCurrentAsync(Api::valueAsync)
                .toCompletableFuture()
                .get(10, TimeUnit.SECONDS));

        Api stableProxy = bridge.proxy();
        bridge.close();
        assertFalse(bridge.available());
        assertThrows(IllegalStateException.class, bridge::proxy);
        assertThrows(DynamicCapabilityClosedException.class, stableProxy::value);
        assertTrue(runtime.root().view().find(BRIDGED).isEmpty());
    }

    @Test
    void blockedBridgeCallDelaysOldProviderComponentCleanupUntilAsyncStageCompletes()
            throws Exception {
        SpringDynamicBridge<Api> bridge = SpringDynamicBridge.mount(
                runtime, "blocked-bridge", SOURCE, BRIDGED);
        assertFalse(bridge.available());

        List<String> cleanup = new CopyOnWriteArrayList<>();
        CountDownLatch asyncEntered = new CountDownLatch(1);
        CompletableFuture<Void> firstRelease = new CompletableFuture<>();
        AtomicReference<GatedApi> firstApi = new AtomicReference<>();
        ComponentHandle<ProviderConfig> provider = mountTrackedProvider(
                "tracked-provider",
                new ProviderConfig("v1"),
                firstRelease,
                asyncEntered,
                firstApi,
                cleanup);
        assertEquals(ComponentState.ACTIVE, provider.whenSettled()
                .toCompletableFuture().get(10, TimeUnit.SECONDS));
        assertSame(firstApi.get(), runtime.root().view().require(SOURCE));
        assertTrue(bridge.available());

        CompletableFuture<String> call = bridge.proxy().valueAsync().toCompletableFuture();
        assertTrue(asyncEntered.await(10, TimeUnit.SECONDS));
        assertFalse(call.isDone());

        CompletableFuture<ComponentState> replacement = provider
                .reconfigureAsync(new ProviderConfig("v2"))
                .toCompletableFuture();
        assertFalse(replacement.isDone());
        assertFalse(call.isDone());

        firstRelease.complete(null);
        assertEquals("v1", call.get(10, TimeUnit.SECONDS));
        assertEquals(ComponentState.ACTIVE, replacement.get(10, TimeUnit.SECONDS));
        assertEquals("v2", bridge.proxy().value());
        assertEquals(List.of("v1-closed"), cleanup);

        bridge.close();
    }

    private ComponentHandle<ProviderConfig> mountTrackedProvider(
            String mountId,
            ProviderConfig config,
            CompletableFuture<Void> release,
            CountDownLatch asyncEntered,
            AtomicReference<GatedApi> created,
            List<String> cleanup) {
        Component<ProviderConfig> component = new Component<>() {
            @Override
            public ComponentDescriptor descriptor() {
                return ComponentDescriptor.of();
            }

            @Override
            public void start(ActivationContext context, ProviderConfig configuration) {
                GatedApi api = new GatedApi(
                        configuration.value(), release, asyncEntered);
                created.set(api);
                context.provide(SOURCE, api);
                context.lifecycle().onClose(
                        configuration.value() + "-closed",
                        () -> cleanup.add(configuration.value() + "-closed"));
            }
        };
        ComponentFactory<ProviderConfig> factory = new ComponentFactory<>() {
            @Override
            public String factoryId() {
                return mountId;
            }

            @Override
            public Component<ProviderConfig> create() {
                return component;
            }
        };
        return runtime.mount(mountId, factory, config);
    }
}
