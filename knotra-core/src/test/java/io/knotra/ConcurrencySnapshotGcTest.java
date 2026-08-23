package io.knotra;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.ref.WeakReference;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class ConcurrencySnapshotGcTest {
    static final CapabilityKey<String> X = CapabilityKey.of("x", String.class);
    static final CapabilityKey<String> Y = CapabilityKey.of("y", String.class);
    static final CapabilityKey<String> Z = CapabilityKey.of("z", String.class);

    KnotraRuntime runtime = KnotraRuntime.create();

    @AfterEach
    void tearDown() throws Exception {
        try {
            runtime.close();
        } catch (Exception ignored) {
            // 故意包含清理失败的测试在此处重试关闭
        }
    }

    private MountHandle mount(String id, TestKit.Start<NoConfig> start,
                                             CapabilityRequirement... requirements) {
        return TestKit.mount(runtime, runtime.root(), id, id, start, requirements);
    }

    @Test
    void concurrentMountsWithSameContextAndMountIdCommitOneHandle() throws Exception {
        AtomicInteger starts = new AtomicInteger();
        ComponentFactory<NoConfig> factory = TestKit.factory("same", new TestKit.Scripted<>(
                ComponentDescriptor.named("same"), (context, config) -> starts.incrementAndGet()));
        ExecutorService executor = Executors.newFixedThreadPool(16);
        try {
            var outcomes = new ArrayList<CompletableFuture<TransactionReceipt<MountHandle>>>();
            for (int i = 0; i < 16; i++) {
                outcomes.add(CompletableFuture.supplyAsync(() -> runtime.advanced().transact(transaction ->
                        transaction.mount(runtime.root(), "same", factory)), executor));
            }
            var committed = new ArrayList<TransactionReceipt<MountHandle>>();
            int rejected = 0;
            for (var future : outcomes) {
                try {
                    committed.add(future.get(10, TimeUnit.SECONDS));
                } catch (java.util.concurrent.ExecutionException error) {
                    assertInstanceOf(TransactionRejectedException.class, error.getCause());
                    rejected++;
                }
            }
            assertEquals(15, rejected);
            assertEquals(1, committed.size());
            assertEquals(ComponentState.ACTIVE, TestKit.settle(committed.getFirst().value()).call());
            assertEquals(1, starts.get());
        } finally {
            executor.shutdown();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void oneFactoryCreatesIndependentHandles() throws Exception {
        AtomicInteger starts = new AtomicInteger();
        ComponentFactory<NoConfig> factory = TestKit.factory("multi", new TestKit.Scripted<>(
                ComponentDescriptor.named("multi"), (context, config) -> starts.incrementAndGet()));
        var first = runtime.advanced().transact(mutation ->
                mutation.mount(runtime.root(), "one", factory, NoConfig.INSTANCE));
        var second = runtime.advanced().transact(mutation ->
                mutation.mount(runtime.root(), "two", factory, NoConfig.INSTANCE));
        TestKit.assertCommitted(first);
        TestKit.assertCommitted(second);
        assertNotEquals(first.value().handleId(), second.value().handleId());
        assertEquals(ComponentState.ACTIVE, TestKit.settle(first.value()).call());
        assertEquals(ComponentState.ACTIVE, TestKit.settle(second.value()).call());
        assertEquals(2, starts.get());
    }

    @Test
    void everyCommittedStructuralTransactionAdvancesGenerationByOne() {
        long generation = runtime.advanced().snapshot().generation();
        TestKit.provide(runtime, runtime.root(), X, "x");
        assertEquals(generation + 1, runtime.advanced().snapshot().generation());
        TestKit.child(runtime, runtime.root(), "child");
        assertEquals(generation + 2, runtime.advanced().snapshot().generation());
    }

    @Test
    void startingShadowProviderNeverAppearsBeforeAtomicCommit() throws Exception {
        ContextHandle child = TestKit.child(runtime, runtime.root(), "workspace");
        var consumer = TestKit.mount(runtime, child, "consumer",
                (context, config) -> assertNotNull(context.find(X)),
                CapabilityRequirement.required(X));
        var rootRegistration = TestKit.provide(runtime, runtime.root(), X, "root");
        assertEquals(ComponentState.ACTIVE, TestKit.settle(consumer).call());
        String originalActivation = TestKit.component(runtime, consumer).currentActivationId();

        CountDownLatch started = new CountDownLatch(1);
        CompletableFuture<Void> gate = new CompletableFuture<>();
        var provider = TestKit.mount(runtime, child, "provider", (context, config) -> {
            context.provide(X, "child");
            started.countDown();
            gate.get();
        });
        assertTrue(started.await(10, TimeUnit.SECONDS));
        RuntimeSnapshot before = runtime.advanced().snapshot();
        assertEquals("root", child.view().require(X));
        assertEquals(ComponentState.ACTIVE, TestKit.component(runtime, consumer).state());
        assertTrue(before.registrations().stream().noneMatch(registration ->
                registration.contextId().equals(child.contextId())));
        gate.complete(null);
        assertEquals(ComponentState.ACTIVE, TestKit.settle(provider).call());
        assertEquals("child", child.view().require(X));
        assertEquals(ComponentState.ACTIVE, TestKit.settle(consumer).call(),
                () -> runtime.advanced().snapshot().toString());
        assertNotEquals(originalActivation, TestKit.component(runtime, consumer).currentActivationId());
    }

    @Test
    void tentativeMultiEdgeShadowCycleIsRejectedAndKeepsRootBinding() throws Exception {
        TestKit.provide(runtime, runtime.root(), X, "root");
        ContextHandle child = TestKit.child(runtime, runtime.root(), "cycle");
        var first = TestKit.mount(runtime, child, "a",
                (context, config) -> {
                    context.require(X);
                    context.provide(Y, "a-y");
                }, CapabilityRequirement.required(X));
        assertEquals(ComponentState.ACTIVE, TestKit.settle(first).call());
        var second = TestKit.mount(runtime, child, "b",
                (context, config) -> {
                    context.require(Y);
                    context.provide(X, "b-x");
                }, CapabilityRequirement.required(Y));
        assertEquals(ComponentState.WAITING, TestKit.settle(second).call(),
                () -> runtime.advanced().snapshot().toString());
        assertTrue(runtime.advanced().snapshot().diagnostics().stream()
                .anyMatch(diagnostic -> diagnostic.code() == DiagnosticCode.BINDING_CYCLE),
                () -> runtime.advanced().snapshot().toString());
        assertTrue(child.view().find(Y).isPresent(),
                () -> runtime.advanced().snapshot().toString());
    }

    @Test
    void startingReconfigureWaitsForUserStartBeforeRollback() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CompletableFuture<Void> gate = new CompletableFuture<>();
        AtomicInteger starts = new AtomicInteger();
        ComponentFactory<Object> factory = TestKit.factory("configured", new TestKit.Scripted<>(
                ComponentDescriptor.named("configured"), (context, config) -> {
                    starts.incrementAndGet();
                    entered.countDown();
                    gate.get();
                }));
        var handle = runtime.advanced().transact(mutation ->
                mutation.mount(runtime.root(), "configured", factory, new Object())).value();
        assertTrue(entered.await(10, TimeUnit.SECONDS));
        var reconfigured = handle.reconfigureAsync(new Object());
        gate.complete(null);
        assertEquals(ComponentState.ACTIVE, reconfigured.toCompletableFuture().get(10, TimeUnit.SECONDS));
        assertEquals(2, starts.get());
    }

    @Test
    void failedCleanupRetryDoesNotRunANewActivationBeforeOldScopeSettles() throws Exception {
        AtomicBoolean failCleanup = new AtomicBoolean(true);
        AtomicInteger cleanupAttempts = new AtomicInteger();
        AtomicInteger starts = new AtomicInteger();
        ComponentFactory<NoConfig> factory = TestKit.factory("configured", new TestKit.Scripted<>(
                ComponentDescriptor.named("configured"), (context, config) -> {
                    starts.incrementAndGet();
                    context.lifecycle().onClose("cleanup", () -> {
                        cleanupAttempts.incrementAndGet();
                        if (failCleanup.getAndSet(false)) {
                            throw new IllegalStateException("cleanup failed");
                        }
                    });
                }));
        var handle = runtime.advanced().transact(mutation ->
                mutation.mount(runtime.root(), "configured", factory, NoConfig.INSTANCE)).value();
        assertEquals(ComponentState.ACTIVE, TestKit.settle(handle).call());
        assertEquals(ComponentState.FAILED, handle.disposeAsync().toCompletableFuture().get(10, TimeUnit.SECONDS));
        assertEquals(1, starts.get());
        assertEquals(ComponentState.DISPOSED, handle.retryAsync().toCompletableFuture().get(10, TimeUnit.SECONDS));
        assertEquals(2, cleanupAttempts.get());
        assertEquals(1, starts.get());
    }

    @Test
    void parentDisposeDisposesOwnershipDescendantsAndChildrenCannotRestart() throws Exception {
        AtomicReference<MountHandle> child = new AtomicReference<>();
        var parent = mount("parent", (context, config) ->
                child.set(context.mountChild("child",
                        TestKit.factory("child", new TestKit.Scripted<>(
                                ComponentDescriptor.named("child"), (unused, childConfig) -> {})),
                        NoConfig.INSTANCE)));
        assertEquals(ComponentState.ACTIVE, TestKit.settle(parent).call());
        assertEquals(ComponentState.ACTIVE, TestKit.settle(child.get()).call());
        parent.disposeAsync().toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertEquals(ComponentState.DISPOSED, child.get().state());
        assertTrue(child.get().retryAsync().toCompletableFuture().isCompletedExceptionally());
        assertTrue(runtime.advanced().snapshot().mounts().isEmpty());
    }

    @Test
    void failedRuntimeCloseCanBeRetriedAndThenRejectsMutations() throws Exception {
        AtomicBoolean failOnce = new AtomicBoolean(true);
        var handle = mount("close-retry", (context, config) ->
                context.lifecycle().onClose("bad", () -> {
                    if (failOnce.getAndSet(false)) {
                        throw new IllegalStateException("first close failure");
                    }
                }));
        assertEquals(ComponentState.ACTIVE, TestKit.settle(handle).call());
        assertTrue(assertThrows(java.util.concurrent.ExecutionException.class, () ->
                runtime.closeAsync().toCompletableFuture().get(10, TimeUnit.SECONDS))
                instanceof java.util.concurrent.ExecutionException);
        runtime.closeAsync().toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertEquals(ContextState.DISPOSED, runtime.root().state());
        TestKit.assertRejected(() -> runtime.advanced().transact(mutation ->
                mutation.provide(runtime.root(), Z, "z")),
                DiagnosticCode.INVALID_LIFECYCLE_OPERATION);
        assertTrue(runtime.root().view().find(Z).isEmpty());
        runtime.closeAsync().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @Test
    void snapshotContainsOnlyStableDtoValuesAndNoCapabilityValue() throws Exception {
        TestKit.provide(runtime, runtime.root(), X, "secret-x");
        var failed = mount("failure", (context, config) -> {
            throw new IllegalStateException("stable failure detail");
        });
        try {
            failed.requireActive(java.time.Duration.ofSeconds(5));
        } catch (MountNotActiveException expected) {
            // 在下方断言快照的稳定性。
        }
        var handle = mount("snapshot", (context, config) -> {
            context.lifecycle().onClose("entry", () -> {});
            context.provide(Y, "secret-y");
        });
        assertEquals(ComponentState.ACTIVE, TestKit.settle(handle).call());
        RuntimeSnapshot snapshot = runtime.advanced().snapshot();
        assertStableDto(snapshot);
        assertFalse(snapshot.toString().contains("secret-x"));
        assertFalse(snapshot.toString().contains("secret-y"));
        assertEquals(RuntimeSnapshot.RegistrationOwnerKind.HOST,
                snapshot.registrations().stream()
                        .filter(registration -> registration.capability().name().equals("x"))
                        .findFirst().orElseThrow().owner().kind());
        assertEquals(RuntimeSnapshot.RegistrationOwnerKind.ACTIVATION,
                snapshot.registrations().stream()
                        .filter(registration -> registration.capability().name().equals("y"))
                        .findFirst().orElseThrow().owner().kind());
        assertEquals(ComponentOrigin.Kind.HOST, snapshot.mounts().getFirst().origin().kind());
    }

    @Test
    void committedMountOptionsAreRecordedInMountSnapshot() {
        var result = runtime.advanced().transact(mutation -> mutation.mount(
                runtime.root(),
                "options",
                TestKit.factory("options", new TestKit.Scripted<>(
                        ComponentDescriptor.named("options"), (context, config) -> {})),
                NoConfig.INSTANCE,
                new MountOptions(java.util.Map.of("source", "test"))));
        TestKit.assertCommitted(result);
        assertEquals("test", new MountOptions(java.util.Map.of("source", "test")).metadata("source"));
        assertEquals(ComponentOrigin.Kind.HOST, TestKit.component(runtime, result.value()).origin().kind());
    }

    @Test
    void contextHostRegistrationRemovalImpactsExternalConsumers() throws Exception {
        ContextHandle child = TestKit.child(runtime, runtime.root(), "external");
        var registration = TestKit.provide(runtime, child, X, "child-x");
        var consumer = TestKit.mount(runtime, child, "consumer",
                (context, config) -> context.require(X), CapabilityRequirement.required(X));
        assertEquals(ComponentState.ACTIVE, TestKit.settle(consumer).call());
        TestKit.assertCommitted(runtime.advanced().transact(mutation -> {
            mutation.revoke(registration);
            return null;
        }));
        assertEquals(ComponentState.WAITING, TestKit.settle(consumer).call());
        assertTrue(runtime.root().view().find(X).isEmpty());
    }

    @Test
    void disposedComponentDoesNotStronglyReferenceItsClassLoader() throws Exception {
        WeakReference<URLClassLoader> loaderReference = mountAndDisposeComponentFromIsolatedLoader();
        for (int i = 0; i < 50 && loaderReference.get() != null; i++) {
            System.gc();
            Thread.onSpinWait();
        }
        assertNull(loaderReference.get(), "runtime retained disposed component ClassLoader");
    }

    @Test
    void shadowProviderRemovalReleasesWaitingOwnedChildClassLoader() throws Exception {
        Path directory = Files.createTempDirectory("knotra-owned-child-gc");
        compileWaitingChildFactory(directory);
        URLClassLoader loader = new URLClassLoader(
                new URL[]{directory.toUri().toURL()}, getClass().getClassLoader());
        AtomicReference<ClassLoader> activeLoader = new AtomicReference<>(loader);
        AtomicReference<MountHandle> child = new AtomicReference<>();
        ContextHandle workspace = TestKit.child(runtime, runtime.root(), "owned-child-gc");
        TestKit.provide(runtime, runtime.root(), X, "root");

        MountHandle consumer = TestKit.mount(
                runtime,
                workspace,
                "consumer",
                (context, config) -> {
                    ComponentFactory<NoConfig> factory = (ComponentFactory<NoConfig>)
                            Class.forName(
                                            "io.knotra.WaitingOwnedChildFactory",
                                            true,
                                            activeLoader.get())
                                    .getConstructor()
                                    .newInstance();
                    child.set(context.mountChild(
                            "waiting-child", factory, NoConfig.INSTANCE));
                    activeLoader.set(null);
                },
                CapabilityRequirement.required(X));
        assertEquals(ComponentState.ACTIVE, TestKit.settle(consumer).call());
        assertEquals(ComponentState.WAITING, TestKit.settle(child.get()).call());
        assertNull(activeLoader.get());
        RuntimeSnapshot oldSnapshot = runtime.advanced().snapshot();
        assertTrue(oldSnapshot.mounts().stream()
                .anyMatch(mount -> mount.handleId().equals(child.get().handleId())));

        MountHandle shadow = TestKit.mount(
                runtime,
                workspace,
                "shadow-provider",
                (context, config) -> context.provide(X, "shadow"));
        assertEquals(ComponentState.ACTIVE, TestKit.settle(shadow).call());
        assertEquals(ComponentState.DISPOSED, child.get().state());

        oldSnapshot = null;
        WeakReference<URLClassLoader> loaderReference = new WeakReference<>(loader);
        loader = null;
        awaitClassLoaderCollected(loaderReference);
    }

    @Test
    void pluginCapabilityKeyIdentityIsReleasedAndStillRejectsSameNameDifferentClass()
            throws Exception {
        WeakReference<URLClassLoader> firstReference =
                mountAndDisposeComponentFromIsolatedLoader();
        awaitClassLoaderCollected(firstReference);

        Path firstDirectory = Files.createTempDirectory("knotra-capability-gc-a");
        compileGcComponent(firstDirectory);
        URLClassLoader firstLoader = new URLClassLoader(
                new URL[]{firstDirectory.toUri().toURL()}, getClass().getClassLoader());
        var first = mountPluginCapabilityComponent(firstLoader, "capability-first");
        assertEquals(ComponentState.ACTIVE, first.whenSettled()
                .toCompletableFuture().get(10, TimeUnit.SECONDS));

        Path secondDirectory = Files.createTempDirectory("knotra-capability-gc-b");
        compileGcComponent(secondDirectory);
        URLClassLoader secondLoader = new URLClassLoader(
                new URL[]{secondDirectory.toUri().toURL()}, getClass().getClassLoader());
        try {
            TransactionRejectedException rejected = assertThrows(
                    TransactionRejectedException.class,
                    () -> mountPluginCapabilityComponent(secondLoader, "capability-second"));
            assertEquals(DiagnosticCode.CAPABILITY_TYPE_CONFLICT,
                    rejected.diagnostics().getFirst().code());
        } finally {
            secondLoader.close();
        }

        assertEquals(ComponentState.DISPOSED, first.disposeAsync()
                .toCompletableFuture().get(10, TimeUnit.SECONDS));
    }

    private MountHandle mountPluginCapabilityComponent(
            URLClassLoader loader, String mountId) throws Exception {
        Class<?> componentClass = loader.loadClass("io.knotra.GcComponent");
        ComponentFactory<NoConfig> factory = new IsolatedFactoryAdapter(
                componentClass.getDeclaredConstructor().newInstance());
        return runtime.advanced().transact(transaction -> transaction.mount(
                runtime.root(),
                mountId,
                factory,
                NoConfig.INSTANCE)).value();
    }

    private static void awaitClassLoaderCollected(WeakReference<URLClassLoader> reference) {
        for (int i = 0; i < 50 && reference.get() != null; i++) {
            System.gc();
            Thread.onSpinWait();
        }
        assertNull(reference.get(), "runtime retained plugin capability ClassLoader");
    }

    private WeakReference<URLClassLoader> mountAndDisposeComponentFromIsolatedLoader()
            throws Exception {
        Path directory = Files.createTempDirectory("knotra-gc");
        compileGcComponent(directory);
        URLClassLoader loader = new URLClassLoader(
                new URL[]{directory.toUri().toURL()},
                getClass().getClassLoader());
        Class<?> componentClass = loader.loadClass("io.knotra.GcComponent");
        ComponentFactory<NoConfig> factory = new IsolatedFactoryAdapter(
                componentClass.getDeclaredConstructor().newInstance());
        var result = runtime.advanced().transact(mutation -> mutation.mount(
                runtime.root(), "gc", factory, NoConfig.INSTANCE));
        TestKit.assertCommitted(result);
        assertEquals(ComponentState.ACTIVE, TestKit.settle(result.value()).call());
        assertEquals(ComponentState.DISPOSED, result.value().disposeAsync()
                .toCompletableFuture().get(10, TimeUnit.SECONDS));
        WeakReference<URLClassLoader> reference = new WeakReference<>(loader);
        result = null;
        componentClass = null;
        loader = null;
        return reference;
    }

    private static void compileWaitingChildFactory(Path directory) throws Exception {
        Files.createDirectories(directory.resolve("io/knotra"));
        Path source = directory.resolve("io/knotra/WaitingOwnedChildFactory.java");
        Files.writeString(source, """
                package io.knotra;

                public final class WaitingOwnedChildFactory
                        implements ComponentFactory<NoConfig> {
                    private static final CapabilityKey<String> MISSING =
                            CapabilityKey.of("owned-child-missing-capability", String.class);

                    @Override public String factoryId() { return "waiting-child"; }

                    @Override public Component<NoConfig> create() {
                        return new Component<>() {
                            @Override public ComponentDescriptor descriptor() {
                                return ComponentDescriptor.named(
                                        "waiting-child", CapabilityRequirement.required(MISSING));
                            }

                            @Override public void start(
                                    ActivationContext context, NoConfig config) {
                            }
                        };
                    }
                }
                """, StandardCharsets.UTF_8);
        JavaFileObject unit = new SimpleJavaFileObject(
                URI.create("string:///" + source),
                javax.tools.JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                try {
                    return Files.readString(source, StandardCharsets.UTF_8);
                } catch (Exception error) {
                    throw new IllegalStateException(error);
                }
            }
        };
        boolean success = ToolProvider.getSystemJavaCompiler().run(
                null, null, null,
                "-classpath", System.getProperty("java.class.path"),
                "-d", directory.toString(), source.toString()) == 0;
        assertTrue(success);
    }

    private static final class IsolatedFactoryAdapter implements ComponentFactory<NoConfig> {
        private final Object factory;

        private IsolatedFactoryAdapter(Object factory) {
            this.factory = factory;
        }

        @Override
        public String factoryId() {
            try {
                return (String) factory.getClass().getMethod("factoryId").invoke(factory);
            } catch (ReflectiveOperationException error) {
                throw new IllegalStateException(error);
            }
        }

        @Override
        public Component<NoConfig> create() {
            try {
                Object component = factory.getClass().getMethod("create").invoke(factory);
                Method descriptor = component.getClass().getMethod("descriptor");
                descriptor.setAccessible(true);
                Method start = component.getClass().getMethod(
                        "start",
                        ActivationContext.class,
                        Object.class);
                start.setAccessible(true);
                return new Component<>() {
                    @Override
                    public ComponentDescriptor descriptor() {
                        try {
                            return (ComponentDescriptor) descriptor.invoke(component);
                        } catch (ReflectiveOperationException error) {
                            throw new IllegalStateException(error);
                        }
                    }

                    @Override
                    public void start(ActivationContext context, NoConfig config) throws Exception {
                        try {
                            start.invoke(component, context, config);
                        } catch (ReflectiveOperationException error) {
                            throw new IllegalStateException(error);
                        }
                    }
                };
            } catch (ReflectiveOperationException error) {
                throw new IllegalStateException(error);
            }
        }
    }

    private static void compileGcComponent(Path directory) throws Exception {
        Files.createDirectories(directory.resolve("io/knotra"));
        Path source = directory.resolve("io/knotra/GcComponent.java");
        Files.writeString(source, """
                package io.knotra;

                public final class GcComponent implements ComponentFactory<NoConfig> {
                    public static final class Signal {}

                    private static final CapabilityKey<Signal> KEY =
                            CapabilityKey.of("plugin-defined-capability", Signal.class);

                    public static CapabilityKey<Signal> capabilityKey() {
                        return KEY;
                    }

                    @Override public String factoryId() { return "gc"; }
                    @Override public Component<NoConfig> create() {
                        return new Component<>() {
                            @Override public ComponentDescriptor descriptor() {
                                return ComponentDescriptor.named(
                                        "gc", CapabilityRequirement.optional(KEY));
                            }
                            @Override public void start(ActivationContext context, NoConfig config) {}
                        };
                    }
                }
                """, StandardCharsets.UTF_8);
        JavaFileObject unit = new SimpleJavaFileObject(URI.create("string:///" + source), javax.tools.JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                try {
                    return Files.readString(source, StandardCharsets.UTF_8);
                } catch (Exception error) {
                    throw new IllegalStateException(error);
                }
            }
        };
        boolean success = ToolProvider.getSystemJavaCompiler().run(
                null, null, null,
                "-classpath", System.getProperty("java.class.path"),
                "-d", directory.toString(), source.toString()) == 0;
        assertTrue(success);
        assertTrue(new File(directory.resolve("io/knotra/GcComponent.class").toString()).exists());
    }

    private static void assertStableDto(Object value) {
        if (value == null || value instanceof String || value instanceof Number
                || value instanceof Boolean || value instanceof Enum<?>
                || value instanceof java.time.temporal.Temporal) {
            return;
        }
        Class<?> type = value.getClass();
        boolean nestedDto = type.getName().startsWith("io.knotra.RuntimeSnapshot")
                || value instanceof RuntimeDiagnostic
                || value instanceof FailureInfo
                || value instanceof MountOptions
                || value instanceof ComponentOrigin;
        if (nestedDto) {
            for (Class<?> cursor = type; cursor != null && cursor != Object.class; cursor = cursor.getSuperclass()) {
                for (Field field : cursor.getDeclaredFields()) {
                    if (field.isSynthetic() || Modifier.isStatic(field.getModifiers())) {
                        continue;
                    }
                    field.setAccessible(true);
                    try {
                        assertStableDto(field.get(value));
                    } catch (IllegalAccessException error) {
                        throw new AssertionError(error);
                    }
                }
            }
            return;
        }
        if (value instanceof List<?> list) {
            list.forEach(ConcurrencySnapshotGcTest::assertStableDto);
            return;
        }
        if (value instanceof Map<?, ?> map) {
            map.keySet().forEach(ConcurrencySnapshotGcTest::assertStableDto);
            map.values().forEach(ConcurrencySnapshotGcTest::assertStableDto);
            return;
        }
        fail("snapshot contains non-DTO value: " + type.getName());
    }
}
