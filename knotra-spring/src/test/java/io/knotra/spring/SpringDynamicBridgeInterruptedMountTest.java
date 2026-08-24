package io.knotra.spring;

import io.knotra.ActivationContext;
import io.knotra.AdvancedRuntime;
import io.knotra.CapabilityKey;
import io.knotra.CapabilityRequirement;
import io.knotra.Component;
import io.knotra.ComponentDescriptor;
import io.knotra.ComponentFactory;
import io.knotra.ComponentState;
import io.knotra.ContextHandle;
import io.knotra.KnotraRuntime;
import io.knotra.MountFactory;
import io.knotra.MountHandle;
import io.knotra.NoConfig;
import io.knotra.RegistrationHandle;
import io.knotra.RuntimeTransaction;
import io.knotra.StagedRegistration;
import io.knotra.TransactionReceipt;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SpringDynamicBridgeInterruptedMountTest {

    static final CapabilityKey<Api> SOURCE = CapabilityKey.of("interrupted.source", Api.class);
    static final CapabilityKey<Api> BRIDGED = CapabilityKey.of("interrupted.bridge", Api.class);

    KnotraRuntime runtime = KnotraRuntime.create();

    @AfterEach
    void tearDown() {
        runtime.close();
    }

    interface Api {
        String value();
    }

    @Test
    void interruptedStartupWaitRestoresFlagAndConvergesCompensatingCleanup() throws Exception {
        CountDownLatch startEntered = new CountDownLatch(1);
        CountDownLatch startupWaitEntered = new CountDownLatch(1);
        CountDownLatch cleanupEntered = new CountDownLatch(1);
        CountDownLatch callerFinished = new CountDownLatch(1);
        CompletableFuture<Void> startRelease = new CompletableFuture<>();
        CompletableFuture<Void> cleanupRelease = new CompletableFuture<>();
        AtomicInteger cleanupCount = new AtomicInteger();
        AtomicReference<MountHandle> mountedHandle = new AtomicReference<>();

        MountFactory factory = new GatedFactory(
                startEntered,
                startRelease,
                cleanupEntered,
                cleanupRelease,
                cleanupCount);
        KnotraRuntime bridgedRuntime = new FactoryReplacingRuntime(runtime, factory, mountedHandle, startupWaitEntered);

        ClassLoader originalLoader = Thread.currentThread().getContextClassLoader();
        ClassLoader callerLoader = new ClassLoader(null) { };
        AtomicReference<Throwable> mountFailure = new AtomicReference<>();
        AtomicReference<ClassLoader> callerLoaderAfterMount = new AtomicReference<>();

        Thread caller = new Thread(() -> {
            Thread.currentThread().setContextClassLoader(callerLoader);
            try {
                SpringDynamicBridge.mount(
                        bridgedRuntime,
                        "interrupted-mount",
                        SOURCE,
                        BRIDGED,
                        Duration.ofSeconds(10));
            } catch (Throwable error) {
                mountFailure.set(error);
            } finally {
                callerLoaderAfterMount.set(Thread.currentThread().getContextClassLoader());
                callerFinished.countDown();
            }
        }, "bridge-mount-caller");

        try {
            caller.start();
            assertTrue(startEntered.await(10, TimeUnit.SECONDS));
            assertTrue(startupWaitEntered.await(10, TimeUnit.SECONDS));

            caller.interrupt();
            assertTrue(callerFinished.await(10, TimeUnit.SECONDS));

            IllegalStateException error = assertInstanceOf(
                    IllegalStateException.class, mountFailure.get());
            assertTrue(error.getMessage().contains("dynamic bridge startup failed"), error.getMessage());
            assertTrue(error.getMessage().contains(
                    "dynamic bridge startup cleanup wait was interrupted"), error.getMessage());
            assertTrue(caller.isInterrupted());
            assertSame(callerLoader, callerLoaderAfterMount.get());
            assertEquals(0, cleanupCount.get());

            startRelease.complete(null);
            assertTrue(cleanupEntered.await(10, TimeUnit.SECONDS));
            cleanupRelease.complete(null);

            MountHandle handle = mountedHandle.get();
            assertEquals(ComponentState.DISPOSED, handle.whenSettled()
                    .toCompletableFuture().get(10, TimeUnit.SECONDS));
            assertEquals(1, cleanupCount.get());
            assertFalse(runtime.root().view().find(BRIDGED).isPresent());
            assertTrue(runtime.advanced().snapshot().mounts().stream()
                    .noneMatch(mount -> "interrupted-mount".equals(mount.mountId())));
        } finally {
            startRelease.complete(null);
            cleanupRelease.complete(null);
            caller.join(10_000);
            Thread.currentThread().setContextClassLoader(originalLoader);
        }
    }

    private static final class GatedFactory implements MountFactory {
        private final String factoryId = "spring-dynamic-bridge:interrupted-mount";
        private final CountDownLatch startEntered;
        private final CompletableFuture<Void> startRelease;
        private final CountDownLatch cleanupEntered;
        private final CompletableFuture<Void> cleanupRelease;
        private final AtomicInteger cleanupCount;

        GatedFactory(
                CountDownLatch startEntered,
                CompletableFuture<Void> startRelease,
                CountDownLatch cleanupEntered,
                CompletableFuture<Void> cleanupRelease,
                AtomicInteger cleanupCount) {
            this.startEntered = startEntered;
            this.startRelease = startRelease;
            this.cleanupEntered = cleanupEntered;
            this.cleanupRelease = cleanupRelease;
            this.cleanupCount = cleanupCount;
        }

        @Override
        public String factoryId() {
            return factoryId;
        }

        @Override
        public Component<NoConfig> create() {
            return new GatedComponent(
                    startEntered,
                    startRelease,
                    cleanupEntered,
                    cleanupRelease,
                    cleanupCount);
        }
    }

    private static final class GatedComponent implements Component<NoConfig> {
        private final CountDownLatch startEntered;
        private final CompletableFuture<Void> startRelease;
        private final CountDownLatch cleanupEntered;
        private final CompletableFuture<Void> cleanupRelease;
        private final AtomicInteger cleanupCount;
        private final ComponentDescriptor descriptor;

        GatedComponent(
                CountDownLatch startEntered,
                CompletableFuture<Void> startRelease,
                CountDownLatch cleanupEntered,
                CompletableFuture<Void> cleanupRelease,
                AtomicInteger cleanupCount) {
            this.startEntered = startEntered;
            this.startRelease = startRelease;
            this.cleanupEntered = cleanupEntered;
            this.cleanupRelease = cleanupRelease;
            this.cleanupCount = cleanupCount;
            this.descriptor = ComponentDescriptor.named(
                    "spring-dynamic-bridge:interrupted-mount",
                    CapabilityRequirement.dynamicOptional(SOURCE));
        }

        @Override
        public ComponentDescriptor descriptor() {
            return descriptor;
        }

        @Override
        public void start(ActivationContext context, NoConfig config) throws Exception {
            context.lifecycle().onCloseAsync("startup cleanup gate", () -> {
                cleanupCount.incrementAndGet();
                cleanupEntered.countDown();
                return cleanupRelease;
            });
            startEntered.countDown();
            startRelease.get();
        }
    }

    private static final class FactoryReplacingRuntime implements KnotraRuntime {
        private final KnotraRuntime delegate;
        private final MountFactory replacementFactory;
        private final AtomicReference<MountHandle> mountedHandle;
        private final CountDownLatch startupWaitEntered;
        private final AdvancedRuntime advanced = new ReplacingAdvanced();

        FactoryReplacingRuntime(
                KnotraRuntime delegate,
                MountFactory replacementFactory,
                AtomicReference<MountHandle> mountedHandle,
                CountDownLatch startupWaitEntered) {
            this.delegate = delegate;
            this.replacementFactory = replacementFactory;
            this.mountedHandle = mountedHandle;
            this.startupWaitEntered = startupWaitEntered;
        }

        @Override
        public String runtimeId() {
            return delegate.runtimeId();
        }

        @Override
        public ContextHandle root() {
            return delegate.root();
        }

        @Override
        public AdvancedRuntime advanced() {
            return advanced;
        }

        @Override
        public CompletionStage<Void> closeAsync() {
            return delegate.closeAsync();
        }

        private final class ReplacingAdvanced implements AdvancedRuntime {
            @Override
            public io.knotra.RuntimeSnapshot snapshot() {
                return delegate.advanced().snapshot();
            }

            @Override
            public io.knotra.PendingOperationsSnapshot pendingOperations() {
                return delegate.advanced().pendingOperations();
            }

            @Override
            public <R> TransactionReceipt<R> transact(Function<RuntimeTransaction, R> transaction) {
                return delegate.advanced().transact(received ->
                        transaction.apply(new ReplacingTransaction(received)));
            }

            @Override
            public <T> io.knotra.PublicationChange<T> publication(
                    ContextHandle context,
                    CapabilityKey<T> key,
                    T value) {
                return delegate.advanced().publication(context, key, value);
            }

            @Override
            public ContextHandle childContext(ContextHandle parent, String name) {
                return delegate.advanced().childContext(parent, name);
            }
        }

        private final class ReplacingTransaction implements RuntimeTransaction {
            private final RuntimeTransaction delegate;

            ReplacingTransaction(RuntimeTransaction delegate) {
                this.delegate = delegate;
            }

            @Override
            public <T> StagedRegistration<T> provide(
                    ContextHandle context,
                    CapabilityKey<T> key,
                    T value) {
                return delegate.provide(context, key, value);
            }

            @Override
            public void revoke(RegistrationHandle registration) {
                delegate.revoke(registration);
            }

            @Override
            public ContextHandle childContext(ContextHandle parent, String name) {
                return delegate.childContext(parent, name);
            }

            @Override
            public MountHandle mount(
                    ContextHandle context,
                    String mountId,
                    ComponentFactory<NoConfig> factory) {
                MountHandle handle = delegate.mount(context, mountId, replacementFactory);
                mountedHandle.set(handle);
                return new ObservedMountHandle(handle, startupWaitEntered);
            }

            @Override
            public <C> io.knotra.ConfiguredMountHandle<C> mount(
                    ContextHandle context,
                    String mountId,
                    ComponentFactory<C> factory,
                    C config) {
                return delegate.mount(context, mountId, factory, config);
            }

            @Override
            public <C> io.knotra.ConfiguredMountHandle<C> mount(
                    ContextHandle context,
                    String mountId,
                    ComponentFactory<C> factory,
                    C config,
                    io.knotra.MountOptions options) {
                return delegate.mount(context, mountId, factory, config, options);
            }

            @Override
            public <C> io.knotra.ConfiguredMountHandle<C> reconfigure(
                    io.knotra.ConfiguredMountHandle<C> handle,
                    C config) {
                return delegate.reconfigure(handle, config);
            }

            @Override
            public void dispose(MountHandle handle) {
                delegate.dispose(handle);
            }

            @Override
            public void dispose(ContextHandle context) {
                delegate.dispose(context);
            }
        }
    }

    private record ObservedMountHandle(
            MountHandle delegate,
            CountDownLatch startupWaitEntered) implements MountHandle {

        @Override
        public String handleId() {
            return delegate.handleId();
        }

        @Override
        public String mountId() {
            return delegate.mountId();
        }

        @Override
        public String componentId() {
            return delegate.componentId();
        }

        @Override
        public String factoryId() {
            return delegate.factoryId();
        }

        @Override
        public String contextId() {
            return delegate.contextId();
        }

        @Override
        public ComponentState state() {
            return delegate.state();
        }

        @Override
        public io.knotra.ComponentGoal goal() {
            return delegate.goal();
        }

        @Override
        public long configRevision() {
            return delegate.configRevision();
        }

        @Override
        public CompletionStage<ComponentState> whenSettled() {
            startupWaitEntered.countDown();
            return delegate.whenSettled();
        }

        @Override
        public CompletionStage<ComponentState> retryAsync() {
            return delegate.retryAsync();
        }

        @Override
        public CompletionStage<ComponentState> disposeAsync() {
            return delegate.disposeAsync();
        }
    }
}
