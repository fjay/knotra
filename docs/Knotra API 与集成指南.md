# Knotra API 与集成指南

本文面向使用 Knotra `0.1.0-SNAPSHOT` 的宿主和组件开发者，只使用 public API。当前版本仅存在于本地 Maven reactor，未发布到远端仓库；请在同一 reactor 内引用，或先安装到本机 Maven 缓存。

## 模块与依赖

Knotra 要求 Java 21+、Maven 3.9+，groupId 为 `io.knotra`：

| 模块 | 用途 | 主要依赖 |
|---|---|---|
| `knotra-core` | Context、Capability、Component、Activation、LifecycleScope、Snapshot | 无运行时依赖 |
| `knotra-events` | 作为普通 Capability 发布的 EventBus | Core |
| `knotra-pf4j-spi` | 插件共享 SPI | Core、PF4J `provided` |
| `knotra-pf4j` | artifact 加载、typed mount、drain、ClassLoader guard | Core、SPI、PF4J、ASM |
| `knotra-loader` | 声明式 desired tree reconciliation | Core |
| `knotra-integration-tests` | 测试专用 | 不发布 |

最小宿主依赖 `knotra-core`；需要事件再加 `knotra-events`；需要插件再加 `knotra-pf4j` 与 SPI；需要声明式装配再加 `knotra-loader`。插件编译通常只需要 Core、SPI 和 PF4J，且三者对插件发行包来说应是 provided 边界。

```xml
<dependency>
  <groupId>io.knotra</groupId>
  <artifactId>knotra-core</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

## Capability、Component 与配置

Capability 是 typed contract。同一名称在整个 Runtime 生命周期内固定为一个 Java 引用类型；Context 中同名 slot 只有一个当前 registration。

```java
public record ToolConfig(String command, int timeoutSeconds) {}

public interface Tool { String run(String input); }

public static final CapabilityKey<Tool> TOOL =
        CapabilityKey.of("app.tool", Tool.class);
```

`ComponentFactory<C>` 创建组件，并可提供配置 schema。schema 校验并归一化配置，必须返回非空且类型正确的 `C` 对象；无配置使用 `NoConfig.INSTANCE`。

```java
public final class ToolFactory implements ComponentFactory<ToolConfig> {
    @Override public String factoryId() { return "tool"; }

    @Override public Component<ToolConfig> create() {
        return new Component<>() {
            @Override public ComponentDescriptor descriptor() {
                return ComponentDescriptor.of("tool");
            }
            @Override public void start(ActivationContext context, ToolConfig config) {
                Tool tool = new DefaultTool(config);
                context.lifecycle().manage("tool", (AutoCloseable) tool::close);
                context.provide(TOOL, tool);
            }
        };
    }

    @Override public Optional<ConfigSchema<ToolConfig>> configSchema() {
        return Optional.of(raw -> {
            if (!(raw instanceof ToolConfig value) || value.command().isBlank()) {
                throw new IllegalArgumentException("command must not be blank");
            }
            if (value.timeoutSeconds() < 1 || value.timeoutSeconds() > 60) {
                throw new IllegalArgumentException("timeoutSeconds must be in [1, 60]");
            }
            return new ToolConfig(value.command().trim(), value.timeoutSeconds());
        });
    }
}
```

依赖必须在 descriptor 中声明。REQUIRED 缺失时组件等待；OPTIONAL 进入同一个 BindingSet，缺失可启动，但 provider 出现或消失也会重激活。

```java
ComponentDescriptor.of(
        "job-runner",
        CapabilityRequirement.required(TOOL),
        CapabilityRequirement.optional(METRICS));

Tool tool = context.require(TOOL);
Optional<Metrics> metrics = context.find(METRICS);
```

`ActivationContext.require/find` 遇到未声明 key 会失败；这是组件 contract 错误，不能当普通查询使用。宿主拿到的 `RuntimeContext` 没有 descriptor 限制，可以查询任意 visible capability，也不会建立 Binding 或触发依赖追踪。

## 宿主原子事务

宿主的显式结构修改通过 `runtime.mutate(...)` 完成。`RuntimeMutation` 只在事务 lambda 内有效，不要保存或跨线程复用；事务内 provide、mount、child context、reconfigure、revoke、dispose 要么整体提交，要么整体拒绝。`ContextHandle.dispose()` 与 `runtime.closeAsync()` 是公开 lifecycle request，不需要调用方再包一层 mutation，它们内部仍会 draft/publish generation 并返回 settlement。

```java
try (KnotraRuntime runtime = KnotraRuntime.create()) {
    MutationResult<ComponentHandle<ToolConfig>> mounted = runtime.mutate(tx ->
            tx.mount(runtime.rootContext(), "main-tool", new ToolFactory(),
                    new ToolConfig("wc -l", 10)));
    if (!mounted.committed()) {
        throw new IllegalStateException(mounted.diagnostics().toString());
    }
    ComponentState state = mounted.value().whenSettled()
            .toCompletableFuture()
            .get(10, TimeUnit.SECONDS);
}
```

`mutate(...)` committed 返回时新 view 已经发布并调度 side plan。`MutationResult.settlement()` 只等待本次 mutation 直接触发的 component/context transitions，不是 runtime 全局 quiescence，也不保证之后独立 transition 已完成；要观察特定挂载，使用 `ComponentHandle.whenSettled()`。宿主 Capability 同样事务化发布和撤销：

```java
MutationResult<RegistrationHandle> published = runtime.mutate(tx ->
        tx.provide(runtime.rootContext(), TOOL, defaultTool()));

MutationResult<RegistrationHandle> replaced = runtime.mutate(tx -> {
    tx.revoke(published.value());
    return tx.provide(runtime.rootContext(), TOOL, newTool());
});
```

替换 registration 即使新值与旧值 `equals()` 相等，也会因 registration identity 变化触发消费者重激活。

Context 是可见性树。子 context shadow 父 context；子 registration 撤销后回到父 registration：

```java
ContextHandle workspace = runtime.mutate(tx ->
        tx.childContext(runtime.rootContext(), "workspace")).value();

MutationResult<RegistrationHandle> local = runtime.mutate(tx ->
        tx.provide(workspace, TOOL, workspaceTool()));

runtime.mutate(tx -> {
    tx.revoke(local.value());
    return null;
});
```

挂载唯一键是 `(contextId, mountId)`。配置变更保留 handle 并创建新 Activation；替换工厂实现才需要 dispose 后重新 mount。

## LifecycleScope 与重试

`start()` 创建的可逆资源必须交给 `context.lifecycle()`。默认 scope 与 `child()` 是确定性 LIFO；只有 `parallelChild()` 的 sibling 并行。清理失败不短路其它 entry，错误聚合进诊断，失败 entry 可重试。

```java
Connection connection = openConnection(config);
context.lifecycle().manage("connection", connection);
context.lifecycle().onClose("flush", () -> flush());
context.lifecycle().manageAsync("async-index", () -> closeIndexAsync());

LifecycleScope staged = context.lifecycle().child("staged");
staged.manage("reader", openReader());
staged.manageAsync("warmer", this::stopWarmer);

LifecycleScope parallel = context.lifecycle().parallelChild("warmers");
parallel.manageAsync("warmer-1", this::stopWarmer);
```

`manage(AutoCloseable)` 返回原资源，`onClose(Runnable)` 适合简单动作，`manageAsync(AsyncDisposer)` 适合 future/executor/网络连接。子组件由父 Activation 拥有，先 staged，父 commit 后才启动：

```java
ComponentHandle<NoConfig> child = context.mountChild(
        "worker", new WorkerFactory(), NoConfig.INSTANCE, MountOptions.DEFAULT);
```

生命周期失败的重试模式：

```java
if (handle.dispose().toCompletableFuture().get(10, TimeUnit.SECONDS)
        == ComponentState.FAILED) {
    repairExternalResource();
    ComponentState settled = handle.retry()
            .toCompletableFuture()
            .get(10, TimeUnit.SECONDS);
}
```

`retry()` 只用于 `FAILED` handle：start 失败会重新启动并趋向 `ACTIVE`；dispose 清理失败只重试失败 entry 并趋向 `DISPOSED`。`goal()` 是 `RUNNING/DISPOSED` 意图，`state()` 是当前进度。

## Snapshot 与诊断

`runtime.snapshot()` 是不可变 DTO，不含组件实例、provider 值、disposer、`Throwable`、`Class` 或 `ClassLoader`，可长期持有。

```java
RuntimeSnapshot snapshot = runtime.snapshot();
long generation = snapshot.generation();

boolean active = snapshot.components().stream()
        .anyMatch(item -> item.mountId().equals("main-tool")
                && item.state() == ComponentState.ACTIVE);

List<RuntimeDiagnostic> failures = snapshot.diagnostics().stream()
        .filter(item -> item.code() == DiagnosticCode.ACTIVATION_FAILED
                || item.code() == DiagnosticCode.CLEANUP_FAILED)
        .toList();
```

Snapshot 还包含 context、component handle、activation 与 BindingSet、registration owner、lifecycle scope、managed entry cleanup state。常见 Core code：`MISSING_CAPABILITY`、`CAPABILITY_SLOT_OCCUPIED`、`CAPABILITY_TYPE_CONFLICT`、`BINDING_CYCLE`、`ACTIVATION_FAILED`、`ROLLBACK_FAILED`、`CLEANUP_FAILED`、`INVALID_MOUNT_ID`。`INVALID_CONFIG` 目前是保留码；当前 schema 抛错、返回 null 或返回错误类型通常表现为 `INVALID_LIFECYCLE_OPERATION`。

## EventBus 集成

EventBus 是普通组件。先挂载 `EventBusFactory`，消费者声明并 require `EventCapabilities.EVENT_BUS`。

```java
ComponentHandle<NoConfig> bus = runtime.mutate(tx ->
                tx.mount(runtime.rootContext(), "event-bus",
                        new EventBusFactory(), NoConfig.INSTANCE))
        .value();
bus.whenSettled().toCompletableFuture().get(10, TimeUnit.SECONDS);
```

订阅必须交给消费者 lifecycle 托管，使 provider 替换或 artifact unload 前先等待已 accepted 的 dispatch。

```java
public static final EventDefinition<JobFinished> JOB_FINISHED =
        EventDefinition.serial(EventKey.of(JobFinished.class));

EventBus bus = context.require(EventCapabilities.EVENT_BUS);
EventSubscription subscription = bus.onSerial(JOB_FINISHED, event -> {
    persist(event);
    return CompletableFuture.completedFuture(true);
});
context.lifecycle().manageAsync("job-listener", subscription::closeAsync);
```

五种模式：

- `on` / `emit`：同步 `EventListener`，直接返回 `EventDispatch`。
- `onParallel` / `parallel`：listener 并行，future 汇总失败。
- `onSerial` / `serial`：顺序执行；返回 `false` 停止后续且不算错误。
- `onBail` / `bail`：返回 `true` 表示认领并停止。
- `onWaterfall` / `waterfall`：转换值传给下一个 listener。

```java
EventDispatch<JobFinished> result = bus.serial(JOB_FINISHED, event)
        .toCompletableFuture()
        .get(10, TimeUnit.SECONDS);

result.failures().forEach(f ->
        System.out.println(f.subscriptionId() + ": " + f.message()));
```

`closeAsync()` 拒绝新工作并等待已 accepted 的工作；重复调用返回同一阶段。bus close 会先封闭入口并清空订阅/binding registry，但已 accepted 的 dispatch 对象持有原 binding，直到回调完成后释放；binding 只有活跃订阅和 accepted 计数都归零才可移除。listener 回调内不能等待当前订阅或 bus 关闭。

## PF4J 插件与宿主

插件实现 `RuntimeComponentProvider`，由 PF4J extension 机制发现。导出的 factory 携带显式 config token；config token 与 Capability contract 必须来自宿主或共享 contract 包，不能是插件私有类型。

```java
@Extension
public final class ExportedToolProvider implements RuntimeComponentProvider {
    @Override public Collection<ExportedComponentFactory<?>> factories() {
        return List.of(ExportedComponentFactory.of(
                ToolConfig.class, new PluginToolFactory()));
    }
}
```

无配置导出用 `ExportedComponentFactory.noConfig(factory)`。宿主创建 adapter 时声明额外共享包；Knotra 与 PF4J 自身共享包由 adapter 固定处理：

```java
try (KnotraRuntime runtime = KnotraRuntime.create();
     Pf4jArtifactAdapter adapter = Pf4jArtifactAdapter.create(
             Path.of("plugins"), runtime, Set.of("com.example.contract"))) {

    ArtifactSnapshot artifact = adapter.loadArtifact(
            Path.of("plugins/tool-1.0.0.jar")).join();
}
```

`loadArtifact()` 只加载、启动并枚举 factory，不自动挂载。tokenless catalog 只有稳定文本元数据，不能 mount，也不能转回 typed handle：

```java
adapter.factoryCatalog().forEach(entry ->
        System.out.println(entry.factoryId() + ": " + entry.configTypeName()));
```

挂载必须 typed resolve，并传非空的正确 config 类型实例；输入可以是 raw config，Core 会在 factory create 后调用该 factory 的 schema 归一化：

```java
ArtifactFactoryHandle<ToolConfig> factory = adapter.resolver()
        .resolve("tool", ToolConfig.class)
        .orElseThrow();

ComponentHandle<ToolConfig> tool = factory.mount(
        runtime.rootContext(), "tool", new ToolConfig("convert", 5));
```

`NoConfig` factory 必须传 `NoConfig.INSTANCE`。null、错误类型和 raw cast 都在 factory create/mount 边界前失败；typed direct mount 不要求调用方预先归一化配置。需要 schema 时用 active-only `factory.configSchema()`。

卸载是 drain：拒绝新 mount、等待 in-flight mount、先释放依赖 artifact 和 owned component，再物理 teardown，最后停止/卸载 PF4J plugin 并释放 ClassLoader。

```java
try {
    adapter.unloadArtifact("tool-plugin").join();
} catch (CompletionException error) {
    ArtifactSnapshot snapshot = adapter.artifact("tool-plugin").orElseThrow();
    if (snapshot.state() == ArtifactState.DRAIN_FAILED) {
        repairFailure();
        adapter.retryDrain("tool-plugin").join();
    } else {
        throw error;
    }
}
```

`UNLOADED` 表示成功；`DRAIN_FAILED` 表示 cleanup 可重试。owned handle 通常为 `FAILED` 且 goal 为 `DISPOSED`。极端 PF4J stop/unload 半失败不会被伪造为成功，可能需要重启 JVM。

## Loader 声明式装配

Loader 消费 desired tree。路径是稳定身份并形成 context 树；父路径必须存在，不支持 `..`。

```java
FactoryRef toolRef = FactoryRef.of("tool", "1.0.0");

ComponentFactoryResolver resolver = ClasspathComponentFactoryResolver.builder()
        .add(toolRef, new ToolFactory())
        .build();

try (KnotraLoader loader = KnotraLoader.over(
        runtime, runtime.rootContext(), resolver)) {
    ReconcileResult result = loader.reconcile(ComponentTree.of(
            ComponentEntry.of(
                    "tools/main", toolRef, new ToolConfig("convert", 5),
                    ComponentEntry.of(
                            "tools/main/worker",
                            FactoryRef.of("worker", "1.0.0"),
                            NoConfig.INSTANCE))));
    if (!result.converged()) {
        result.diagnostics().forEach(item ->
                System.out.println(item.path() + ": " + item.message()));
    }
}
```

语义：新增项批量创建 context 并 mount，失败回滚本批新增；删除项递归 dispose context 子树；factory identity 变化时先等待旧 Handle settlement 到 `DISPOSED`，再在同一 slot 挂载新实现，替换失败尝试恢复旧实现；config 变化对同 handle `reconfigure()`；`FAILED` 组件由调用方显式 `loader.retry(path)`。

`FactoryIdentity` 由必填 `factoryId`、必填 implementation fingerprint 和可选 `version` 组成；三者共同参与相等性判断。fingerprint 用于区分同名 factory 的不同 artifact/实现来源。

Loader 不监听文件系统。`reconcileAsync()` 由单线程 coordinator 串行执行；resolver 和 controlled mount 回调不能同步调用同一个 loader。

## PF4J 到 Loader 的 typed bridge

Loader 不能拿到 `KnotraRuntime`、`RuntimeMutation` 或 executable factory。桥接层把 typed artifact handle 转成 opaque `ResolvedComponentDefinition`，mount 只使用分配好的 `ControlledMountContext`。

```java
static <C> ResolvedComponentDefinition bridgeDefinition(
        FactoryRef ref,
        ArtifactFactoryHandle<C> handle,
        Class<C> configType) {

    String fingerprint = handle.artifactId() + "@"
            + handle.artifactVersion() + "#" + handle.factoryId()
            + ":" + handle.configType().getName();

    ConfigSchema<Object> schema = raw -> {
        Optional<ConfigSchema<C>> selected = handle.configSchema();
        return selected.isPresent()
                ? selected.get().validate(raw)
                : (raw == null ? NoConfig.INSTANCE : raw);
    };

    ControlledMountStrategy strategy = (context, config) -> {
        try {
            C typedConfig = configType.cast(config);
            return CompletableFuture.completedFuture(handle.mount(
                            context.context(), context.mountId(), typedConfig))
                    .thenApply(value -> (ComponentHandle<?>) value);
        } catch (RuntimeException error) {
            return CompletableFuture.failedFuture(error);
        }
    };

    return new ResolvedComponentDefinition(
            FactoryIdentity.fromRef(ref, fingerprint),
            schema, strategy, ReconfigureStrategy.direct());
}

private static <C> Optional<ResolvedComponentDefinition> typedBridge(
        Pf4jArtifactAdapter adapter, FactoryRef ref, Class<C> configType) {
    return adapter.resolver()
            .resolve(ref.factoryId(), configType)
            .map(handle -> bridgeDefinition(ref, handle, configType));
}
```

Resolver 根据 `FactoryRef` 选择 config token，再调用 `typedBridge`。typed resolve 查不到 factory 时返回 `Optional.empty()`；token 不匹配会 fail fast 抛 `IllegalArgumentException`，并由 Loader 记录 `RESOLUTION_FAILED`。桥接 schema 先归一化 raw config，`ControlledMountContext` 只暴露目标 context、mountId 和一次 typed mount；返回其它 handle 会破坏 slot 契约并被清理。

## 关闭顺序与错误处理

推荐从外到内关闭：

```java
loader.closeAsync().toCompletableFuture().get(30, TimeUnit.SECONDS);
adapter.closeAsync().toCompletableFuture().get(30, TimeUnit.SECONDS);
runtime.closeAsync().toCompletableFuture().get(30, TimeUnit.SECONDS);
```
`try-with-resources` 的关闭顺序与声明相反；通常先声明 `runtime`，再声明 adapter/loader。如果 `runtime.closeAsync()` 已接管 Loader base subtree，`loader.closeAsync()` 只清理自身托管簿记并停止 coordinator，不重复 teardown，也不代替调用方等待 Runtime settlement；调用方仍应等待 `runtime.closeAsync()`。关闭失败时先读 snapshot/diagnostics，修复后调用 retry 或重复 close；不要假设已经关闭。各层 close 幂等。

## 常见错误

- `MutationResult.value()` 抛异常：事务被拒绝，先看 `committed()` 与 `diagnostics()`。
- 第二次 provide 同名 capability 被拒：同 context slot 已占用，先 `revoke()`。
- 组件长期 `WAITING`：REQUIRED 缺失或 binding cycle，看 snapshot bindings/diagnostics。
- `capability is not declared`：descriptor 未声明却 `require/find`。
- 配置 mount 失败：schema 抛错或返回 null；无配置必须 `NoConfig.INSTANCE`。
- typed resolve 返回空：factory 不存在；token 不匹配是立即抛出的 `IllegalArgumentException`，Loader 记录 `RESOLUTION_FAILED`。查 catalog 的 `configTypeName()`。
- artifact mount 报 null config：artifact handle 强制非 null，NoConfig 也传 `NoConfig.INSTANCE`；非空正确类型由 Core schema 归一化。
- `CONTEXT_CONFLICT`：路径/mountId 被其它 owner 占用，Loader 路径是保留命名空间。
- Event close 卡住：listener 等待自身 close，或异步 disposer 不完成。
- 保存 `ActivationContext`：start 返回后 context 关闭；长期状态放入 capability。
