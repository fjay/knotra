# Knotra API 与集成指南

本文面向 Knotra `0.1.0-SNAPSHOT` 的宿主、组件和插件开发者，只使用当前 public API。该版本要求 Java 21+、Maven 3.9+，尚未发布到 Maven Central。

## 模块选择

| 模块 | 使用场景 |
|---|---|
| `knotra-core` | Context、Capability、事务、组件生命周期与 Snapshot |
| `knotra-events` | 类型化 EventBus |
| `knotra-pf4j-spi` | 插件实现的 factory export SPI |
| `knotra-pf4j` | PF4J artifact 加载、目录、挂载、drain 与卸载 |
| `knotra-loader` | desired component tree reconcile |
| `knotra-pf4j-loader` | PF4J catalog 到 Loader 的官方 resolver |

最小宿主只依赖 Core：

```xml
<dependency>
  <groupId>io.knotra</groupId>
  <artifactId>knotra-core</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

插件通常以 provided 方式依赖 Core、PF4J SPI 和 PF4J。宿主同时使用插件与 Loader 时再引入 `knotra-pf4j-loader`。

## Capability 与组件

Capability 由稳定名称和精确 JVM 引用类型组成：

```java
public interface Tool {
    String run(String input);
}

public static final CapabilityKey<Tool> TOOL =
        CapabilityKey.of("app.tool", Tool.class);
```

同一个 Runtime 生命周期内，同名 Capability 固定为同一个 Java 类型。Context 中同名 slot 只有一个当前 registration；子 Context 的本地 registration 可以遮蔽父级。

组件在 descriptor 中声明依赖，在 Activation 中读取固定 BindingSet：

```java
public final class RunnerFactory implements ComponentFactory<NoConfig> {
    @Override
    public Component<NoConfig> create() {
        return new Component<>() {
            @Override
            public ComponentDescriptor descriptor() {
                return ComponentDescriptor.of(
                        CapabilityRequirement.required(TOOL),
                        CapabilityRequirement.optional(METRICS));
            }

            @Override
            public void start(ActivationContext context, NoConfig config) {
                Tool tool = context.require(TOOL);
                Optional<Metrics> metrics = context.find(METRICS);
                startRunner(tool, metrics);
            }
        };
    }
}
```

`REQUIRED` 缺失时组件 settle 为 `WAITING`。`OPTIONAL` 缺失时可以启动，但 provider 出现或消失仍会产生新 BindingSet 并触发重新激活。

`ActivationContext.require/find` 访问未声明 key 会立即失败。宿主查询不受 descriptor 限制：

```java
Tool current = runtime.root().view().require(TOOL);
Optional<Tool> maybe = workspace.view().find(TOOL);
```

宿主读取不建立 Binding，不参与依赖追踪。

## 配置契约

Core 只接收类型化配置 `C`。Factory 可以校验并返回同类型规范值：

```java
public record ToolConfig(String command, int timeoutSeconds) {}

public final class ToolFactory implements ComponentFactory<ToolConfig> {
    @Override
    public ToolConfig normalizeConfig(ToolConfig config) {
        if (config.command().isBlank()) {
            throw new IllegalArgumentException("command must not be blank");
        }
        if (config.timeoutSeconds() < 1 || config.timeoutSeconds() > 60) {
            throw new IllegalArgumentException("timeoutSeconds must be in [1, 60]");
        }
        return new ToolConfig(config.command().trim(), config.timeoutSeconds());
    }

    @Override
    public Component<ToolConfig> create() {
        return new ToolComponent();
    }
}
```

直接 Core mount 的入参必须是 `ToolConfig`：

```java
ComponentHandle<ToolConfig> tool = runtime.mount(
        "main-tool", new ToolFactory(), new ToolConfig("wc -l", 10));
```

Map、JSON tree 和配置文件节点通过独立 `ConfigDecoder<C>` 在 Loader 或 PF4J export 边界转换：

```java
ConfigDecoder<ToolConfig> decoder = raw -> {
    Map<?, ?> map = (Map<?, ?>) raw;
    return new ToolConfig(
            (String) map.get("command"),
            ((Number) map.get("timeoutSeconds")).intValue());
};
```

Decoder 负责 raw 到 `C`，Factory normalizer 负责 `C` 到规范 `C`。两者不是同一个契约。

配置为 null、normalizer 抛错或返回 null 时，Core 使用 `DiagnosticCode.INVALID_CONFIG` 拒绝事务。

## 结构事务

根 Context 上的单操作有 convenience：

```java
RegistrationHandle registration = runtime.provide(TOOL, defaultTool());
ComponentHandle<ToolConfig> tool = runtime.mount(
        "tool", new ToolFactory(), new ToolConfig("convert", 5));
ComponentHandle<NoConfig> bus = runtime.mount(
        "event-bus", new EventBusFactory());
```

无配置 mount 不传 `NoConfig.INSTANCE`。组件实现内部仍使用 `ComponentFactory<NoConfig>` 与 `Component<NoConfig>` 获得明确的 unit 类型。

多个结构变化通过一次性 `RuntimeTransaction` 原子提交：

```java
TransactionReceipt<RegistrationHandle> replaced = runtime.transact(tx -> {
    tx.revoke(registration);
    tx.mount(runtime.root(), "worker", workerFactory);
    return tx.provide(runtime.root(), TOOL, replacementTool());
});
```

事务包含：

- `provide` / `revoke`
- `childContext`
- typed `mount` 与 no-config `mount`
- `reconfigure`
- Component 或 Context `dispose`

事务 callback 只记录意图，不能保存 `RuntimeTransaction` 跨线程复用。全部意图在最新结构草稿上校验，成功后只发布一次 generation。

成功返回：

```java
R value = receipt.value();
long generation = receipt.generation();
CompletionStage<Void> directSettlement = receipt.settlement();
```

拒绝不会返回 receipt：

```java
try {
    runtime.transact(tx -> tx.provide(runtime.root(), TOOL, duplicate));
} catch (TransactionRejectedException failure) {
    failure.diagnostics().forEach(this::report);
}
```

因此忽略 `transact(...)` 返回值也不会忽略失败。事务拒绝保证 generation 和已发布结构不变。

`settlement()` 只等待该事务直接触发的组件与 Context 过渡。它不是 Runtime 全局 quiescence；观察特定组件使用 `handle.whenSettled()`。

## Context

创建子 Context：

```java
ContextHandle workspace = runtime.transact(tx ->
        tx.childContext(runtime.root(), "workspace")).value();
```

查询结构和 Capability：

```java
String path = workspace.info().canonicalPath();
Tool local = workspace.view().require(TOOL);
ContextState state = workspace.state();
```

`ContextHandle` 是结构身份；`ContextView` 是只读 Capability 视图。根查询只有 `runtime.root().view()` 一条路径。

释放 Context 子树：

```java
ContextState settled = workspace.disposeAsync()
        .toCompletableFuture()
        .get(30, TimeUnit.SECONDS);
```

清理失败正常结算为 `ContextState.FAILED`，保留现场；修复后再次调用 `disposeAsync()`。`close()` 阻塞等待并要求最终为 `DISPOSED`。

## LifecycleScope

Activation 获得的所有可逆资源必须登记：

```java
Connection connection = context.lifecycle()
        .manage("connection", openConnection());
context.lifecycle().onClose("flush", this::flush);
context.lifecycle().onCloseAsync("consumer", this::stopConsumerAsync);

LifecycleScope staged = context.lifecycle().child("staged");
staged.manage("reader", openReader());

LifecycleScope parallel = context.lifecycle().parallelChild("workers");
parallel.onCloseAsync("worker-a", this::stopWorkerA);
parallel.onCloseAsync("worker-b", this::stopWorkerB);
```

同步 scope 按全局节点序列 LIFO 清理。`parallelChild` 只并行其直属条目，组本身仍参与外层 LIFO。

实现 `AsyncCloseable` 的资源直接托管：

```java
EventSubscription subscription = bus.subscribe(EVENT, listener);
context.lifecycle().manageAsync("listener", subscription);
```

清理失败不会中断其他条目。成功条目的 disposer 被释放，失败条目保留供 `retryAsync()` 重试。

## ComponentHandle

```java
ComponentState current = handle.state();
ComponentGoal goal = handle.goal();
long revision = handle.configRevision();

ComponentState updated = handle.reconfigureAsync(newConfig)
        .toCompletableFuture().join();
ComponentState retried = handle.retryAsync()
        .toCompletableFuture().join();
ComponentState disposed = handle.disposeAsync()
        .toCompletableFuture().join();
```

- `whenSettled()` 等待离开当前 `STARTING` / `STOPPING`，不阻止之后的新结构变化。
- `reconfigureAsync` 保留 handle，增加 config revision，并产生新 Activation。
- `retryAsync` 只接受 `FAILED` handle。启动失败会重启；清理失败只重试失败条目。
- `disposeAsync` 设置 goal 为 `DISPOSED`，递归释放 owned registration 和 child mount。
- `close()` 阻塞执行 dispose，最终不是 `DISPOSED` 时抛出异常。

## Snapshot 与诊断

`RuntimeSnapshot` 是不可变 DTO，包含：

- generation 与 Context tree
- component state、goal、config revision 与 origin
- Activation、Requirement 和 BindingSet
- registration owner
- LifecycleScope 和 managed entry cleanup state
- 稳定 `RuntimeDiagnostic`

Snapshot 不含 provider value、component、factory、disposer、listener、Throwable、Class 或 ClassLoader，可长期保存。

常见 Core 诊断：

| 诊断码 | 含义 |
|---|---|
| `INVALID_CONFIG` | 类型化配置校验或归一化失败 |
| `INVALID_MOUNT_ID` | mountId 为空或 slot 已占用 |
| `CAPABILITY_SLOT_OCCUPIED` | 当前 Context 的 Capability slot 已占用 |
| `CAPABILITY_TYPE_CONFLICT` | 同名 Capability 使用了不同 JVM 类型 |
| `MISSING_CAPABILITY` | REQUIRED binding 不可用 |
| `ACTIVATION_FAILED` | 组件 start 失败 |
| `CLEANUP_FAILED` | LifecycleScope 条目清理失败 |
| `BINDING_CYCLE` | 依赖图存在不能提交的环 |
| `NON_CONVERGENT_RECONCILE` | 自动收敛超过配置上限 |

调用方应匹配 enum，不要匹配 message 文本。

## EventBus

挂载并等待 EventBus provider：

```java
ComponentHandle<NoConfig> provider = runtime.mount(
        "event-bus", new EventBusFactory());
provider.whenSettled().toCompletableFuture().join();
```

事件 definition 同时固定事件名、JVM Class 和 mode：

```java
EventDefinition.Sync<JobStarted> STARTED =
        EventDefinition.sync(JobStarted.class);
EventDefinition.Serial<JobFinished> FINISHED =
        EventDefinition.serial("job.finished", JobFinished.class);
```

五种 mode：

| Definition 类型 | Listener | Dispatch 结果 |
|---|---|---|
| `Sync<T>` | `EventListener<T>` | 当前线程直接返回 `EventDispatch<T>` |
| `Parallel<T>` | `ParallelEventListener<T>` | listener 并发，stage 汇总 |
| `Serial<T>` | `SerialEventListener<T>` | 顺序执行，返回 false 停止 |
| `Bail<T>` | `BailEventListener<T>` | 第一个 true 认领并停止 |
| `Waterfall<T>` | `WaterfallEventListener<T>` | 前一个输出成为后一个输入 |

所有订阅统一调用 `subscribe`，所有分发统一调用 `dispatch`：

```java
EventSubscription subscription = bus.subscribe(FINISHED, event -> {
    persist(event);
    return CompletableFuture.completedFuture(true);
});

EventDispatch<JobFinished> report = bus.dispatch(FINISHED, event)
        .toCompletableFuture().join();
```

Definition 的静态类型决定正确 overload；不存在 mode mismatch 的运行时 API。Listener 业务失败进入 `report.failures()`，不会让已接受 dispatch 的 stage 异常完成。

`EventSubscription` 与 `EventBus` 都实现 `AsyncCloseable`。`closeAsync()` 拒绝新工作，并等待调用前已 accepted 的 dispatch；listener 回调中不能阻塞等待自身 close。

## PF4J artifact

插件 export：

```java
@Extension
public final class ToolProvider implements RuntimeComponentProvider {
    @Override
    public Collection<ExportedComponentFactory<?>> factories() {
        return List.of(
                ExportedComponentFactory.of(
                        ToolConfig.class,
                        raw -> decode(raw),
                        new ToolFactory()),
                ExportedComponentFactory.noConfig(new MetricsFactory()));
    }
}
```

`Class<C>` 与 Capability contract 必须来自宿主或共享 contract 包，不能是插件私有类型。

宿主创建 adapter：

```java
try (Pf4jArtifactAdapter plugins = Pf4jArtifactAdapter.create(
        Path.of("plugins"),
        runtime,
        Set.of("com.example.contract"))) {

    ArtifactSnapshot snapshot = plugins.loadArtifact(
            Path.of("plugins/tool.jar"));
}
```

异步动作对应 `loadArtifactAsync`、`unloadArtifactAsync` 和 `retryDrainAsync`，统一返回 `CompletionStage`。无后缀方法是阻塞 convenience。

唯一目录入口：

```java
plugins.factories().list();
plugins.factories().find("tool");
ArtifactFactoryHandle<?> dynamic =
        plugins.factories().resolve("tool").orElseThrow();
ArtifactFactoryHandle<ToolConfig> typed =
        plugins.factories().resolve("tool", ToolConfig.class).orElseThrow();
```

`find` 只返回稳定文本元数据。Wildcard executable handle 主要供官方 Loader bridge 捕获泛型；普通宿主优先 typed resolve。

直接挂载：

```java
ToolConfig config = typed.decodeConfig(rawConfig);
ComponentHandle<ToolConfig> handle = typed.mount(
        runtime.root(), "tool", config);
```

`decodeConfig` 校验 decoder 输出 exact token。Core mount 随后执行 factory 的 typed normalizer。

卸载执行 drain：

1. 拒绝该 artifact 的新 mount。
2. 等待 in-flight mount。
3. 按依赖顺序释放 owned component。
4. 停止并卸载 PF4J plugin。
5. 释放 catalog、factory 与 ClassLoader 引用。

清理失败进入 `DRAIN_FAILED`，修复后调用 `retryDrain`。`ArtifactOperationException` 携带 artifactId、phase 和可选 Core diagnostics。

## Loader

Classpath factory resolver：

```java
FactoryRef TOOL_REF = FactoryRef.of("tool", "1.0.0");

ComponentFactoryResolver classpath = ClasspathFactoryResolver.builder()
        .add(TOOL_REF, new ToolFactory(), rawToolDecoder)
        .add(FactoryRef.of("metrics"), new MetricsFactory())
        .build();
```

配置型 factory 必须提供 decoder；无配置 factory 直接 `add(ref, factory)`。

声明树明确区分无配置和 raw 配置：

```java
ComponentTree desired = ComponentTree.of(
        ComponentEntry.configured(
                "tools/main", TOOL_REF, rawToolConfig,
                ComponentEntry.of(
                        "worker", FactoryRef.of("worker"))),
        ComponentEntry.of(
                "metrics", FactoryRef.of("metrics")));
```

`ComponentEntry.of` 表示无配置；`configured` 要求非 null raw value。子 entry 使用相对单段路径时自动拼接父路径。

创建 Loader：

```java
try (KnotraLoader loader = KnotraLoader.over(
        runtime, runtime.root(), classpath)) {
    ReconcileResult result = loader.reconcile(desired);
    result.requireConverged();
}
```

`owned(runtime, resolver)` 创建 Loader 专属 base Context；`over(runtime, context, resolver)` 使用宿主给定 Context。

Reconcile 先完成路径、resolver、decoder 和冲突预检，再按“清理旧结构、创建新结构、重配置稳定 handle”执行。新增失败会回滚本批新增；已有 cleanup 失败可能产生 `BLOCKED` change，结果会明确记录已经提交的变化与剩余诊断。

`converged` 表示 desired state 无剩余差异和诊断，不承诺所有组件都是 `ACTIVE`。REQUIRED 缺失时 `WAITING` 仍可以是合法收敛状态。`requireConverged()` 在 false 时抛出携带 diagnostics 的 `ReconcileException`。

FAILED start 不自动重试：

```java
loader.retry("tools/main").requireConverged();
```

## PF4J 到 Loader

使用官方 bridge：

```java
ComponentFactoryResolver resolver = Pf4jFactoryResolver.of(plugins);

try (KnotraLoader loader = KnotraLoader.over(
        runtime, runtime.root(), resolver)) {
    loader.reconcile(desired).requireConverged();
}
```

无需宿主维护 factoryId 到 `Class<C>` 的映射。Bridge 会：

- 从 wildcard catalog handle 安全捕获 `C`。
- 精确匹配非空 `FactoryRef.version`。
- 调用 artifact export decoder。
- 生成包含 artifact 坐标、路径、factory 和 config token 的实现 fingerprint。
- 只使用 Loader 分配的 Context 与 mountId。
- 保留 Core 拒绝的结构化 diagnostics。

PF4J 优先并带 classpath fallback：

```java
ComponentFactoryResolver resolver = Pf4jFactoryResolver.withFallbacks(
        plugins,
        classpathResolver);
```

Artifact drain 与 reconcile 并发时，新 mount 被拒绝且本批回滚；下次 reconcile 可以命中新 artifact 或 fallback。

## 关闭顺序

推荐从外到内关闭：

```java
loader.closeAsync().toCompletableFuture().get(30, TimeUnit.SECONDS);
plugins.closeAsync().toCompletableFuture().get(30, TimeUnit.SECONDS);
runtime.closeAsync().toCompletableFuture().get(30, TimeUnit.SECONDS);
```

Try-with-resources 的关闭顺序与声明相反，因此通常先声明 Runtime，再声明 adapter 和 Loader。

| 入口 | 行为 |
|---|---|
| `RuntimeTransaction.dispose(handle/context)` | 与其他结构意图原子提交 |
| `ComponentHandle.disposeAsync()` | 等待单个组件及 owned subtree 清理，失败返回 `FAILED` |
| `ContextHandle.disposeAsync()` | 等待 Context 子树清理，失败返回 `FAILED` |
| `KnotraRuntime.closeAsync()` | 接管根 Context，失败 stage 异常完成，可再次 close |
| `Pf4jArtifactAdapter.closeAsync()` | drain 全部 artifact，失败保留现场，可再次 close |
| `KnotraLoader.closeAsync()` | 释放 Loader 所有结构并停止 coordinator |

不要假设 `closeAsync()` 被调用就已经关闭；应等待 stage，并在失败后读取 Snapshot 与 diagnostics。
