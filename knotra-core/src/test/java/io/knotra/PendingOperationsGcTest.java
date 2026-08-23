package io.knotra;

import org.junit.jupiter.api.Test;

import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.lang.ref.WeakReference;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

final class PendingOperationsGcTest {

    @Test
    void pendingSnapshotDoesNotRetainIsolatedComponentClassLoader() throws Exception {
        Path directory = Files.createTempDirectory("knotra-pending-gc");
        compileGcComponent(directory);
        URLClassLoader loader = new URLClassLoader(
                new URL[]{directory.toUri().toURL()},
                getClass().getClassLoader());
        MountHandle handle;
        KnotraRuntime runtime = KnotraRuntime.create();
        try {
            @SuppressWarnings("unchecked")
            ComponentFactory<NoConfig> factory = (ComponentFactory<NoConfig>)
                    loader.loadClass("io.knotra.IsolatedPendingComponent")
                            .getDeclaredConstructor()
                            .newInstance();
            handle = runtime.advanced().transact(transaction ->
                    transaction.mount(runtime.root(), "pending-gc", factory)).value();
            Class<?> isolated = loader.loadClass("io.knotra.IsolatedPendingComponent");
            isolated.getMethod("awaitStart").invoke(null);

            PendingOperationsSnapshot snapshot = runtime.advanced().pendingOperations();
            assertTrue(snapshot.operations().stream().anyMatch(operation ->
                    operation.kind() == PendingOperationsSnapshot.Kind.COMPONENT_TRANSITION
                            && operation.targetId().equals(handle.handleId())));

            isolated.getMethod("release").invoke(null);
            assertEquals(ComponentState.ACTIVE, handle.whenSettled()
                    .toCompletableFuture().get(10, TimeUnit.SECONDS));
            assertEquals(ComponentState.DISPOSED, handle.disposeAsync()
                    .toCompletableFuture().get(10, TimeUnit.SECONDS));
        } finally {
            Class<?> isolated = loader.loadClass("io.knotra.IsolatedPendingComponent");
            isolated.getMethod("release").invoke(null);
            try {
                runtime.close();
            } catch (Exception ignored) {
                // 失败时也不能让隔离类加载器因测试清理路径保持强引用。
            }
        }

        WeakReference<URLClassLoader> reference = new WeakReference<>(loader);
        loader = null;
        for (int index = 0; index < 100 && reference.get() != null; index++) {
            System.gc();
            Thread.onSpinWait();
        }
        assertNull(reference.get(), "pending snapshot retained isolated component ClassLoader");
    }

    private static void compileGcComponent(Path directory) throws Exception {
        Files.createDirectories(directory.resolve("io/knotra"));
        Path source = directory.resolve("io/knotra/IsolatedPendingComponent.java");
        Files.writeString(source, """
                package io.knotra;

                import java.util.concurrent.CompletableFuture;
                import java.util.concurrent.CountDownLatch;

                public final class IsolatedPendingComponent implements ComponentFactory<NoConfig> {
                    private static final CountDownLatch STARTED = new CountDownLatch(1);
                    private static final CompletableFuture<Void> GATE = new CompletableFuture<>();

                    public static void awaitStart() throws InterruptedException {
                        STARTED.await();
                    }

                    public static void release() {
                        GATE.complete(null);
                    }

                    @Override
                    public String factoryId() {
                        return "pending-gc";
                    }

                    @Override
                    public Component<NoConfig> create() {
                        return new Component<>() {
                            @Override
                            public ComponentDescriptor descriptor() {
                                return ComponentDescriptor.named("pending-gc");
                            }

                            @Override
                            public void start(ActivationContext context, NoConfig config)
                                    throws InterruptedException {
                                STARTED.countDown();
                                GATE.join();
                            }
                        };
                    }
                }
                """, StandardCharsets.UTF_8);
        JavaFileObject unit = new SimpleJavaFileObject(
                URI.create("string:///" + source), JavaFileObject.Kind.SOURCE) {
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
                "-d", directory.toString(),
                source.toString()) == 0;
        assertTrue(success);
    }
}
