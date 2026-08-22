# Knotra 测试指南

Knotra 测试的重点不是验证方法调用顺序，而是验证代际、传播、排空、清理和 ClassLoader 边界。所有异步等待都必须有超时预算。

## 测试原则

1. POJO 用普通 JUnit 单测；只有装配和传播行为才启动 Runtime。
2. 每个测试创建独立 Runtime，`finally` 或 try-with-resources 关闭。
3. 生产代码等待 `awaitSettled(Duration)`；测试中的 CompletionStage 使用 `get(timeout, unit)`。
4. settlement 正常完成不代表全部 ACTIVE，断言目标状态而不是猜测“已完成”。
5. 失败清理允许进入 FAILED，显式 retry 后再断言 DISPOSED。
6. 插件测试区分共享合约 Class 与插件私有 Class，快照和诊断不得保留后者。

## Runtime 测试骨架

```java
final class RendererTest {

    @Test
    void dynamicProxyFollowsProviderReplacement() throws Exception {
        try (KnotraRuntime runtime = KnotraRuntime.create()) {
            PublicationChange<Greeting> first =
                    runtime.publish(Greeting.class, new ConstantGreeting("v1"));
            Publication<Greeting> greeting = first.publication();
            first.awaitSettled(Duration.ofSeconds(5));

            BeanDefinition<GreetingRenderer> definition = Beans
                    .component("renderer")
                    .with(Beans.dynamic(Greeting.class))
                    .create(GreetingRenderer::new)
                    .provideAs(RenderedGreeting.class, renderer -> renderer)
                    .build();

            MountHandle handle = definition.mount(runtime);
            handle.requireActive(Duration.ofSeconds(5));
            assertEquals("v1", render(runtime));

            PublicationChange<Greeting> second =
                    greeting.update(new ConstantGreeting("v2"));
            second.awaitSettled(Duration.ofSeconds(5));
            handle.requireActive(Duration.ofSeconds(5));
            assertEquals("v2", render(runtime));
        }
    }

    private String render(KnotraRuntime runtime) {
        return runtime.root().view().require(RenderedGreeting.class).render("test");
    }
}
```

完整可执行版本见 [QuickStartExample](../knotra-docs-examples/src/test/java/io/knotra/docs/QuickStartExample.java) 及其行为测试。

## 断言状态

`MountHandle.whenSettled()` 返回该挂载自身的 `ComponentState`，只等待这个挂载的生命周期过渡，不等待它拥有的子挂载；测试中应有界等待：

```java
ComponentState state = handle.whenSettled()
        .toCompletableFuture()
        .get(5, TimeUnit.SECONDS);

assertEquals(ComponentState.ACTIVE, state);
```

需要明确抛错语义时使用 `assertThrows`：

```java
MountNotActiveException error = assertThrows(
        MountNotActiveException.class,
        () -> handle.requireActive(Duration.ofSeconds(5)));
assertEquals(ComponentState.FAILED, error.state());
```

`requireActive(Duration)` 是最简洁的 ACTIVE 断言。不要用它替代 settlement 报告断言：操作 settlement（`PublicationChange`、`TransactionReceipt`、`Registration`）描述本次变更的影响集，并递归等待本次操作触发的 owned children；`requireActive` 和 `whenSettled()` 只描述一个挂载自身。测试父子挂载时按断言目标选择等待对象。

## Settlement 测试

固定依赖消费方启动失败时，settlement future 可能正常完成：

```java
PublicationChange<Greeting> change = publication.update(new BrokenGreeting());
SettlementReport report = change.awaitSettled(Duration.ofSeconds(5));

assertTrue(report.hasFailedMounts());
assertFalse(report.allAffectedActive());
assertEquals(
        ComponentState.FAILED,
        report.failedMounts().getFirst().state());

assertTrue(report.failedMounts().stream()
        .flatMap(outcome -> outcome.diagnostics().stream())
        .anyMatch(diagnostic -> diagnostic.code() == DiagnosticCode.ACTIVATION_FAILED));
```

父子挂载断言要区分等待对象：事务或 publication 的 operation settlement 会递归等待本次操作创建的 owned children；父挂载的 `whenSettled()` 变为 ACTIVE 不代表子挂载已收敛。

反向断言同样重要：

- 影响集为空时，`hasAffectedMounts()` 是 false。
- 影响集为空时，`hasFailedMounts()` 是 false。
- 影响集为空时，`allAffectedActive()` 仍是 false。
- 动态代理消费方不重建，可能不出现在影响集。
- 对具体挂载使用 `handle.requireActive(Duration.ofSeconds(5))`。


## Advanced 事务测试

事务测试应断言 staged token 的类型和 Context，并等待事务收据：

```java
TransactionReceipt<StagedRegistration<Message>> receipt =
        runtime.advanced().transact(transaction ->
                transaction.provide(runtime.root(), Message.class, message));

assertEquals(Message.class, receipt.value().key().type());
SettlementReport report = receipt.awaitSettled(Duration.ofSeconds(5));

assertEquals(message, runtime.root().view().require(Message.class));
assertTrue(report.generation() >= 0);
```

提交成功后，staged token 可以作为 opaque handle 撤销：

```java
runtime.advanced().revoke(receipt.value())
        .awaitSettled(Duration.ofSeconds(5));
assertTrue(runtime.root().view().find(Message.class).isEmpty());
```

不要断言 staged token 是 `Registration`，也不要期待它拥有 replace 或 settlement 方法。

## 清理失败与重试

构造一个前两次失败、第三次成功的 disposer：

```java
AtomicInteger attempts = new AtomicInteger();

BeanDefinition<ResourceBean> definition = Beans
        .component("flaky-resource")
        .create(ResourceBean::new)
        .destroyWith(resource -> {
            if (attempts.incrementAndGet() < 3) {
                throw new IllegalStateException("temporary cleanup failure");
            }
        })
        .build();

try (KnotraRuntime runtime = KnotraRuntime.create()) {
    MountHandle handle = definition.mount(runtime);
    handle.requireActive(Duration.ofSeconds(5));

    assertEquals(ComponentState.FAILED, handle.disposeAsync()
            .toCompletableFuture()
            .get(5, TimeUnit.SECONDS));

    assertEquals(ComponentState.DISPOSED, handle.retryAsync()
            .toCompletableFuture()
            .get(5, TimeUnit.SECONDS));
    assertEquals(3, attempts.get());
}
```

测试清理失败时不要在 `finally` 里吞异常。让第二次关闭失败，并在断言诊断后显式 retry，这更接近生产故障处理。

## 诊断断言

诊断按稳定诊断码和目标 ID 断言，不依赖 message 全文：

```java
RuntimeDiagnostic diagnostic = runtime.advanced().snapshot().diagnostics().stream()
        .filter(item -> item.code() == DiagnosticCode.ACTIVATION_FAILED)
        .filter(item -> item.targetId().equals(handle.handleId()))
        .findFirst()
        .orElseThrow();

FailureInfo failure = diagnostic.failure();
assertEquals(IllegalStateException.class.getName(), failure.exceptionType());
assertTrue(failure.message().contains("cannot connect"));
assertTrue(failure.stackTrace().size() <= policy.maxFrames());
```

`FailureInfo` 是有界 DTO，不保存异常对象。测试异常 message 可以包含类型和摘要，但不要把 `Throwable`、`Class` 或 `ClassLoader` 放进自定义诊断对象。

## 等待异步测试

不要使用 sleep 轮询。优先使用 Knotra 的 settlement：

```java
change.awaitSettled(Duration.ofSeconds(5));
handle.requireActive(Duration.ofSeconds(5));
```

测试自定义 CompletionStage 时：

```java
ComponentState state = handle.whenSettled()
        .toCompletableFuture()
        .get(5, TimeUnit.SECONDS);
```

如果使用 Awaitility，也必须设置明确超时和 poll interval：

```java
await().atMost(Duration.ofSeconds(5))
        .pollInterval(Duration.ofMillis(20))
        .until(() -> runtime.root().view().find(Output.class).isPresent());
```

## 插件与 Loader 测试

插件测试建议分三层：

1. 合约模块纯 JUnit：验证策略、解析和配置归一化。
2. 宿主集成：加载 artifact、解析 factory catalog、挂载并断言输出。
3. 卸载集成：断言 ownership 清空、drain 完成、artifact UNLOADED。

目录和快照断言：

```java
RuntimeSnapshot snapshot = runtime.advanced().snapshot();
snapshot.mounts().stream()
        .filter(mount -> mount.mountId().equals("payment.primary"))
        .findFirst()
        .orElseThrow();

LoaderSnapshot loaderSnapshot = loader.snapshot();
loaderSnapshot.entry("payment.primary").orElseThrow();
```

Loader reconcile 断言应同时检查结果和最终状态：

```java
ReconcileResult result = loader.reconcileAsync(desired)
        .toCompletableFuture()
        .get(30, TimeUnit.SECONDS);

assertTrue(result.converged(), result.diagnostics().toString());
assertEquals(ComponentState.ACTIVE, loader.snapshot()
        .entry("payment.primary")
        .orElseThrow()
        .state());
```

## ClassLoader GC 测试

插件 GC 测试需要区分两类引用：

- 共享合约 Class：宿主与插件共同依赖，允许存在于 `CapabilityKey` 与 `PublicationChange`。
- 插件私有 Class：只能存在于插件 Activation 存活期间，快照、诊断和报告不得保留。

测试套路：

1. 用独立 `URLClassLoader` 加载插件 fixture。
2. 挂载并断言 ACTIVE。
3. 释放挂载、drain 并卸载 artifact。
4. 断言 ownership、mount 和 activation 快照不再指向插件私有类型。
5. 丢弃业务强引用和 loader，触发 GC。
6. 用 `WeakReference` 断言 loader 被回收。

不要把插件返回的实例、异常、`Class`、`ClassLoader` 存进宿主静态字段。诊断只保留 `FailureInfo` 的有界文本。

## 常见错误

| 现象 | 常见原因 | 修正 |
|---|---|---|
| 测试偶发未 ACTIVE | 无界或过短等待后直接读状态 | 使用 `requireActive(Duration)` |
| settlement 完成但断言失败 | 误把收敛当全部成功 | 检查 `hasFailedMounts()` / failed outcome |
| Publication update 被拒绝 | 槽位 UNPUBLISHED 或 DISPLACED | 检查外部 revoke、Context 释放和 Runtime close |
| 清理后一直 FAILED | 释放动作失败 | 显式 `retryAsync()` 并断言最终状态 |
| 插件 loader 不回收 | 宿主持有私有 Class 或实例 | 检查静态字段、线程、全局注册表和日志对象 |
