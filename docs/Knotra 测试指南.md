# Knotra 测试指南

> **面向读者**：编写动态组件系统与插件系统时，传统的单测往往容易写出“时序竞态”或“假死测试”。本文提供一套标准、可靠的 Knotra 测试套路，覆盖普通 POJO、依赖热替换、故障恢复与 ClassLoader 内存回收测试。

---

## Knotra 测试核心原则

- **断言状态与代际，绝不断言调度时机**：
  - 优先检查 `handle.state() == ACTIVE`、`handle.requireActive()`、`DiagnosticCode`、`generation`；绝不要用 `Thread.sleep(100)` 来猜组件什么时候启动完成。
- **使用有界等待（Await）**：
  - 等待异步收敛时，使用 `whenSettled().toCompletableFuture().get(5, TimeUnit.SECONDS)`。
- **每个测试用例必须关闭 Runtime**：
  - 在 `@AfterEach` 中调用 `runtime.close()`，确保测试之间彻底隔离，不泄漏虚拟线程与资源。

```bash
# 运行全部测试
mvn test

# 运行全量验证（包含真实 PF4J 插件构建与卸载验证）
mvn clean verify
```

---

## 普通 POJO 组件单元测试

测试核心逻辑：验证当底层 Provider 被 `replace()` 时，上层消费方是否自动以新依赖重新激活。

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
        // 发布初始依赖 V1
        Provided<String> prefix = runtime.provide(PREFIX, "Hello");

        // 装配并挂载消费方组件
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

        // 验证初始状态
        assertEquals(1, instances.size());
        assertEquals("Hello Alice", instances.get(0).greet("Alice"));

        // 热替换依赖为 V2
        Provided<String> nextPrefix = prefix.replace("Bonjour");
        nextPrefix.whenSettled().toCompletableFuture().join();
        handle.requireActive();

        // 验证消费方已自动以新依赖重新创建实例 (代际由 1 变为 2)
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

Knotra 的一大特色是：**清理失败不吞没，保留现场并支持幂等重试**。以下演示如何测试清理失败场景：

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

    // 尝试销毁组件，由于 shouldFail=true，清理会失败并进入 FAILED 状态
    handle.disposeAsync().toCompletableFuture().join();
    assertEquals(ComponentState.FAILED, handle.state());

    // 验证快照中记录了 CLEANUP_FAILED 诊断码
    RuntimeSnapshot snapshot = runtime.snapshot();
    assertTrue(snapshot.diagnostics().stream()
            .anyMatch(d -> d.code() == DiagnosticCode.CLEANUP_FAILED));

    // 修复故障并触发重试
    shouldFail.set(false);
    handle.retryAsync().toCompletableFuture().join();

    // 验证重试后成功进入终态 DISPOSED
    assertEquals(ComponentState.DISPOSED, handle.state());
}
```

---

## ClassLoader 卸载与 GC 回收验证测试

为了确保动态加载的插件 JAR 在卸载后不会造成 Metaspace 内存泄漏，可以编写如下的 GC 断言测试：

```java
@Test
void pluginClassLoaderIsGarbageCollectedAfterUnload() throws Exception {
    Pf4jArtifactAdapter adapter = Pf4jArtifactAdapter.create(
            pluginsDir, runtime, Set.of("com.example.contract"));

    // 加载插件
    ArtifactSnapshot artifact = adapter.loadArtifact(pluginJarPath);
    ClassLoader pluginClassLoader = getPluginClassLoader(adapter, artifact.artifactId());

    // 使用弱引用监听 ClassLoader
    WeakReference<ClassLoader> weakRef = new WeakReference<>(pluginClassLoader);
    pluginClassLoader = null; // 释放强引用

    // 卸载插件 (排空并清理)
    adapter.unloadArtifact(artifact.artifactId());
    adapter.close();

    // 循环触发 System.gc() 并断言弱引用被回收
    boolean collected = false;
    for (int i = 0; i < 50; i++) {
        System.gc();
        if (weakRef.get() == null) {
            collected = true;
            break;
        }
        Thread.sleep(50);
    }

    assertTrue(collected, "插件 ClassLoader 必须在卸载后被 JVM GC 回收，防止 Metaspace OOM！");
}
```
