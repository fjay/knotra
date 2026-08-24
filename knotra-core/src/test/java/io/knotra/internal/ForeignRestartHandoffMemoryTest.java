package io.knotra.internal;

import io.knotra.ComponentFactory;
import io.knotra.ComponentState;
import io.knotra.ConfiguredMountHandle;
import io.knotra.KnotraRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies the FOREIGN restart handoff becomes unreachable after convergence. */
final class ForeignRestartHandoffMemoryTest {
    private final KnotraRuntime runtime = KnotraRuntime.create();
    private final DefaultKnotraRuntime internal = (DefaultKnotraRuntime) runtime;

    @AfterEach
    void tearDown() {
        runtime.close();
    }

    @Test
    @Timeout(20)
    void completedHandoffDoesNotPinIsolatedComponentClassLoader() throws Exception {
        WeakReference<ClassLoader> loaderWeak = completeHandoffInIsolatedLoader();
        assertTrue(gcCleared(loaderWeak),
                "completed FOREIGN handoff retained isolated component class loader");
    }

    private WeakReference<ClassLoader> completeHandoffInIsolatedLoader() throws Exception {
        IsolatedRestartClassLoader loader = new IsolatedRestartClassLoader();
        Class<?> factoryClass = loader.loadClass(
                IsolatedRestartComponentFactory.class.getName());
        assertSame(loader, factoryClass.getClassLoader());

        CountDownLatch startEntered = new CountDownLatch(1);
        CountDownLatch releaseStart = new CountDownLatch(1);
        CountDownLatch completionCaptured = new CountDownLatch(1);
        AtomicBoolean firstStart = new AtomicBoolean();
        AtomicBoolean publicationFaulted = new AtomicBoolean();
        AtomicReference<Runnable> gatedCompletion = new AtomicReference<>();
        AtomicReference<ComponentRuntime.Reservation> foreign =
                new AtomicReference<>();

        try {
            @SuppressWarnings("unchecked")
            ComponentFactory<String> factory = (ComponentFactory<String>) factoryClass
                    .getConstructor(
                            AtomicBoolean.class,
                            CountDownLatch.class,
                            CountDownLatch.class)
                    .newInstance(firstStart, startEntered, releaseStart);
            ConfiguredMountHandle<String> handle =
                    runtime.advanced().transact(transaction -> transaction.mount(
                            runtime.root(),
                            "isolated-foreign-handoff",
                            factory,
                            "v1")).value();
            assertTrue(startEntered.await(10, TimeUnit.SECONDS),
                    "isolated component did not enter first start");

            internal.activationCoordinator().activationFailureCompletionGate =
                    (future, completion) -> {
                        gatedCompletion.set(completion);
                        completionCaptured.countDown();
                    };

            CompletableFuture<ComponentState> reconfigured =
                    handle.reconfigureAsync("v2").toCompletableFuture();
            // 结构事务同步提交后再注入；过早注入会让事务自身被拒绝。
            internal.activationCoordinator().transitionPublicationProbe = () -> {
                if (!publicationFaulted.getAndSet(true)) {
                    throw new IllegalStateException("injected publication fault");
                }
            };
            internal.activationCoordinator().cleanupFinalCommitProbe = () -> {
                if (foreign.get() == null) {
                    ComponentRuntime component = internal.publishedState()
                            .index.components.get(handle.handleId());
                    foreign.set(component.reserveTransition(
                            internal.activationCoordinator().scheduler().pendingTime(),
                            "isolated foreign restart owner"));
                }
            };
            releaseStart.countDown();
            assertTrue(completionCaptured.await(10, TimeUnit.SECONDS),
                    () -> "state=" + internal.componentState(handle.handleId())
                            + ", faulted=" + publicationFaulted.get()
                            + ", foreign=" + foreign.get());
            assertEquals(ComponentState.WAITING,
                    awaitState(handle.handleId(), ComponentState.WAITING));

            ComponentRuntime.Reservation owner = foreign.get();
            assertNotNull(owner, "foreign owner was not injected");
            ComponentRuntime component = internal.publishedState()
                    .index.components.get(handle.handleId());
            assertTrue(component.cancelTransition(owner.future()));
            internal.activationCoordinator().scheduler().completeCancelled(
                    List.of(owner.future()));
            assertEquals(ComponentState.ACTIVE,
                    awaitState(handle.handleId(), ComponentState.ACTIVE));

            runCaptured(gatedCompletion);
            assertThrows(ExecutionException.class,
                    () -> reconfigured.get(10, TimeUnit.SECONDS));
            assertEquals(ComponentState.DISPOSED, handle.disposeAsync()
                    .toCompletableFuture().get(10, TimeUnit.SECONDS));
            return new WeakReference<>(loader);
        } finally {
            releaseStart.countDown();
            runCaptured(gatedCompletion);
            internal.activationCoordinator().activationFailureCompletionGate = null;
            internal.activationCoordinator().transitionPublicationProbe = null;
            internal.activationCoordinator().cleanupFinalCommitProbe = null;
        }
    }

    private ComponentState awaitState(String handleId, ComponentState expected)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        ComponentState state = internal.componentState(handleId);
        while (state != expected) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("component did not converge to "
                        + expected + ", now " + state);
            }
            Thread.sleep(1);
            state = internal.componentState(handleId);
        }
        return state;
    }

    private static void runCaptured(AtomicReference<Runnable> completion) {
        Runnable captured = completion.get();
        if (captured != null) {
            captured.run();
            completion.set(null);
        }
    }

    private static boolean gcCleared(WeakReference<?> reference) {
        for (int attempt = 0; attempt < 100 && reference.get() != null; attempt++) {
            System.gc();
            System.runFinalization();
            try {
                Thread.sleep(10);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return reference.get() == null;
    }

    /** Defines only the factory/component pair locally; all API classes stay delegated. */
    private static final class IsolatedRestartClassLoader extends ClassLoader {
        private static final List<String> LOCAL_CLASSES = List.of(
                IsolatedRestartComponentFactory.class.getName(),
                IsolatedRestartComponent.class.getName());

        IsolatedRestartClassLoader() {
            super(ForeignRestartHandoffMemoryTest.class.getClassLoader());
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve)
                throws ClassNotFoundException {
            if (!LOCAL_CLASSES.contains(name)) {
                return super.loadClass(name, resolve);
            }
            Class<?> isolated = findLoadedClass(name);
            if (isolated == null) {
                isolated = defineIsolatedClass(name);
            }
            if (resolve) {
                resolveClass(isolated);
            }
            return isolated;
        }

        private Class<?> defineIsolatedClass(String name) throws ClassNotFoundException {
            String resource = name.replace('.', '/') + ".class";
            try (var input = getClass().getClassLoader().getResourceAsStream(resource)) {
                if (input == null) {
                    throw new ClassNotFoundException(name);
                }
                byte[] bytes = input.readAllBytes();
                return defineClass(name, bytes, 0, bytes.length);
            } catch (java.io.IOException error) {
                throw new ClassNotFoundException(name, error);
            }
        }
    }
}
