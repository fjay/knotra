package io.knotra.spring;

import io.knotra.ActivationContext;
import io.knotra.CapabilityKey;
import io.knotra.CapabilityUnavailableException;
import io.knotra.Component;
import io.knotra.ComponentDescriptor;
import io.knotra.ComponentFactory;
import io.knotra.ComponentState;
import io.knotra.ComponentGoal;
import io.knotra.ConfiguredMountHandle;
import io.knotra.DynamicCapabilityClosedException;
import io.knotra.MountHandle;
import io.knotra.MountNotActiveException;
import io.knotra.KnotraRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.time.Duration;
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

        runtime.publish(SOURCE, new SimpleApi("v1"))
                .awaitSettled(java.time.Duration.ofSeconds(10));
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
        ConfiguredMountHandle<ProviderConfig> provider = mountTrackedProvider(
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

    @Test
    void explicitTimeoutMountsAndClosesWithoutChangingNormalBehavior() {
        assertEquals(Duration.ofSeconds(30), SpringDynamicBridge.DEFAULT_TIMEOUT);
        SpringDynamicBridge<Api> bridge = SpringDynamicBridge.mount(
                runtime,
                runtime.root(),
                "explicit-timeout",
                SOURCE,
                BRIDGED,
                Duration.ofSeconds(5));
        try {
            assertFalse(bridge.available());
        } finally {
            bridge.close(Duration.ofSeconds(5));
        }
        assertTrue(runtime.root().view().find(BRIDGED).isEmpty());
    }

    @Test
    void mountRejectsNonPositiveTimeoutBeforeCreatingAMount() {
        assertThrows(IllegalArgumentException.class, () -> SpringDynamicBridge.mount(
                runtime,
                runtime.root(),
                "invalid-timeout",
                SOURCE,
                BRIDGED,
                Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> SpringDynamicBridge.mount(
                runtime,
                runtime.root(),
                "negative-timeout",
                SOURCE,
                BRIDGED,
                Duration.ofNanos(-1)));
    }

    @Test
    void startupWaitDelegatesToMountHandleTimeoutContract() {
        Duration timeout = Duration.ofMillis(20);
        SlowStartupHandle handle = new SlowStartupHandle();

        MountNotActiveException error = assertThrows(MountNotActiveException.class, () ->
                SpringDynamicBridge.awaitStartup(handle, timeout));

        assertEquals(ComponentState.STARTING, error.state());
        assertEquals(timeout, error.timeout());
        assertEquals("slow-startup", error.mountId());
        handle.release.complete(null);
    }

    @Test
    void cleanupTimeoutLeavesPendingCloseRetryableAndRestoresCallerClassLoader()
            throws Exception {
        SpringDynamicBridge<Api> bridge = SpringDynamicBridge.mount(
                runtime,
                runtime.root(),
                "slow-cleanup",
                SOURCE,
                BRIDGED,
                Duration.ofSeconds(5));
        CountDownLatch callEntered = new CountDownLatch(1);
        CompletableFuture<Void> release = new CompletableFuture<>();
        runtime.publish(SOURCE, new GatedApi("held", release, callEntered))
                .awaitSettled(Duration.ofSeconds(10));

        CompletableFuture<String> held = bridge.proxy().valueAsync().toCompletableFuture();
        assertTrue(callEntered.await(10, TimeUnit.SECONDS));
        ClassLoader originalLoader = Thread.currentThread().getContextClassLoader();
        ClassLoader callerLoader = new ClassLoader(null) { };
        Thread.currentThread().setContextClassLoader(callerLoader);
        try {
            IllegalStateException error = assertThrows(IllegalStateException.class, () ->
                    bridge.close(Duration.ofMillis(20)));
            assertTrue(error.getMessage().contains("timed out after PT0.02S"), error.getMessage());
            assertTrue(error.getMessage().contains("cleanup remains pending"), error.getMessage());
            assertNull(error.getCause());
            assertFalse(bridge.available());

            CompletionStage<Void> pending = bridge.closeAsync();
            assertFalse(pending.toCompletableFuture().isDone());
            release.complete(null);
            pending.toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertEquals("held", held.get(10, TimeUnit.SECONDS));
            assertSame(callerLoader, Thread.currentThread().getContextClassLoader());
        } finally {
            release.complete(null);
            held.get(10, TimeUnit.SECONDS);
            Thread.currentThread().setContextClassLoader(originalLoader);
        }
    }

    @Test
    void interruptedCleanupWaitRestoresInterruptFlagAndLeavesCleanupPending()
            throws Exception {
        SpringDynamicBridge<Api> bridge = SpringDynamicBridge.mount(
                runtime,
                runtime.root(),
                "interrupted-cleanup",
                SOURCE,
                BRIDGED,
                Duration.ofSeconds(5));
        CountDownLatch callEntered = new CountDownLatch(1);
        CompletableFuture<Void> release = new CompletableFuture<>();
        runtime.publish(SOURCE, new GatedApi("held", release, callEntered))
                .awaitSettled(Duration.ofSeconds(10));

        CompletableFuture<String> held = bridge.proxy().valueAsync().toCompletableFuture();
        assertTrue(callEntered.await(10, TimeUnit.SECONDS));
        CountDownLatch callerReady = new CountDownLatch(1);
        CountDownLatch callerFinished = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread caller = new Thread(() -> {
            callerReady.countDown();
            try {
                bridge.close(Duration.ofSeconds(10));
            } catch (Throwable error) {
                failure.set(error);
            } finally {
                callerFinished.countDown();
            }
        }, "bridge-close-caller");
        try {
            caller.start();
            assertTrue(callerReady.await(10, TimeUnit.SECONDS));
            caller.interrupt();
            assertTrue(callerFinished.await(10, TimeUnit.SECONDS));

            IllegalStateException error = assertInstanceOf(
                    IllegalStateException.class, failure.get());
            assertTrue(error.getMessage().contains("wait was interrupted"), error.getMessage());
            assertTrue(error.getMessage().contains("cleanup remains pending"), error.getMessage());
            assertNull(error.getCause());
            assertTrue(caller.isInterrupted());
            assertFalse(bridge.closeAsync().toCompletableFuture().isDone());
        } finally {
            release.complete(null);
            caller.join(10_000);
            held.get(10, TimeUnit.SECONDS);
        }
    }


    private ConfiguredMountHandle<ProviderConfig> mountTrackedProvider(
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

    private static final class SlowStartupHandle implements MountHandle {
        private final CompletableFuture<Void> release = new CompletableFuture<>();

        @Override
        public String handleId() {
            return "slow-startup-handle";
        }

        @Override
        public String mountId() {
            return "slow-startup";
        }

        @Override
        public String componentId() {
            return "slow-startup-component";
        }

        @Override
        public String factoryId() {
            return "slow-startup-factory";
        }

        @Override
        public String contextId() {
            return "slow-startup-context";
        }

        @Override
        public ComponentState state() {
            return ComponentState.STARTING;
        }

        @Override
        public ComponentGoal goal() {
            return ComponentGoal.RUNNING;
        }

        @Override
        public long configRevision() {
            return 0;
        }

        @Override
        public CompletionStage<ComponentState> whenSettled() {
            return release.thenApply(ignored -> ComponentState.ACTIVE);
        }

        @Override
        public CompletionStage<ComponentState> retryAsync() {
            return CompletableFuture.completedFuture(ComponentState.FAILED);
        }

        @Override
        public CompletionStage<ComponentState> disposeAsync() {
            return CompletableFuture.completedFuture(ComponentState.DISPOSED);
        }
    }
}
