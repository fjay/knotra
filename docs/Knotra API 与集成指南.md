# Knotra API 与集成指南

本文面向 Knotra `0.1.0-SNAPSHOT` 的宿主、组件和插件开发者，只描述当前 public API。该版本要求 Java 21+、Maven 3.9+，尚未发布到 Maven Central；引用方式是在同一 reactor 内依赖，或先执行 `mvn install`。

普通业务装配优先使用 `knotra-beans`，本文的 Core 章节是理解其运行语义的基础。Beans 与 Spring 的完整用法见 [Beans 与 Spring 集成指南](<Knotra Beans 与 Spring 集成指南.md>)，本文只保留摘要。

## 模块与 Maven 坐标

所有模块使用同一 groupId 和版本：

| 模块 | 使用场景 |
|---|---|
| `io.knotra:knotra-core` | Context、Capability、事务、固定/动态绑定、组件生命周期与 Snapshot |
| `io.knotra:knotra-beans` | POJO constructor wiring、输出和清理适配 |
| `io.knotra:knotra-beans-processor` | 编译期生成无反射 Bean Factory |
| `io.knotra:knotra-events` | 类型化 EventBus |
| `io.knotra:knotra-spring` | Activation-owned Spring child context 与 dynamic bridge |
| `io.knotra:knotra-pf4j-spi` | 插件实现的 factory export SPI |
| `io.knotra:knotra-pf4j` | PF4J artifact 加载、目录、挂载、drain 与卸载 |
| `io.knotra:knotra-loader` | desired component tree reconcile |
| `io.knotra:knotra-pf4j-loader` | PF4J catalog 到 Loader 的官方 resolver |

版本统一为 `0.1.0-SNAPSHOT`：

```xml
<dependency>
    <groupId>io.knotra</groupId>
    <artifactId>knotra-core</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

插件通常以 provided 方式依赖 Core、`knotra-pf4j-spi` 和 PF4J；宿主同时使用插件与 Loader 时再引入 `knotra-pf4j-loader`。`knotra-integration-tests` 只在 reactor 内验证跨模块行为，不作为使用方依赖。

## 最小 Core 路径

默认配置适合普通宿主；需要稳定 runtime ID 或限制同一结构指纹的自动收敛次数时，显式传入 `KnotraConfig`：

```java
KnotraRuntime runtime = KnotraRuntime.create(
        new KnotraConfig("order-runtime", 128));
```

`runtimeId` 必须非空白，`maxReconcileIterations` 必须为正，默认上限是 256。同一结构指纹超过上限后，组件保持 `WAITING` 并报告 `NON_CONVERGENT_RECONCILE`，直到显式 retry 或相关结构变化。

定义 Capability 合约：

```java
public interface Tool {
    String run(String input);
}

public static final CapabilityKey<Tool> TOOL =
        CapabilityKey.of("app.tool", Tool.class);
```

创建组件工厂并挂载。组件壳由工厂创建、跨 Activation 复用；每次启动获得新的 `ActivationContext` 与新的 BindingSet，业务实例不要缓存在组件壳字段里：

```java
public final class ToolBoxFactory implements ComponentFactory<NoConfig> {
    @Override
    public Component<NoConfig> create() {
        return new Component<>() {
            @Override
            public ComponentDescriptor descriptor() {
                return ComponentDescriptor.of(
                        CapabilityRequirement.required(TOOL));
            }

            @Override
            public void start(ActivationContext context, NoConfig config)
                    throws Exception {
                Tool tool = context.require(TOOL);
                context.provide(BOX, new ToolBox(tool));
            }
        };
    }
}
```

启动 Runtime、发布 provider、挂载 consumer 并等待首次稳定：

```java
try (KnotraRuntime runtime = KnotraRuntime.create()) {
    RegistrationHandle tool = runtime.provide(TOOL, new DefaultTool());

    ComponentHandle<NoConfig> box =
            runtime.mount("tool-box", new ToolBoxFactory());
    box.whenSettled().toCompletableFuture().join();
}
```

`start()` 在协调器锁外调用。抛出异常会使本次 Activation 失败并回滚；启动期间暂存的注册与子挂载只有在正常返回且验证通过后才对外可见。通过 `context.lifecycle()` 登记的资源随 Activation 回滚或关闭，不要自行保存后手工释放。

## Capability、Component 与 PINNED 绑定

Capability 由稳定名称和精确 JVM 引用类型组成。同一个 Runtime 生命周期内，同名 Capability 固定为同一个 Java 类型；Context 中同名 slot 只有一个当前 registration，子 Context 的本地 registration 可以遮蔽父级。

依赖在 descriptor 中预先声明：

```java
ComponentDescriptor.of(
        CapabilityRequirement.required(DATABASE),
        CapabilityRequirement.optional(METRICS));
```

PINNED 是默认绑定语义：

- `REQUIRED`：provider 缺失时组件 settle 为 `WAITING`；provider 出现后自动激活。
- `OPTIONAL`：缺失时可以启动，`find` 返回空；provider 出现、消失或替换都会改变 BindingSet 并触发新 Activation。
- 同一 descriptor 内 Capability 名称不可重复，primitive 合约类型被拒绝。

Activation 读取依赖：

```java
Tool tool = context.require(TOOL);
Optional<Metrics> metrics = context.find(METRICS);
```

`require/find/subscribe` 访问未声明 key、或使用与声明不一致的绑定方式时立即失败。宿主查询走 `ContextView`，不受 descriptor 限制：

```java
Tool current = runtime.root().view().require(TOOL);
Optional<Tool> maybe = workspace.view().find(TOOL);
```

宿主读取不建立 Binding，也不持有调用租约；provider 替换或关闭时宿主引用不受保护。需要动态跟随 provider 的调用必须使用下文的 `DynamicCapability` 或 `SpringDynamicBridge`。

`runtime.provide(...)` 返回的 host `RegistrationHandle` 只控制注册表身份；`revoke` 或 Runtime close 不会替宿主调用 value 的 `close()`。`ActivationContext.provide(...)` 创建的 registration 则由当前 Activation 所有，并随回滚、重激活或 dispose 自动撤销。

## 类型化配置

Core 只接收类型化配置 `C`。Factory 校验并返回同类型规范值：

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

直接 Core mount 的入参必须是 `ToolConfig`，无配置组件使用 no-config overload：

```java
ComponentHandle<ToolConfig> tool = runtime.mount(
        "main-tool", new ToolFactory(), new ToolConfig("wc -l", 10));
ComponentHandle<NoConfig> metrics = runtime.mount(
        "metrics", new MetricsFactory());
```

配置为 null、normalizer 抛错或返回 null 时，Core 以 `DiagnosticCode.INVALID_CONFIG` 拒绝事务。Map、JSON tree 和配置文件节点通过独立 `ConfigDecoder<C>` 在 Loader 或 PF4J export 边界转换成 `C`：Decoder 负责 raw 到 `C`，Factory normalizer 负责 `C` 到规范 `C`，两个契约不合并。

## 结构事务

根 Context 上的单操作有 convenience：`runtime.provide`、`runtime.revoke`、`runtime.mount`。多个结构变化通过一次性 `RuntimeTransaction` 原子提交：

```java
TransactionReceipt<RegistrationHandle> replaced = runtime.transact(tx -> {
    tx.revoke(registration);
    tx.mount(runtime.root(), "worker", workerFactory);
    return tx.provide(runtime.root(), TOOL, replacementTool());
});
```

事务支持：

- `provide` / `revoke`
- `childContext`
- typed `mount` 与 no-config `mount`，可带 `MountOptions`
- `reconfigure`
- Component 或 Context `dispose`

回调在调用线程执行，不只是记录意图：每个 mount 意图会立即执行 `factory.factoryId()` 读取、`factory.create()`、descriptor 冻结与校验，以及 typed `normalizeConfig(config)`。用户 `start()` 不会在回调内执行。因此 factory 与 normalizer 应保持轻量、无副作用，且不要在回调里使用回调返回的 handle——它们指向尚未提交的草稿结构，事务成功提交后才可用。

回调抛出的配置错误会以 `INVALID_CONFIG` 拒绝整个事务；其他运行时异常包装为 `INVALID_LIFECYCLE_OPERATION`。全部意图在最新结构草稿上校验，任一失败都不会发布新 generation。`RuntimeTransaction` 只在回调内有效，不能保存复用。

成功返回收据：

```java
R value = replaced.value();
long generation = replaced.generation();
CompletionStage<Void> settlement = replaced.settlement();
```

拒绝不会返回 receipt，而是抛出 `TransactionRejectedException`，异常携带 `List<RuntimeDiagnostic>`；忽略 `transact(...)` 返回值也不会忽略失败。`settlement()` 等待该事务直接触发的组件/Context 过渡，以及被 retire registration 的动态调用租约排空，但不是 Runtime 全局静止；观察特定组件使用 `handle.whenSettled()`。

## Context

创建子 Context：

```java
ContextHandle workspace = runtime.transact(tx ->
        tx.childContext(runtime.root(), "workspace")).value();
```

查询结构：

```java
String path = workspace.info().canonicalPath();
ContextState state = workspace.state();
Tool local = workspace.view().require(TOOL);
```

`ContextHandle` 是结构身份，`ContextView` 是只读 Capability 视图；根查询只有 `runtime.root().view()` 一条路径。释放子树：

```java
ContextState settled = workspace.disposeAsync()
        .toCompletableFuture()
        .get(30, TimeUnit.SECONDS);
```

子 Context 名称是单个路径段：必须非空、不能是 `.`/`..`，不能包含 `/`、`\` 或控制字符；同一父节点下名称唯一。dispose 会处理整个 subtree，包括其中的组件、Activation-owned registration 和 host registration。清理失败正常结算为 `ContextState.FAILED` 并保留现场，修复后再次调用 `disposeAsync()`；`close()` 阻塞等待并要求最终为 `DISPOSED`。Root Context 只能由 `runtime.closeAsync()` 接管，不能单独 dispose。

## Owned Child

组件可以在 `start()` 中把子组件挂到同一个 Context，并把它的生命周期交给当前 Activation：

```java
ComponentHandle<NoConfig> child =
        context.mountChild("worker", workerFactory);
```

`mountChild` 也提供 typed-config 和 `MountOptions` overload。调用只创建 provisional handle 和挂载计划；父 Activation 提交后 child 才进入 Runtime 结构。父启动失败、stale 或回滚时，计划与 provisional child 一起撤销；父重激活或 dispose 时，旧 Activation 拥有的 registrations 和整棵 child subtree 递归释放。旧 child 清理未 settle 前，新一代不能复用同一 mountId。

Snapshot 通过 component 的 `ownerActivationId` 与 `parentHandleId` 暴露这层所有权。父或 child 清理失败时保留 `FAILED` 现场，修复 disposer 后通过相应 handle 的 `retryAsync()` 推进，而不是绕过所有权重新 mount。

## LifecycleScope

Activation 获得的每个可逆资源都要登记：

```java
Connection connection = context.lifecycle()
        .manage("connection", openConnection());
context.lifecycle().onClose("flush", this::flush);
context.lifecycle().onCloseAsync("consumer", this::stopConsumerAsync);

EventSubscription subscription = bus.subscribe(JOB_FINISHED, listener);
context.lifecycle().manageAsync("listener", subscription);
```

四种登记方式对应同步资源、同步动作、异步动作和 `AsyncCloseable` 资源。资源应先登记再返回或发布，确保初始化失败、发布冲突或 stale 回滚都能释放。`onClose/onCloseAsync` 返回 `ManagedHandle`，可读取 entry ID、描述、`CleanupState`、尝试次数和最近错误；Scope 自身通过 `scopeId()`、`parent()` 与 `state()` 暴露位置和生命周期状态。当前实现把异步 disposer 返回 null stage 视为完成；实现方应返回明确的 completed stage。

Scope 可以组成树：

```java
LifecycleScope staged = context.lifecycle().child("staged");
staged.manage("reader", openReader());

LifecycleScope parallel = context.lifecycle().parallelChild("workers");
parallel.onCloseAsync("worker-a", this::stopWorkerA);
parallel.onCloseAsync("worker-b", this::stopWorkerB);
```

同步 scope 按全局节点序列 LIFO 清理；`parallelChild` 只并发其直属条目，组本身仍参与外层 LIFO。清理失败不会中断其他条目：成功条目的 disposer 被释放，失败条目保留供 `retryAsync()` 重试，因此 disposer 应可重入或能安全失败重试。

`ActivationContext` 在 `start()` 返回后关闭，保存它再调用 `require/provide/mountChild/lifecycle` 都会失败；但在 `start()` 内取得并保存的 `LifecycleScope` 只要仍是 `OPEN`，可以为 Activation 后续创建的异步资源补登记。Scope 进入 `STOPPING` 后拒绝新条目。

## ComponentHandle 与状态

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

- `whenSettled()` 等待离开当前 `STARTING` / `STOPPING` 过渡，不阻止之后的新结构变化。
- `reconfigureAsync` 保留 handle，增加 config revision，并产生新 Activation；typed normalizer 在提交路径执行。
- `retryAsync` 只接受 `FAILED` handle。启动失败会重新激活；清理失败只重试失败条目。
- `disposeAsync` 设置 goal 为 `DISPOSED`，递归释放 owned registration 和 child mount。
- `close()` 阻塞执行 dispose，最终不是 `DISPOSED` 时抛出异常，不会静默接受失败。

`MountOptions` 携带 `ComponentOrigin` 与自由格式 metadata，用于诊断和归属，不影响绑定语义。集合在构造时复制为不可变值；owned child 未显式传 options 时继承父组件 origin，但不继承父 metadata。

## DynamicCapability 调用契约

动态绑定解决的是“无状态调用随 provider 换代即时切换，而 consumer 不重启”。它是显式选择，不是默认注入语义。

声明与订阅：

```java
ComponentDescriptor.of(
        CapabilityRequirement.dynamicRequired(PAYMENT_GATEWAY),
        CapabilityRequirement.dynamicOptional(METRICS));

DynamicCapability<PaymentGateway> gateway =
        context.subscribe(PAYMENT_GATEWAY);
```

绑定规则：

- `dynamicRequired` 只在候选 Activation 可以启动前要求已提交 provider 存在；缺失时 mount 事务仍可成功，组件保持 `WAITING`。候选启动后 Core 在提交时再次确认 required provider 仍存在，若期间消失则回滚该候选。这两次检查只比较 presence，不把启动之初的 registration 固定到候选；consumer 进入 `ACTIVE` 后，provider 消失或替换也不重启 consumer。
- `dynamicOptional` 允许 provider 缺失；调用时无 provider 会失败，不触发 consumer 重启。
- DYNAMIC 依赖不属于固定 BindingSet 身份，不参与 PINNED 依赖传播；DYNAMIC 与 PINNED 混合形成的依赖环仍会以 `BINDING_CYCLE` 拒绝提交。

`available()` 只是 advisory 结果：它回答“当前视图能否解析到 provider”，不持有租约。`available()` 返回 true 之后、真正调用之前 provider 仍可能被替换或撤销。真正的调用在协调器内原子获取 consumer 侧与 provider 侧租约。

两类失败要区分：

- `CapabilityUnavailableException`：consumer 仍存活但当前没有已提交、可租约的 provider（包括 provider 已 retire）。
- `DynamicCapabilityClosedException`：consumer 所属 Activation 已关闭，动态入口不再接受调用。

多方法操作固定同一个 provider：

```java
Receipt receipt = gateway.call(provider ->
        provider.begin()
                .set(order)
                .commit());
```

`call` 在单个已提交 provider 上执行整个 operation。operation 抛出的 `RuntimeException` 原样抛出；checked exception 包装为以原异常为 cause 的 `CompletionException`。租约在所有正常和异常退出路径中都会释放。

异步调用：

```java
CompletionStage<Receipt> stage = gateway.callAsync(provider ->
        provider.submitAsync(order));
```

`callAsync` 先取得租约，再把 provider 交给 operation。operation 同步抛错时立即释放租约并返回异常完成的 stage；返回 null stage 视为同步完成；返回 `CompletionStage` 时租约保持到 stage 完成（无论成功或失败），结果 stage 在租约释放后完成。恶意组合的 `CompletionStage` 不会泄漏租约。

接口代理：

```java
PaymentGateway proxied = gateway.proxy(PaymentGateway.class);
```

- 只接受 interface，且类型必须与 `CapabilityKey.type()` 完全一致；子接口会被拒绝，`proxy()` 是使用 key 类型的快捷方式。
- 每个 proxy 方法独立获取租约；连续两次方法调用可能落在不同 provider 代际上。
- 返回 `CompletionStage` 的方法会持有租约到 stage 完成；同步方法的租约随调用结束释放。
- `equals/hashCode/toString` 使用 proxy 自身身份，不解析 provider；业务方法通过反射调用时会解包 provider 抛出的原始 cause。
- 多方法事务边界必须使用一次 `call`/`callAsync` 或下文的显式租约语义，代理不能推断业务事务。

排空与关闭契约：

- provider 被替换或撤销时，旧 provider 先停止接收新租约；其 cleanup 等待全部在途租约归零后才执行。
- consumer Activation 关闭时，先关闭调用闸门并等待已开始的动态调用归零，然后才执行 LifecycleScope teardown。
- 因此“旧对象清理后不再被调用”由运行时保证；对象内部线程、外部逃逸引用和宿主直接持有的引用仍由对象自己负责。

Snapshot 中 DYNAMIC 绑定是固定占位：`present=false`，不记录当前 provider 的 registration 身份。动态解析结果每次调用时决定。当前 Snapshot 也不暴露 dynamic gate、在途 lease 数或 drain 进度；调用期 unavailable/closed 通过调用异常观测，不会写入 Runtime diagnostics。

## Snapshot 与诊断

`runtime.snapshot()` 返回不可变 `RuntimeSnapshot`，包含：

- generation 与 Context tree
- component state、goal、config revision、origin 与 mount options
- Activation、Requirement 与 BindingSet
- registration owner
- LifecycleScope 与 managed entry cleanup state
- 稳定 `RuntimeDiagnostic`

Snapshot 中的内部代际与资源状态用于区分组件表面状态背后的阶段：

| 状态族 | 值 | 含义 |
|---|---|---|
| `ActivationState` | `STARTING` / `ACTIVE` / `STOPPING` | 候选启动、已提交代际、正在排空和清理 |
| `ActivationState` | `FAILED` / `SETTLED` | 该代启动或清理失败；或该代资源已完全释放 |
| `LifecycleState` | `OPEN` / `STOPPING` | Scope 可登记资源；或已关闭准入并执行清理 |
| `LifecycleState` | `FAILED` / `SUCCEEDED` | 至少一个 cleanup entry 失败；或所有条目清理成功 |
| `CleanupState` | `PENDING` / `FAILED` / `SUCCEEDED` | 单个 entry 尚未完成、最近一次失败且可重试、已经成功且不会重放 |

快照是纯数据：不引用 provider value、component、factory、disposer、listener、`Throwable`、`Class` 或 `ClassLoader`，长期保存不会阻止插件卸载后的 ClassLoader 回收。诊断消费者应匹配枚举：

```java
runtime.snapshot().diagnostics().stream()
        .filter(item -> item.code() == DiagnosticCode.CLEANUP_FAILED)
        .forEach(this::alert);
```

| 诊断码 | 含义 |
|---|---|
| `INVALID_CONFIG` | 类型化配置校验或归一化失败 |
| `INVALID_MOUNT_ID` | mountId 为空或 slot 已占用 |
| `INVALID_LIFECYCLE_OPERATION` | 生命周期操作非法（目标已释放、参数无效、运行时关闭中等） |
| `CAPABILITY_SLOT_OCCUPIED` | 当前 Context 的 Capability slot 已占用 |
| `CAPABILITY_TYPE_CONFLICT` | 同名 Capability 使用了不同 JVM 类型 |
| `MISSING_CAPABILITY` | REQUIRED binding 不可用 |
| `ACTIVATION_FAILED` | 组件 start 失败 |
| `ROLLBACK_FAILED` | 公开枚举项；当前 Core 生产路径没有发射点 |
| `CLEANUP_FAILED` | LifecycleScope 条目清理失败 |
| `BINDING_CYCLE` | 依赖图存在不能提交的环 |
| `NON_CONVERGENT_RECONCILE` | 自动收敛超过配置的最大迭代次数 |

## EventBus

挂载 EventBus 组件并等待 provider：

```java
ComponentHandle<NoConfig> provider = runtime.mount(
        "event-bus", new EventBusFactory());
provider.whenSettled().toCompletableFuture().join();

EventBus bus = context.require(EventCapabilities.EVENT_BUS);
```

事件 definition 同时固定事件名、精确 JVM Class 和分发模式：

```java
EventDefinition.Sync<JobStarted> STARTED =
        EventDefinition.sync(JobStarted.class);
EventDefinition.Serial<JobFinished> FINISHED =
        EventDefinition.serial("job.finished", JobFinished.class);
```

事件名默认使用 Class 全限定名；显式名称只改变可读身份。同一事件名存在活跃订阅或已接受 dispatch 时，只能绑定一个精确 JVM `Class`；不同 ClassLoader 加载出的同名 Class 不是同一事件身份。最后一个订阅和在途 dispatch 都释放后，binding 变为空闲并可由新的 exact Class 重绑。

五种模式：

| Definition 类型 | Listener | Dispatch 结果 |
|---|---|---|
| `Sync<T>` | `EventListener<T>` | 调用线程按顺序执行；失败进入结果，后续 listener 继续 |
| `Parallel<T>` | `ParallelEventListener<T>` | accepted listener 并发执行；全部完成后聚合失败 |
| `Serial<T>` | `SerialEventListener<T>` | 顺序执行；false 正常停止，异常记录失败并停止 |
| `Bail<T>` | `BailEventListener<T>` | 第一个 true 正常认领并停止；异常记录失败并停止 |
| `Waterfall<T>` | `WaterfallEventListener<T>` | 前一输出作为后一输入；异常记录失败、停止并保留最后成功值 |

订阅与分发统一入口，definition 静态类型决定正确 overload：

```java
EventSubscription subscription = bus.subscribe(FINISHED, event -> {
    persist(event);
    return CompletableFuture.completedFuture(true);
});
context.lifecycle().manageAsync("job-finished", subscription);

EventDispatch<JobFinished> report = bus.dispatch(FINISHED, event)
        .toCompletableFuture().join();
```

行为契约：

- Listener 业务失败进入 `EventDispatch.failures()`，不会让已接受 dispatch 的 stage 异常完成。
- 分发接受时固化监听集合；回调中修改注册表不影响本次分发。
- Listener 回调期间 TCCL 切换为监听实现自身的 ClassLoader，结束后恢复调用线程原状态。
- `EventSubscription` 与 `EventBus` 都是 `AsyncCloseable`：close 拒绝新工作，并等待调用前已接受的 dispatch 归零。Listener 回调中不能阻塞等待自身 close。

## PF4J artifact

插件通过 SPI 导出共享配置 token、raw decoder 和类型化 factory：

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

`Class<C>` 与 Capability contract 必须来自宿主或共享 contract 包，不能是插件私有类型；适配器会按 shared contract packages 校验 ClassLoader 一致性。

创建适配器并加载 artifact：

```java
try (Pf4jArtifactAdapter plugins = Pf4jArtifactAdapter.create(
        Path.of("plugins"), runtime, Set.of("com.example.contract"))) {

    ArtifactSnapshot snapshot = plugins.loadArtifact(Path.of("plugins/tool.jar"));
}
```

异步入口为 `loadArtifactAsync`、`unloadArtifactAsync`、`retryDrainAsync`，统一返回 `CompletionStage`；同名阻塞方法只是小工具。

工厂目录只有 `plugins.factories()` 一个入口：

```java
plugins.factories().list();
plugins.factories().find("tool");
ArtifactFactoryHandle<?> dynamic =
        plugins.factories().resolve("tool").orElseThrow();
ArtifactFactoryHandle<ToolConfig> typed =
        plugins.factories().resolve("tool", ToolConfig.class).orElseThrow();
```

`list()` / `find()` 返回只读文本元数据；wildcard `resolve(id)` 供官方 Loader bridge 捕获泛型，普通宿主优先 typed resolve。直接挂载：

```java
ToolConfig config = typed.decodeConfig(rawConfig);
ComponentHandle<ToolConfig> tool = typed.mount(
        runtime.root(), "tool", config);
```

`decodeConfig` 校验 decoder 输出 exact token；Core mount 随后执行 factory 的 typed normalizer。加载不会隐式挂载组件，挂载由宿主显式完成，所有权记录在 `plugins.ownership(artifactId)`。

卸载按 drain 执行：

1. 进入 `DRAINING`，拒绝该 artifact 的新 mount。
2. 等待 in-flight mount 提交或回滚。
3. 若存在未卸载的下游 artifact，先 drain 下游闭包，避免下游仍引用将释放的插件类。
4. 刷新 ownership，并对 adapter 直接提交的 root handle 发起 dispose；Core 递归清理其 owned subtree。
5. 所有 dispose settle 后 stop 并 unload PF4J plugin。
6. 释放 catalog、factory 与 ClassLoader 引用。

清理失败进入 `DRAIN_FAILED`，保留现场，修复后调用 `retryDrain`。`ArtifactOperationException` 携带 artifactId、phase 和可选 Core diagnostics；`ArtifactSnapshot` 不引用 Class、ClassLoader 或存活实例。

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

期望树明确区分无配置和 raw 配置：

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

`owned(runtime, resolver)` 创建 Loader 专属 base Context，close 时整体释放；`over(runtime, context, resolver)` 使用宿主给定 Context，只管理自己的子结构。

Reconcile 语义：

- 先完成路径、resolver、decoder 与冲突的完全准备，再执行“清理旧结构、创建新结构、重配置稳定 handle”。
- resolver、raw decoder 或冲突预检失败时不触碰现有结构；mount 事务或 controlled mount 被拒绝时，Loader 按 LIFO 补偿本批此前新增的 handle 与 Context。mount 已提交但组件 `start()` 失败时，entry 保留为 `FAILED` 并记录 `ACTIVATION_FAILED`，不会作为“未挂载”整体回滚。已有结构清理失败可能产生 `BLOCKED` change。
- 实现身份由 `FactoryIdentity`（factory 引用加实现 fingerprint）决定：身份不变只重配置，身份变化替换挂载。
- `converged` 表示期望树无剩余差异且无诊断，不承诺所有组件 `ACTIVE`；REQUIRED 依赖缺失导致的 `WAITING` 是合法收敛状态。
- `requireConverged()` 在未收敛时抛出携带 diagnostics 的 `ReconcileException`。
- FAILED start 不自动重试，显式执行 `loader.retry("tools/main").requireConverged()`；FAILED Context 会在 retry 中释放子树并由下一次 reconcile 重建。

变更类型：`MOUNTED`、`UPDATED`、`REPLACED`、`REMOVED`、`RETRIED`、`BLOCKED`。`loader.snapshot()` 是不可变 DTO，按路径排序，可长期保存。

### 高级 Resolver SPI

`CompositeFactoryResolver.of(...)` 按声明顺序查询 resolver，第一个返回 `ResolvedFactory` 的实现获胜。自定义实现来源通过 `ResolvedFactory` 同时声明：

- `FactoryIdentity`：factory 引用与 implementation fingerprint，决定 reconfigure 还是 replace。
- `ConfigDecoder<Object>`：在结构修改前把 raw 配置转换为 typed value。
- `ControlledMountStrategy`：只通过 Loader 分配的 `ControlledMountContext` 挂载。
- `ReconfigureStrategy`：把已解码配置应用到现有 handle。

`ControlledMountContext` 只暴露分配好的 Context、mountId 和一次性 `mountAsync(...)` 槽位；重复使用、返回落在其他槽位的 handle 或在非 ACTIVE Context 挂载都会结构化失败。普通 Core factory 优先使用 `ResolvedFactory.of(...)`，只有 artifact bridge 或其他集成层才需要直接实现这些 expert SPI。

## PF4J 到 Loader bridge

使用官方 bridge 把 PF4J catalog 接入 Loader：

```java
ComponentFactoryResolver resolver = Pf4jFactoryResolver.of(plugins);

try (KnotraLoader loader = KnotraLoader.over(
        runtime, runtime.root(), resolver)) {
    loader.reconcile(desired).requireConverged();
}
```

Bridge 负责：

- 从 wildcard catalog handle 安全捕获 `C`。
- 精确匹配非空 `FactoryRef.version`。
- 调用 artifact export decoder。
- 生成包含 artifact 坐标、路径、factory 和 config token 的实现 fingerprint。
- 只使用 Loader 分配的 Context 与 mountId。
- 保留 Core 拒绝的结构化 diagnostics。

PF4J 优先并带 classpath fallback：

```java
ComponentFactoryResolver resolver = Pf4jFactoryResolver.withFallbacks(
        plugins, classpathResolver);
```

Artifact drain 与 reconcile 并发时，新 mount 被拒绝且本批回滚；下次 reconcile 命中新 artifact 或 fallback。

## 关闭顺序

推荐从外到内关闭：

```java
loader.closeAsync().toCompletableFuture().get(30, TimeUnit.SECONDS);
plugins.closeAsync().toCompletableFuture().get(30, TimeUnit.SECONDS);
runtime.closeAsync().toCompletableFuture().get(30, TimeUnit.SECONDS);
```

try-with-resources 的关闭顺序与声明相反，因此先声明 Runtime，再声明 adapter 和 Loader。

| 入口 | 行为 |
|---|---|
| `RuntimeTransaction.dispose(handle/context)` | 与其他结构意图原子提交 |
| `ComponentHandle.disposeAsync()` | 等待单个组件及 owned subtree 清理，失败返回 `FAILED` |
| `ContextHandle.disposeAsync()` | 等待 Context 子树清理，失败返回 `FAILED` |
| `KnotraRuntime.closeAsync()` | 接管根 Context，失败 stage 异常完成，可再次 close |
| `Pf4jArtifactAdapter.closeAsync()` | drain 全部 artifact，失败保留现场，可再次 close |
| `KnotraLoader.closeAsync()` | 释放 Loader 所有结构并停止 coordinator |

不要假设调用 `closeAsync()` 就已经关闭：等待 stage，失败后读取 Snapshot 与 diagnostics 再决定重试或告警。

## Beans 与 Spring 摘要

`knotra-beans` 是 Activation 到 POJO 对象图的类型安全适配器：0～5 参数构造器、required/optional/dynamic 依赖、typed config、initializer、多输出与自动/自定义生命周期，生成标准 `ComponentFactory`。组件壳无状态、跨 Activation 复用；业务 Bean 每次 Activation 新建并由当前 LifecycleScope 拥有。

`knotra-spring` 提供每 Activation 一个 Spring child context，外部 Capability 通过 external singleton registry 注入而不被 Spring 销毁；`SpringDynamicBridge` 为宿主 Spring singleton 提供与 Core 相同租约语义的动态代理。完整 DSL、注解处理器和 builder 契约见 [Beans 与 Spring 集成指南](<Knotra Beans 与 Spring 集成指南.md>)。

## 公开入口参考

| 模块 | 主要公开类型 |
|---|---|
| `knotra-core` | `KnotraRuntime`、`KnotraConfig`、`RuntimeTransaction`、`TransactionReceipt`、`CapabilityKey`、`CapabilityRequirement`、`ComponentFactory`、`Component`、`ComponentDescriptor`、`ActivationContext`、`DynamicCapability` 与动态异常/operation、`LifecycleScope`、`ComponentHandle`、`ContextHandle`、`ContextView`、`RegistrationHandle`、`MountOptions`、`ComponentOrigin`、`ConfigDecoder`、状态枚举、`RuntimeSnapshot`、`RuntimeDiagnostic`、`DiagnosticCode` |
| `knotra-beans` | `Beans`、`BeanDefinition`、`BeanDependency`、builder/creator/lifecycle API，以及 `io.knotra.beans.annotation` 包内的 SOURCE 注解 |
| `knotra-beans-processor` | `KnotraBeanProcessor`；根据 `knotra-beans` 提供的注解生成 `*_KnotraFactory` |
| `knotra-events` | `EventBusFactory`、`EventBus`、`EventDefinition`、各 mode listener、`EventSubscription`、`EventDispatch`、`EventFailure`、`EventBusSnapshot`、`EventCapabilities` |
| `knotra-spring` | `SpringModules`、`SpringModuleBuilder`、`SpringModuleDefinition`、`SpringDynamicBridge`、`SpringContextCloser` |
| `knotra-pf4j-spi` | `RuntimeComponentProvider`、`ExportedComponentFactory` |
| `knotra-pf4j` | `Pf4jArtifactAdapter`、`ArtifactFactoryCatalog`/entry/handle、`ArtifactSnapshot`、`ArtifactMetadata`、`ArtifactDiagnostic`、`ArtifactOwnership`、`ArtifactOperationException`、`KnotraClassLoaderPolicy` |
| `knotra-loader` | `KnotraLoader`、`ComponentTree`、`ComponentEntry`、`FactoryRef`、`FactoryIdentity`、`ComponentFactoryResolver`、`ClasspathFactoryResolver`、`CompositeFactoryResolver`、`ResolvedFactory`、controlled mount/reconfigure SPI、`ReconcileResult`、`LoaderSnapshot` |
| `knotra-pf4j-loader` | `Pf4jFactoryResolver` |
