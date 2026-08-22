# Knotra 测试指南

> 面向单元测试与集成测试编写，介绍状态断言、依赖热重载、清理重试与类加载器垃圾回收的验证模式。

---

## 测试核心原则

- **断言状态与代际，绝不断言调度时机**：优先检查 `handle.state() == ACTIVE`、`handle.requireActive()`、`DiagnosticCode` 与 `generation`，避免使用 `Thread.sleep` 猜测执行时机。
- **使用有界等待**：等待异步收敛时使用 `whenSettled().toCompletableFuture().get(5, TimeUnit.SECONDS)` 或 Awaitility。
- **测试资源必须闭环释放**：在 `@AfterEach` 中调用 `runtime.close()`，确保测试用例之间环境隔离。

```bash
mvn test
mvn clean verify
```

---

## 普通 POJO 组件单元测试

验证提供方被 `replace()` 替换时，消费方能否自动以新依赖完成代际重建：

```java
import io.knotra.*;
import io.knotra.beans.*;
import org.junit.jupiter.api.*;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.junit.jupiter.api.Assertions.*;

class GreeterComponentTest {

    static final CapabilityKey<String> PREFIX = CapabilityKey.of("test.prefix", String.class);
    static final CapabilityKey<Greeter> GREETER = CapabilityKey.of("test.greeter", Greeter.class);

    private KnotraRuntime runtime;

    @BeforeEach
    void setUp() {
        runtime = KnotraRuntime.create();
    }

    @AfterEach
    void tearDown() {
        runtime.close();
    }

    @Test
    void providerReplacementRecreatesConsumer() {
        Provided<String> prefix = runtime.provide(PREFIX, "Hello");

        CopyOnWriteArrayList<Greeter> instances = new CopyOnWriteArrayList<>();
        BeanDefinition<NoConfig, Greeter> definition = Beans.component("greeter")
                .with(Beans.required(PREFIX))
                .create(p -> {
                    Greeter g = new Greeter(p);
                    instances.add(g);
                    return g;
                })
                .provide(GREETER)
                .build();

        ComponentHandle<NoConfig> handle = Beans.mount(runtime, definition);
        handle.requireActive();

        assertEquals(1, instances.size());
        assertEquals("Hello Alice", instances.get(0).greet("Alice"));

        // 热替换底层依赖为 V2
        Provided<String> nextPrefix = prefix.replace("Bonjour");
        nextPrefix.whenSettled().toCompletableFuture().join();
        handle.requireActive();

        // 验证消费方自动重建并生成新实例
        assertEquals(2, instances.size());
        assertEquals("Bonjour Alice", instances.get(1).greet("Alice"));
    }

    static final class Greeter {
        private final String prefix;
        Greeter(String prefix) { this.prefix = prefix; }
        String greet(String name) { return prefix + " " + name; }
    }
}
```

---

## 故障恢复与清理重试测试

验证清理异常时系统保留现场，并在修复后支持精准重试：

```java
@Test
void cleanupFailureRetainsDiagnosticsAndRecoversOnRetry() {
    AtomicBoolean shouldFail = new AtomicBoolean(true);

    BeanDefinition<NoConfig, AutoCloseable> def = Beans.component("flaky-resource")
            .create(() -> (AutoCloseable) () -> {
                if (shouldFail.get()) {
                    throw new RuntimeException("清理网络连接超时");
                }
            })
            .build();

    ComponentHandle<NoConfig> handle = Beans.mount(runtime, def);
    handle.requireActive();

    // 触发销毁，模拟清理失败
    handle.disposeAsync().toCompletableFuture().join();
    assertEquals(ComponentState.FAILED, handle.state());

    RuntimeSnapshot snapshot = runtime.snapshot();
    assertTrue(snapshot.diagnostics().stream()
            .anyMatch(d -> d.code() == DiagnosticCode.CLEANUP_FAILED));

    // 修复问题后触发重试
    shouldFail.set(false);
    handle.retryAsync().toCompletableFuture().join();

    assertEquals(ComponentState.DISPOSED, handle.state());
}
```

---

## 类加载器卸载与垃圾回收测试

在 CI 中验证卸载插件后，其 ClassLoader 能被 JVM 正常回收，防止 Metaspace 内存泄漏：

```java
@Test
void pluginClassLoaderIsGarbageCollectedAfterUnload() throws Exception {
    Pf4jArtifactAdapter adapter = Pf4jArtifactAdapter.create(
            pluginsDir, runtime, Set.of("com.example.contract"));

    ArtifactSnapshot artifact = adapter.loadArtifact(pluginJarPath);
    ClassLoader pluginClassLoader = getPluginClassLoader(adapter, artifact.artifactId());

    // 通过弱引用监听类加载器生命周期
    WeakReference<ClassLoader> weakRef = new WeakReference<>(pluginClassLoader);
    pluginClassLoader = null; // 释放局部强引用

    // 卸载插件
    adapter.unloadArtifact(artifact.artifactId());
    adapter.close();

    // 触发垃圾回收并轮询断言
    boolean collected = false;
    for (int i = 0; i < 50; i++) {
        System.gc();
        if (weakRef.get() == null) {
            collected = true;
            break;
        }
        Thread.sleep(50);
    }

    assertTrue(collected, "插件 ClassLoader 必须在卸载后被 JVM GC 回收，防止 Metaspace OOM。");
}
```
