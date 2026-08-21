# Knotra 测试指南

Knotra 测试应验证状态与结构语义，不依赖调度时机。仓库自身的测试遵循三条规则：

1. 等待 `CompletionStage`，不要用 `Thread.sleep` 猜测收敛。
2. 断言稳定状态、generation、registration identity、Snapshot 和诊断码，不匹配错误文本。
3. 失败恢复必须验证“只重试未完成部分”，不能只验证最终成功。

## 最小测试夹具

无配置组件可以用小型 factory helper：

```java
static ComponentFactory<NoConfig> factory(
        String id,
        CapabilityRequirement[] requirements,
        ThrowingBiConsumer<ActivationContext, NoConfig> start) {
    return new ComponentFactory<>() {
        @Override
        public String factoryId() {
            return id;
        }

        @Override
        public Component<NoConfig> create() {
            return new Component<>() {
                @Override
                public ComponentDescriptor descriptor() {
                    return ComponentDescriptor.named(id, requirements);
                }

                @Override
                public void start(ActivationContext context, NoConfig config)
                        throws Exception {
                    start.accept(context, config);
                }
            };
        }
    };
}
```

挂载与等待：

```java
ComponentHandle<NoConfig> handle = runtime.mount(
        "consumer", consumerFactory);

assertEquals(ComponentState.ACTIVE,
        handle.whenSettled().toCompletableFuture()
                .get(10, TimeUnit.SECONDS));
```

无配置路径不传 `NoConfig.INSTANCE`。

## 依赖替换

测试 provider 替换应同时验证旧 registration 被撤销、消费方产生新 Activation，并只绑定新值：

```java
RegistrationHandle v1 = runtime.provide(GREETING, greeting("v1"));
ComponentHandle<NoConfig> consumer = runtime.mount(
        "greeter", consumerFactory);
assertEquals(ComponentState.ACTIVE, settle(consumer));

String oldActivation = component(runtime.snapshot(), consumer)
        .currentActivationId();

runtime.transact(tx -> {
    tx.revoke(v1);
    return tx.provide(runtime.root(), GREETING, greeting("v2"));
});

assertEquals(ComponentState.ACTIVE, settle(consumer));
String newActivation = component(runtime.snapshot(), consumer)
        .currentActivationId();
assertNotEquals(oldActivation, newActivation);
assertEquals("v2", observed.get());
```

不要只断言 `start()` 调用了两次；那无法证明旧 BindingSet 已经失效。

## 事务原子性

拒绝现在通过异常表达：

```java
long generation = runtime.snapshot().generation();

TransactionRejectedException failure = assertThrows(
        TransactionRejectedException.class,
        () -> runtime.transact(tx -> {
            tx.provide(runtime.root(), SERVICE, first);
            tx.provide(runtime.root(), SERVICE, duplicate);
            return null;
        }));

assertEquals(DiagnosticCode.CAPABILITY_SLOT_OCCUPIED,
        failure.diagnostics().getFirst().code());
assertEquals(generation, runtime.snapshot().generation());
assertTrue(runtime.root().view().find(SERVICE).isEmpty());
```

还应覆盖：

- callback 抛异常时零发布。
- 同事务 provisional Context/registration 可以被后续 intent 使用。
- 无结构变化的事务不增加 generation。
- Runtime closing 后新事务抛 `TransactionRejectedException`。
- typed normalizer 抛错或返回 null 时诊断为 `INVALID_CONFIG`。

## Stale Activation

用 latch 控制 `start()`：

1. 组件进入 `STARTING` 后阻塞。
2. 替换其 provider 或 reconfigure。
3. 放行旧 `start()`。
4. 断言旧 Activation 回滚，不产生业务 `ACTIVATION_FAILED`。
5. 断言新 Activation 绑定最新 registration/config。

旧候选已经 stale 时，即使旧 `start()` 随后抛业务异常，也应优先按 stale rollback 处理。

## LifecycleScope

验证 LIFO：

```java
List<String> order = new CopyOnWriteArrayList<>();
ComponentHandle<NoConfig> handle = runtime.mount("lifo", factory(
        "lifo", new CapabilityRequirement[0], (context, config) -> {
            context.lifecycle().onClose("first", () -> order.add("first"));
            context.lifecycle().onClose("last", () -> order.add("last"));
        }));

assertEquals(ComponentState.ACTIVE, settle(handle));
assertEquals(ComponentState.DISPOSED,
        handle.disposeAsync().toCompletableFuture().get());
assertEquals(List.of("last", "first"), order);
```

异步动作使用 `onCloseAsync`：

```java
context.lifecycle().onCloseAsync("consumer", this::closeConsumerAsync);
```

实现 `AsyncCloseable` 的资源直接 `manageAsync`：

```java
EventSubscription subscription = bus.subscribe(EVENT, listener);
context.lifecycle().manageAsync("listener", subscription);
```

## 清理失败与 close

失败条目保留，成功条目不重放：

```java
assertEquals(ComponentState.FAILED,
        handle.disposeAsync().toCompletableFuture().get());
assertEquals(1, successfulCleanupAttempts.get());
assertEquals(1, failedCleanupAttempts.get());

repair();
assertEquals(ComponentState.DISPOSED,
        handle.retryAsync().toCompletableFuture().get());
assertEquals(1, successfulCleanupAttempts.get());
assertEquals(2, failedCleanupAttempts.get());
```

必须单独覆盖 `close()`：

```java
IllegalStateException failure = assertThrows(
        IllegalStateException.class,
        handle::close);
assertEquals(ComponentState.FAILED, handle.state());
```

这可以防止同步 close 静默接受清理失败。

Context 清理失败正常返回 `ContextState.FAILED`，第二次 `disposeAsync()` 推进重试。Runtime close 则异常完成并保留执行器，重复 `closeAsync()` 继续收敛。

## EventBus

Definition 静态类型固定 mode：

```java
EventDefinition.Serial<JobFinished> event =
        EventDefinition.serial(JobFinished.class);
EventSubscription subscription = bus.subscribe(
        event,
        value -> CompletableFuture.completedFuture(true));
EventDispatch<JobFinished> dispatch = bus.dispatch(event, value)
        .toCompletableFuture().get();
```

不存在运行时 mode mismatch 测试；非法组合应当无法编译。行为测试应覆盖：

- Sync 注册顺序。
- Parallel 等待全部 listener。
- Serial stop、Bail claim、Waterfall value 传递。
- Listener 失败进入 `EventDispatch.failures()`。
- `unsubscribe()` 不等待 accepted work。
- Subscription/bus `closeAsync()` 等待 accepted work。
- 同名不同 exact Class 被拒，binding idle 后可由新 ClassLoader 重新绑定。
- Listener callback 使用 listener ClassLoader 作为临时 TCCL，结束后恢复。

## Loader

无配置与 raw 配置声明分开：

```java
ComponentTree tree = ComponentTree.of(
        ComponentEntry.of("metrics", METRICS_REF),
        ComponentEntry.configured("tool", TOOL_REF, rawConfig));
```

配置型 classpath factory 在 resolver 注册 decoder：

```java
ComponentFactoryResolver resolver = ClasspathFactoryResolver.builder()
        .add(TOOL_REF, toolFactory, rawToolDecoder)
        .add(METRICS_REF, metricsFactory)
        .build();
```

Loader 测试应验证：

- 全部 decoder 在结构变化前执行。
- decoder 失败返回 `CONFIG_INVALID`，新增项零挂载。
- 相同 `FactoryIdentity` 只 reconfigure，handle 不变。
- Identity 改变时旧 handle 先到 `DISPOSED`，再挂新实现。
- 新实现拒绝时补偿恢复旧实现。
- 清理失败产生 `BLOCKED` / `TEARDOWN_FAILED`，下一次 reconcile 只重试清理。
- start `FAILED` 不自动 retry，必须显式 `loader.retry(path)`。
- `requireConverged()` 在有诊断时抛 `ReconcileException`，合法 `WAITING` 不抛。

## PF4J 与官方桥接

直接 artifact 测试：

```java
plugins.loadArtifact(fixture);
ArtifactFactoryHandle<Config> factory = plugins.factories()
        .resolve("factory", Config.class)
        .orElseThrow();
Config config = factory.decodeConfig(raw);
ComponentHandle<Config> handle = factory.mount(
        runtime.root(), "component", config);
```

需要覆盖 token mismatch、decoder wrong type、normalizer 拒绝、catalog metadata 不可执行、wildcard handle 失效和 direct ownership。

官方 Loader bridge：

```java
KnotraLoader loader = KnotraLoader.over(
        runtime,
        runtime.root(),
        Pf4jFactoryResolver.of(plugins));
```

跨模块测试应证明：

- 宿主不提供 factoryId 到 `Class<C>` 映射也能 reconcile。
- 非空 `FactoryRef.version` 精确匹配 artifact version。
- Core `INVALID_CONFIG` 穿过 PF4J 和 bridge 后仍映射为 Loader `CONFIG_INVALID`。
- Reconcile mount 与 artifact drain 竞争时不留下 partial state。
- Cleanup 失败进入 `DRAIN_FAILED`，`retryDrainAsync` 只推进失败部分。
- Adapter、Loader 与 Runtime 并发 close 最终收敛。

## ClassLoader GC

GC 测试应保留弱引用并避免测试代码自己持有插件对象：

1. Load artifact 并挂载组件或 listener。
2. 保存 Runtime、Artifact、Loader、EventBus Snapshot。
3. Drain/unload artifact。
4. 清空 fixture coordinator 等宿主静态引用。
5. 使用 Awaitility 触发 GC，断言插件 ClassLoader 弱引用清零。

Snapshot 可以继续强引用；它们不得钉住插件 ClassLoader。

## 运行测试

```bash
mvn test
mvn clean verify
```

当前测试分布：Core 98、Events 44、PF4J 37、Loader 36、跨模块集成 15，共 230 项。
