# Knotra 测试指南

本文回答：**如何测试基于 Knotra 的组件、依赖替换、清理失败和插件卸载**。适用于 `0.1.0-SNAPSHOT`，全部示例只用公开 API。

## 原则

1. **用真实 Runtime 测试**。生命周期、绑定换代、清理顺序本身就是被测行为的一部分，mock 掉 Runtime 等于没测。每个测试创建独立的 `KnotraRuntime`，用 try-with-resources 或 `@AfterEach` 关闭。
2. **永远等待 settle，不要 sleep**。`whenSettled()` / `dispose()` / `reconcile()` 都返回可等待的 future；轮询快照时用 Awaitility。仓库自身的 227 项测试没有任何 `Thread.sleep`，你的测试也不需要。
3. **断言行为而不是实现**。组件消费了哪一代 provider，最可靠的断言是让组件记录它看到的值，而不是猜内部结构。
4. **测试结束时 close 应当成功**。`runtime.close()` 抛异常说明清理逻辑有 bug，应当让测试失败，而不是吞掉。

依赖（测试作用域）：

```xml
<dependency>
  <groupId>org.junit.jupiter</groupId>
  <artifactId>junit-jupiter</artifactId>
  <scope>test</scope>
</dependency>
<dependency>
  <groupId>org.awaitility</groupId>
  <artifactId>awaitility</artifactId>
  <scope>test</scope>
</dependency>
```

## 基本骨架：挂载并断言 ACTIVE

```java
class GreetingComponentTest {

    @Test
    void mountedComponentBecomesActive() throws Exception {
        try (KnotraRuntime runtime = KnotraRuntime.create()) {
            MutationResult<ComponentHandle<NoConfig>> mounted = runtime.mutate(tx ->
                    tx.mount(runtime.rootContext(), "greeter",
                            new GreetingFactory(), NoConfig.INSTANCE));
            assertTrue(mounted.committed(), mounted.diagnostics()::toString);

            ComponentState state = mounted.value().whenSettled()
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertEquals(ComponentState.ACTIVE, state);
        }
    }
}
```

检查 `committed()` 是固定动作：事务被拒时 `value()` 会抛 `IllegalStateException`。

## 测试依赖替换与重激活

让组件把它绑定到的 provider 值记录下来，替换 provider 后断言值变化、activation 换代：

```java
@Test
void providerReplacementReactivatesConsumer() throws Exception {
    try (KnotraRuntime runtime = KnotraRuntime.create()) {
        CapabilityKey<Greeting> GREETING =
                CapabilityKey.of("app.greeting", Greeting.class);
        AtomicReference<String> boundTo = new AtomicReference<>();
        AtomicInteger activations = new AtomicInteger();

        ComponentFactory<NoConfig> consumerFactory = new ComponentFactory<>() {
            @Override public String factoryId() { return "greeter"; }

            @Override public Component<NoConfig> create() {
                return new Component<>() {
                    @Override public ComponentDescriptor descriptor() {
                        return ComponentDescriptor.of("greeter",
                                CapabilityRequirement.required(GREETING));
                    }

                    @Override public void start(ActivationContext context, NoConfig config) {
                        activations.incrementAndGet();
                        boundTo.set(context.require(GREETING).version());
                    }
                };
            }
        };

        MutationResult<RegistrationHandle> v1 = runtime.mutate(tx ->
                tx.provide(runtime.rootContext(), GREETING, greeting("v1")));
        MutationResult<ComponentHandle<NoConfig>> consumer = runtime.mutate(tx ->
                tx.mount(runtime.rootContext(), "greeter", consumerFactory, NoConfig.INSTANCE));
        assertEquals(ComponentState.ACTIVE, consumer.value().whenSettled()
                .toCompletableFuture().get(10, TimeUnit.SECONDS));
        assertEquals("v1", boundTo.get());

        runtime.mutate(tx -> {
            tx.revoke(v1.value());
            return tx.provide(runtime.rootContext(), GREETING, greeting("v2"));
        });
        consumer.value().whenSettled().toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertEquals(2, activations.get());      // 旧运行收尾后按新注册重启
        assertEquals("v2", boundTo.get());
    }
}

record Greeting(String version) {}
```

同一次替换里"先 revoke 再 provide"是一个事务：绑定变化只触发一次重激活，不会出现中间空窗被误判为 `MISSING_CAPABILITY`。

## 测试清理失败与 retry

清理失败的语义：失败条目保留、组件进入 `FAILED`、`retry()` 只重试失败条目。

```java
@Test
void retryRepeatsOnlyFailedCleanup() throws Exception {
    try (KnotraRuntime runtime = KnotraRuntime.create()) {
        AtomicInteger goodCloses = new AtomicInteger();
        AtomicInteger badCloses = new AtomicInteger();

        ComponentFactory<NoConfig> factory = new ComponentFactory<>() {
            @Override public String factoryId() { return "flaky"; }

            @Override public Component<NoConfig> create() {
                return new Component<>() {
                    @Override public ComponentDescriptor descriptor() {
                        return ComponentDescriptor.of("flaky");
                    }

                    @Override public void start(ActivationContext context, NoConfig config) {
                        context.lifecycle().onClose("good", goodCloses::incrementAndGet);
                        context.lifecycle().onClose("bad", () -> {
                            if (badCloses.incrementAndGet() < 2) {
                                throw new IllegalStateException("temporary");
                            }
                        });
                    }
                };
            }
        };

        MutationResult<ComponentHandle<NoConfig>> mounted = runtime.mutate(tx ->
                tx.mount(runtime.rootContext(), "flaky", factory, NoConfig.INSTANCE));
        ComponentHandle<NoConfig> handle = mounted.value();
        assertEquals(ComponentState.ACTIVE, handle.whenSettled()
                .toCompletableFuture().get(10, TimeUnit.SECONDS));

        // 清理失败：dispose 正常完成为 FAILED，不会异常完成
        assertEquals(ComponentState.FAILED, handle.dispose()
                .toCompletableFuture().get(10, TimeUnit.SECONDS));
        assertTrue(runtime.snapshot().diagnostics().stream()
                .anyMatch(d -> d.code() == DiagnosticCode.CLEANUP_FAILED));

        // 修复外部条件后重试：good 条目不再重放，bad 条目重试成功
        ComponentState settled = handle.retry()
                .toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertEquals(ComponentState.DISPOSED, settled);
        assertEquals(1, goodCloses.get());
        assertEquals(2, badCloses.get());
    }
}
```

## 用门闩控制时序

测试竞态的正确姿势是用 `CountDownLatch` 冻结某个阶段，而不是 sleep：

```java
CountDownLatch startGate = new CountDownLatch(1);

// 组件 start() 里阻塞：startGate.await();
// 此时从测试线程发起 dispose，再断言过渡状态：
assertEquals(ComponentState.STOPPING, componentState(runtime, handle));

startGate.countDown();   // 放行 start，过渡继续推进
assertEquals(ComponentState.DISPOSED, handle.dispose()
        .toCompletableFuture().get(10, TimeUnit.SECONDS));
```

`componentState` 是对快照的薄封装（Knotra 没有发布测试工具包，建议在自己的工程里维护这个小助手）：

```java
static ComponentState componentState(KnotraRuntime runtime, ComponentHandle<?> handle) {
    return runtime.snapshot().components().stream()
            .filter(c -> c.handleId().equals(handle.handleId()))
            .findFirst()
            .orElseThrow()
            .state();
}
```

## 测试 reconfigure

```java
// 同一组件实例收到两次配置；activation 换代，configRevision 递增
MutationResult<ComponentHandle<GreetingConfig>> mounted = runtime.mutate(tx ->
        tx.mount(runtime.rootContext(), "greeter",
                new GreetingFactory(), new GreetingConfig("v1")));
ComponentHandle<GreetingConfig> handle = mounted.value();
assertEquals(ComponentState.ACTIVE, handle.whenSettled()
        .toCompletableFuture().get(10, TimeUnit.SECONDS));
assertEquals(1, handle.configRevision());

runtime.mutate(tx -> tx.reconfigure(handle, new GreetingConfig("v2")));
assertEquals(ComponentState.ACTIVE, handle.whenSettled()
        .toCompletableFuture().get(10, TimeUnit.SECONDS));
assertEquals(2, handle.configRevision());
```

断言"组件实例被复用、配置被更新"最直接的方式同样是让 `start()` 把收到的 config 记入 `AtomicReference`。

## 测试 ClassLoader 回收

插件卸载后断言 ClassLoader 弱可达，使用弱引用加显式 GC 循环（与仓库集成测试相同的姿势）：

```java
@Test
void unloadedPluginClassLoaderIsCollectable() throws Exception {
    try (KnotraRuntime runtime = KnotraRuntime.create();
         Pf4jArtifactAdapter adapter = Pf4jArtifactAdapter.create(
                 pluginsRoot, runtime, Set.of("com.example.contract"))) {

        adapter.loadArtifact(pluginJar).join();
        // 挂载、使用、卸载……
        adapter.unloadArtifact("greeting-plugin").join();

        ClassLoader pluginLoader = capturedDuringLoad;   // 在加载阶段通过插件代码捕获
        WeakReference<ClassLoader> ref = new WeakReference<>(pluginLoader);
        pluginLoader = null;

        for (int i = 0; i < 50 && ref.get() != null; i++) {
            System.gc();
            Thread.onSpinWait();
        }
        assertNull(ref.get());
    }
}
```

注意：测试里不能有其他强引用（局部变量、字段、lambda 捕获）指向插件对象或 loader，否则断言会假失败。

## 测试插件加载与卸载

集成层测试直接走真实 JAR（可以用测试 fixture 的构建方式在 `generate-test-resources` 阶段编译打包一个最小插件），断言用适配器公开状态：

```java
adapter.loadArtifact(pluginJar).join();
assertEquals(ArtifactState.ACTIVE, adapter.artifact("greeting-plugin")
        .orElseThrow().state());

adapter.unloadArtifact("greeting-plugin").join();
assertEquals(ArtifactState.UNLOADED, adapter.artifact("greeting-plugin")
        .orElseThrow().state());
```

drain 竞态测试思路：用门闩让某个 owned mount 的清理阻塞，发起 `unloadArtifact`，断言 artifact 处于 `DRAINING`；放行后断言 `UNLOADED`。若并发发起 reconcile，被 drain 的 mount 应失败回滚，下一次 reconcile 恢复本地实现。

## 常见坑

- **保存 `ActivationContext`**：`start()` 返回后 context 即关闭，存下来后续使用必错。需要长期状态就发布 capability。
- **组件字段跨代残留**：组件实例复用，上次 Activation 的字段若不在 `start()` 重置，会污染本次运行。
- **用 sleep 等收敛**：偶发通过、经常抖动。所有等待都有 future 或 Awaitility 入口。
- **吞掉 close 失败**：`runtime.close()` 异常 = 清理 bug，让测试红。
- **在诊断里匹配消息文本**：消息文本可能调整，按 `DiagnosticCode` 编程。

## 相关文档

- [Knotra API 与集成指南](<Knotra API 与集成指南.md>)：被测 API 的完整语义。
- [Knotra 线程模型与生产实践](<Knotra 线程模型与生产实践.md>)：时序相关断言背后的线程事实。
- [Knotra FAQ 与排障指南](<Knotra FAQ 与排障指南.md>)：测试中出现的诊断码含义。
