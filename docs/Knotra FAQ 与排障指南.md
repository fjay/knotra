# Knotra FAQ 与排障指南

本文按“症状 → 原因 → 处理”组织，只描述当前 `0.1.0-SNAPSHOT`。先看状态和稳定诊断码，再看高级并发与 ClassLoader 问题。公开 API 见 [Knotra API 与集成指南](<Knotra API 与集成指南.md>)，POJO 与 Spring 用法见 [Knotra Beans 与 Spring 集成指南](<Knotra Beans 与 Spring 集成指南.md>)。

## 排查入口

从三层 Snapshot 开始：

```java
RuntimeSnapshot runtimeState = runtime.snapshot();
LoaderSnapshot loaderState = loader.snapshot();
List<ArtifactSnapshot> artifactStates = adapter.artifacts();
```

先看三类信息：

1. **状态**：组件、Context、artifact 和 lifecycle entry 卡在哪个阶段。
2. **诊断码**：匹配稳定 enum，不要按 message 字符串编程。
3. **绑定集**：Activation 的 BindingSet 解释为什么启动、重启或等待。

`adapter.artifacts()` 只适合看状态概览。排查单个 artifact 时使用：

```java
adapter.artifact(artifactId);       // 单个状态快照
adapter.diagnostic(artifactId);     // 详细诊断和 owned handle
adapter.ownership(artifactId);      // 运行时归属投影
```

恢复操作是幂等推进：`retryAsync()`、`retryDrainAsync()`、重复 `disposeAsync()` 和重复 `closeAsync()` 都不会重放已经成功的清理。

## 状态速查

| ComponentState | 含义 | 常见原因 |
|---|---|---|
| `WAITING` | 已挂载但当前无 Activation | REQUIRED 缺失、依赖环、自动重启被抑制 |
| `STARTING` | 候选 Activation 正在启动 | `start()` 未返回，或子挂载仍在启动 |
| `ACTIVE` | 当前 Activation 已提交 | 正常 |
| `STOPPING` | 旧 Activation 正在脱离和清理 | 依赖方先清理、动态调用未归零、异步 disposer 未完成 |
| `FAILED` | 启动或清理失败，现场保留 | 读诊断；启动失败或清理失败都可显式 retry |
| `DISPOSED` | 已释放并移除 | 终态 |

| ArtifactState | 含义 | 常见处理 |
|---|---|---|
| `ACTIVE` | artifact 已加载启动，factory catalog 可用 | 正常 |
| `DRAINING` | 拒绝新 mount，正在排空 | 等待 in-flight mount、owned component 或异步清理 |
| `DRAIN_FAILED` | drain 或 PF4J stop/unload 失败 | 读 diagnostic 和 ownership，修复后 `retryDrainAsync` |
| `FAILED` | 加载或启动失败且回滚 | 读 diagnostic，修正后重新 load |
| `UNLOADED` | PF4J 已停止卸载 | 终态 |

单个受管资源条目：

- `PENDING`：尚未清理或正在本次尝试中；
- `SUCCEEDED`：已释放，成功条目不会被 retry 重放；
- `FAILED`：清理失败，错误和 disposer 保留，可 retry。

## 启动、配置与依赖

### 组件一直是 WAITING

读组件 diagnostics 和 Activation requirements：

- `MISSING_CAPABILITY`：REQUIRED provider 不存在。提供对应 Capability 后自动启动。
- `BINDING_CYCLE`：依赖图有环。调整结构；拓扑变化后自动重试预算会重置，也可显式 `retryAsync()`。
- `NON_CONVERGENT_RECONCILE`：同一指纹反复重激活超过 `maxReconcileIterations`。默认上限 256。先稳定 provider 或配置，再改变拓扑指纹。

DYNAMIC REQUIRED 只有首次启动要求 provider 存在；ACTIVE 后 provider 暂时缺失不会把 consumer 改成 WAITING。

### 遇到 INVALID_CONFIG

`INVALID_CONFIG` 是当前 Core 的实际诊断码，不是保留码。它表示类型化配置契约被拒绝：

- mount 或 reconfigure 传入 config 为 null；
- raw factory 调用传入的运行时类型不匹配；
- factory normalizer 抛出异常；
- normalizer 返回 null；
- normalizer 返回类型不是声明的 `C`。

事务在发布前拒绝，generation 不变。修正配置或 normalizer 后重新提交。

Loader 的 raw decoder 失败不会伪装成 Core `INVALID_CONFIG`；Loader 结果使用自己的 `CONFIG_INVALID`。详见下文 Loader 部分。

### 等值 provider 替换后组件仍重启

这是设计行为。PINNED BindingSet 的身份包含 registration id，不比较 value 的 `equals()`。新注册即使值相等，也是新的 provider 代际，会触发依赖闭包重新 Activation。这样可以避免等值对象掩盖真实换代。

### OPTIONAL 变化也触发重启

OPTIONAL 只表示缺失时也能启动。PINNED OPTIONAL 的出现、消失和替换仍进入固定 BindingSet，并触发新 Activation。希望 consumer 不重启时使用 `dynamicOptional`，并接受 provider 缺失窗口中的 `CapabilityUnavailableException`。

### require/find/subscribe 报使用方式错误

`ActivationContext.require/find` 只能读取 descriptor 声明的 PINNED key；`subscribe` 只能读取 DYNAMIC key。未声明 key、DYNAMIC key 用 require、PINNED key 用 subscribe 都会立即失败。

宿主 `ContextView.require/find` 没有 descriptor 限制，但它不建立 Binding，也不持有调用 lease。不要用它实现动态路由。

### start 期间依赖被替换

候选 Activation 会判定 stale：暂存注册和资源回滚，按最新 BindingSet 重新调度。这不是业务 `ACTIVATION_FAILED`，不会产生启动失败诊断。频繁出现说明结构变化过快或存在拓扑问题。

## Dynamic Capability

### provider 替换后 consumer 没有重启

DYNAMIC 绑定不进入固定 BindingSet。`subscribe()` 得到的 `DynamicCapability<T>` 在每次 call 或 proxy 方法开始时解析当前已提交 registration。旧调用持有 lease，新调用可以立即使用新 provider，旧 provider cleanup 等待旧调用完成。

### CapabilityUnavailableException 和 DynamicCapabilityClosedException 的区别

`CapabilityUnavailableException` 表示调用入口仍然开放，但当前没有可获取的已提交 provider：

- dynamic optional provider 缺失；
- dynamic required consumer ACTIVE 后 provider 被撤销；
- provider registration 已 retired，不能接受新 lease；
- 候选 provider 仍在 STARTING，暂存 registration 尚未提交；
- consumer 当前不是 tracking graph 的 ACTIVE Activation。

这类调用可以由业务按暂时不可用处理，之后重试。

`DynamicCapabilityClosedException` 表示当前 Activation 的动态调用准入闸门已关闭：

- consumer 正在 STOPPING 或 dispose；
- 候选 Activation 被 stale 回滚；
- Runtime 正在 close；
- `SpringDynamicBridge.closeAsync()` 后继续使用旧 bridge proxy。

对于同一个 `DynamicCapability` 对象，这种状态不可恢复；新一轮 Activation 会创建新的订阅入口。

### available() 为 true 但调用仍不可用

`available()` 是 advisory 观测值，不建立 lease。观察和真正调用之间可能发生 provider 提交、撤销或 consumer 停止。业务重试逻辑应以调用异常为准，不要把 available 当作成功保证。

### proxy 类型被拒绝

动态 proxy 的 Capability contract 必须是 Java interface，并且 `proxy(Class<P>)` 只接受与 `CapabilityKey.type()` 精确相同的 `Class`。子接口、实现类、非 interface 都会被拒绝。

### 连续 proxy 方法拿到不同实现

每个 proxy 方法独立获取 lease。两次方法调用之间 provider 可以替换。需要固定同一 provider 时，把多个方法放入一次 `call` 或 `callAsync`，Spring bridge 使用 `withCurrent` 或 `withCurrentAsync`。

### STOPPING 长期不收敛

查看 runtime snapshot 的 lifecycleScopes 和 dynamic 调用来源：

1. 同步 dynamic callback 未返回；
2. proxy 或 `callAsync` 返回的 `CompletionStage` 未完成；
3. provider cleanup 或 consumer lifecycle entry 等待上述 lease；
4. 依赖闭包按消费方先于提供方的顺序清理，下游未完成时上游保持 STOPPING。

不要通过再次 dispose 抢占；修复阻塞调用或完成 stage 后，原有 settlement 会继续。

### dynamic required 启动时 provider 消失

候选 Activation 会回滚并回到 WAITING 或按最新结构重调度，不记为 `ACTIVATION_FAILED`。该规则针对启动期间 required provider 存在性变化，典型是候选启动后原 provider 被撤销；consumer ACTIVE 后 provider 消失不重启。

### dynamic 依赖形成环

DYNAMIC 边同样参与依赖图环检测。混合 dynamic/pinned 依赖形成 SCC 时会得到 `BINDING_CYCLE`，不能通过 dynamic 绕过拓扑检查。

## Knotra Beans

### creator 返回 null

Bean creator 返回 null 立即使本次 Activation 失败，诊断是 `ACTIVATION_FAILED`，message 包含 component id。Core 不会把 null 当作空 Bean 或未发布输出。

### output 返回 null 或输出部分失败

所有输出在发布前依次解析。某个直接输出为 null、mapper 返回 null、类型不匹配或发布被拒绝时，本次候选 Activation 整体回滚，已创建 Bean 会被已登记的 cleanup 清理，不会提交部分输出。

### unmanaged Bean 没有关闭

`.unmanaged()` 表示 Knotra 不登记 cleanup。对象由创建方或外部借入方拥有，不会因为 Activation 结束而自动 close。宿主必须自己保证释放。

### AUTO 清理选了哪个方法

按 Bean 实际运行时类型推断：

```text
AsyncCloseable -> manageAsync(closeAsync)
AutoCloseable -> manage(close)
普通对象 -> 不登记
```

同时实现两者时 `AsyncCloseable` 优先。`destroyWith` 或 `destroyAsyncWith` 是显式覆盖，替换 AUTO 推断。

### 清理失败后怎么重试

第一次清理失败会让组件 settle 为 `FAILED`，失败 lifecycle entry 保留错误和 disposer。调用 `handle.retryAsync()` 只重试失败条目，已经成功的条目不会再次执行。disposer 应幂等，并能在部分失败后继续收敛。

### initializer 失败后资源是否泄漏

不会。Beans 的顺序是 creator、立即登记 cleanup、initializer、输出。initializer 抛错时，本次 Activation 回滚并执行已登记清理。

### optional 注入什么形态

`Beans.optional(key)` 注入 `Optional<T>`，缺失时是 `Optional.empty()`，不是 null。Pinned optional 的出现或消失仍会重建 Bean。dynamic optional 可注入 proxy 或 `DynamicCapability`，provider 缺失时每次调用得到 unavailable 异常。

## Beans Processor

### 生成物在哪里，如何使用

`@KnotraBean` 是 SOURCE 注解。Processor 为顶层类在同包生成：

```text
demo.OrderService
    -> demo.OrderService_KnotraFactory
```

生成类实现 `ComponentFactory<C>`，并公开 `definition()`。使用方式与手写 factory 相同：

```java
var factory = new demo.OrderService_KnotraFactory();
ComponentHandle<C> handle = runtime.mount("order-service", factory, config);
```

无配置 Bean 使用 no-config mount。生成源码不使用运行时反射，不嵌入时间戳，重复编译生成相同源码。

### 常见编译诊断

Processor 在编译期拒绝：

- `@KnotraBean` 不在顶层 class、class private 或 abstract；
- Bean class 或 constructor 声明类型参数；
- 没有 id 或 id 空白；
- lifecycle 不是 `AUTO` 或 `UNMANAGED`；
- config 是 primitive、generic 或 parameterized type；
- config 或 contract 对同包生成类不可访问；
- constructor 不是 exactly one `@KnotraConstructor`，或 constructor private；
- 构造参数缺少唯一注解；
- required 参数不是精确 contract 类型；
- optional 参数不是精确 `Optional<contract>`；
- dynamic contract 不是 interface，或参数不是精确 interface 类型；
- contract 或 output 是 primitive、void、generic 或 parameterized type；
- output contract 不兼容 Bean 类型；
- dependency/output capability 名称重复；
- init、destroy 或 normalizer 方法签名不满足要求；
- async destroy 不返回 `CompletionStage<Void>`；
- `@KnotraDestroy` 与 `UNMANAGED` 组合。

校验失败不生成 factory。

### 生成类名冲突

如果目标包中已经存在：

```text
demo.BadBean_KnotraFactory
```

编译失败，诊断说明无法生成该 fully-qualified type。Processor 不会覆盖已有类，也不会改用临时名。重命名 Bean、已有类或 factory。

### 没有 @KnotraOutput 时发布了什么

什么 Capability 都不发布。Bean 仍由 Activation 创建和清理，可作为组件内部对象或通过子结构间接工作。需要跨组件可见时显式声明 output 的名称和精确 contract。

## Spring 集成

### optional 和 optionalAsOptional 有什么不同

```java
.optional("provider", PROVIDER)
```

provider 存在时注册 `T`，缺失时不注册该 bean name。适合按 bean name 或单一候选类型注入。

```java
.optionalAsOptional("provider", PROVIDER)
```

无论是否存在都注册 `Optional<T>`，缺失时是 `Optional.empty()`。由于手动注册 singleton 缺少泛型元数据，注入它应使用 bean name 或 qualifier。

两种都是 PINNED optional；provider 出现、消失或替换都会重建 child context。

### typed config 是 Spring Bean 吗

它作为 external singleton 注册进 child context，默认 bean name 是 `knotraConfig`，可用 `configBeanName(...)` 修改。它不会收到 Spring 初始化或销毁回调，也不会被 child context close。`configNormalizer(...)` 在注册前运行。

### refresh 或 customizer 失败后是否泄漏

不会正常泄漏。Knotra 在执行任何 Spring 操作前已登记 cleanup；refresh 或 customizer 失败时，未完成 context 的已创建 singleton 会被 `destroySingletons()` 清理，组件进入 FAILED，输出不发布。

外部 config 和外部 Capability 是 external singleton，不由 Spring 销毁。customizer 手动注册并实例化的 Spring singleton 属于 context，会被清理。

### classLoader(...) 只影响 customizer 吗

不是。显式 loader 会设置到 Spring context，并覆盖 register、config 和 dependency 注册、customizer、refresh、output 解析和 cleanup 的 TCCL。每段结束都会恢复调用线程原 TCCL。

多个 annotated classes 来自不同 ClassLoader 时，未显式设置 loader 会在 `build()` 被拒绝。此时必须显式选择一个符合加载需求的 loader。

### SpringContextCloser 失败后如何重试

Hook 抛错或返回异常 stage 时，Knotra 不物理关闭 context，组件进入 FAILED，hook 作为 lifecycle entry 保留。修复后 `retryAsync()` 会再次执行 hook；hook 正常完成后 Knotra 总是执行物理关闭。当前实现也把返回 null 视为 hook 成功并立即物理关闭；实现方应返回明确的已完成 stage。Hook 必须幂等，不能假设第一次调用没有产生部分副作用。

默认无 hook 时，Spring 自己的 Bean 销毁异常可能被 Spring 记录并吞掉，不能可靠进入 Knotra retry。需要可观测清理失败时必须使用 hook。

### by-type expose 找错 Bean

`expose(key)` 使用 Spring by-type lookup。多个可赋值候选会导致 Spring 解析失败或不稳定选择。多个同类型或继承关系复杂的 Bean 应使用：

```java
.expose(SERVICE, "serviceBeanName")
```

Knotra 在查找后检查对象是否可赋值给 Capability contract，子类实例合法。By-type 候选还包含 config、借入依赖和 dynamic proxy 等 external singleton；需要确认输出来自 Spring 内部 Bean 时，使用稳定 bean name。

### SpringDynamicBridge 调用旧 provider

Bridge proxy 的每个方法独立持有 lease。替换 provider 后，已经开始的调用继续使用旧 provider，直到同步返回或异步 stage 完成；新调用使用新 provider。多方法事务使用 `withCurrent` 或 `withCurrentAsync`。

Bridge close 后：

- `bridge.proxy()` 抛 `IllegalStateException`；
- 旧 proxy 调用抛 `DynamicCapabilityClosedException`；
- bridge Capability 从 Context 消失；
- 清理失败时重复 `closeAsync()` 继续重试。

## 生命周期与关闭

### 组件 FAILED 后如何恢复

按诊断分：

- `ACTIVATION_FAILED`：start 或 Bean 创建失败。修正原因后 `retryAsync()` 或提交新的 reconfigure。
- `CLEANUP_FAILED`：lifecycle 条目清理失败。`retryAsync()` 只重试失败条目。

Loader 管理的条目还可调用 `loader.retry(path)`；FAILED Context 会先释放子树，FAILED Component 走组件 retry。

### close 卡住

按层定位：

1. Runtime lifecycleScopes 中哪个 entry 是 PENDING；
2. 同步 disposer 是否阻塞；
3. 异步 disposer 返回的 stage 是否永不完成；
4. EventBus listener 是否等待自身 close；
5. dynamic 调用是否未返回或异步 stage 未完成；
6. PF4J drain 是否等待 owned handle、in-flight mount 或 stop/unload。

修复后重复同一层 `closeAsync()`，不会从头重放成功项。

### 宿主 provide 的对象会被 revoke 关闭吗

不会。`runtime.provide(...)` 只发布注册；宿主仍拥有对象生命周期。`revoke` 移除注册并等待 registration lease 排空，但不执行对象的 `close`。组件 Activation 自己创建并登记的对象才由 Knotra 清理。

### 推荐关闭顺序

```text
KnotraLoader
    -> Pf4jArtifactAdapter
    -> KnotraRuntime
```

必须等待每层 stage。并发或反向 close 会协调收敛，但排查更复杂。任何一层失败都不要假设已关闭。

## EventBus

### 同名同结构事件订阅不上

事件身份是精确 JVM `Class`，不是全限定名。不同 artifact ClassLoader 加载的同名类是不同身份，并且同一事件名在存活订阅或已接受分发期间不能重绑到另一个 `Class`。合约事件类型必须放在共享合约包并由宿主加载。

### listener 返回 null stage

Parallel、Serial 和 Waterfall listener 返回 null 视为监听失败，失败进入 `EventDispatch.failures()`，外层已接受 dispatch 的 stage 不会因此异常完成。Bail listener 同步返回 boolean，不存在 null stage。

### closeAsync 在等什么

等待 close 请求被观察到之前已经接受的分发：

- Parallel 等待全部 listener stage；
- Serial、Bail 和 Waterfall 等待 stage 链走完或提前停止；
- 跳过的 listener 租约也会在整体 dispatch 收敛时释放。

之后的新订阅和分发被拒绝。重复 close 返回同一个 close future。

### listener 里等待自己的 close 会死锁

会。已接受分发必须等 listener 返回或完成 stage，而 close 又在等这个分发收敛。listener 应直接返回 stage，不能同步等待包含自己的 subscription 或 bus 关闭。

### EventBus executor 可以换吗

公开 API 没有 EventBus executor 注入入口。EventBus 组件每次 Activation 创建自有 executor 并在关闭时停止它。需要隔离或限流时，在宿主调用侧控制 dispatch 频率和并发。

## PF4J Artifact

### loadArtifact 失败

`loadArtifactAsync` 的失败 future 抛出 `ArtifactOperationException`，先读其中的 artifactId、phase 和诊断。若失败已进入 adapter 记账，再查询：

```java
adapter.artifact(artifactId);
adapter.diagnostic(artifactId);
```

加载前会解析依赖闭包、拓扑、版本冲突和 descriptor。失败会按加载逆序回滚本次新加载插件；回滚仍有残留时保留 FAILED 诊断，不会伪造 ACTIVE。

### factory 不存在和 config token 不匹配是同一件事吗

不是：

- `factories().resolve(...)` 找不到 factory 返回 `Optional.empty()`；
- typed `resolve(factoryId, configType)` 发现精确 config token 不匹配时立即抛 `IllegalArgumentException`；
- 不存在 token mismatch 到 Loader `RESOLUTION_FAILED` 的专门映射，不要按这种映射写告警逻辑。

typed resolve 的 token 是精确 `Class<C>` 相等，不接受父子类型或 erased type。

### 官方 PF4J Loader 中 decoder 失败是什么码

`Pf4jFactoryResolver` 先调用 artifact export 的 `ConfigDecoder<C>`。decoder 抛错或返回非法值时，Loader 在准备阶段记录 `LoaderDiagnosticCode.CONFIG_INVALID`，整批 reconcile 不触碰现有树。

Core factory normalizer 失败同样会被映射为 Loader 的 `CONFIG_INVALID`。这是 Loader 诊断码，不是 Core 事务中的 `DiagnosticCode.INVALID_CONFIG` 对象。

### DRAIN_FAILED 怎么恢复

Drain 顺序：

1. 拒绝新 mount；
2. 等待 in-flight mount；
3. 刷新 ownership；
4. 释放 owned component 根；
5. stop PF4J plugin；
6. unload plugin；
7. 释放 catalog 和 loader 引用。

任一步失败进入 `DRAIN_FAILED`。读 diagnostic 和 ownership，修复后调用 `retryDrainAsync(artifactId)` 或再次 `closeAsync()`。并发 unload 同一依赖闭包会共享同一个 drain future。

### UNLOADED 后 ClassLoader 未回收

Knotra Snapshot、factory catalog 和运行时结构不保留插件 ClassLoader 强引用。未回收通常表示宿主或其他库仍持有：

- capability value；
- 动态 proxy；
- 插件实现的 `Class` 或实例；
- listener、线程、连接回调；
- 静态集合、全局缓存或线程池闭包。

组件把对象交给外部代码后，释放责任转移到该代码。

### 插件私有 contract 被拒

Capability contract、config token、动态 contract 和输出 contract 必须能由宿主共享 parent 按相同二进制名解析为同一个 `Class`。插件私有类型或插件私有副本会在工厂发现、descriptor 校验、require/find/subscribe/provide 或子挂载前被拒绝，不会进入 Core 类型表。

### mount 报 null config

配置型 artifact factory 的 typed mount 拒绝 null config。应先调用 `decodeConfig(rawConfig)`；无配置 factory 使用 no-config mount。decoder 负责 raw 到 typed config，Core normalizer 随后负责 typed config 规范化。

## Loader

### CONTEXT_CONFLICT

Loader 的路径和 mountId 是受控记账。目标 canonical path 或 mount slot 已被其他所有者占用时拒绝，不隐式认领。检查宿主事务、其他 Loader 或手工 mount 是否占用同一路径。

### reconcile 失败会留下半个新增批次吗

要区分失败阶段。路径、父节点、resolver、raw decoder 和冲突会先预检，失败时不触碰结构；Core config normalizer 在逐项 mount 事务中执行。mount 事务或 controlled mount 被拒绝时，Loader 按 LIFO 补偿本批此前新增的 handle 与 Context。mount 已提交但组件 `start()` 失败时，该 entry 会保留为 `FAILED` 并记录 `ACTIVATION_FAILED`，不会整体回滚成“从未挂载”；修复后显式 `retry(path)`。

替换 factory 失败时，Loader 尝试用旧实现补偿重挂载；补偿也失败才报告 `COMPENSATION_FAILED`，此时必须读诊断确认残余。

### reconcile 遇到 artifact drain

被 drain 的 mount 提交会被拒绝，本批回滚，不留下 partial entry。下一次 reconcile 可以选择新 artifact、fallback resolver 或保持其他稳定条目。

### ACTIVATION_FAILED 后 reconcile 会自动重试吗

不会。Loader 认为失败 start 可能是持续外部故障，自动重试会放大副作用。调用：

```java
loader.retry(path).requireConverged();
```

如果组件仍 FAILED，retry 结果会携带 `ACTIVATION_FAILED`。重配置已保存到期望记账时，修复后 retry 使用最新配置。

### Loader 已关闭

close 清理失败后 `closed` 保持 true，诊断保留，重复 close 可继续收敛。新的 reconcile 或 retry 返回 `CLOSED` 诊断。恢复运行需要创建新 Loader；已有 Runtime 结构是否可复用取决于其当前 Snapshot。

### INVALID_TREE

常见原因：

- path 为空；
- 使用 `..`；
- child path 越出 parent；
- normalized path 重复；
- 期望树缺少父条目；
- retry path 不受管理或目标不可重试。

路径会 trim、把反斜杠归一为斜杠、折叠空段和 `.`；相对单段 child 会拼接父路径。

## 诊断码表

### Core `DiagnosticCode`

| 诊断码 | 含义 | 常见处理 |
|---|---|---|
| `MISSING_CAPABILITY` | REQUIRED Capability 缺失，组件 WAITING | provide 后自动启动 |
| `CAPABILITY_SLOT_OCCUPIED` | 同 Context 同名 slot 已占用 | 先 revoke，或用子 Context 遮蔽 |
| `CAPABILITY_TYPE_CONFLICT` | 同名 Capability 类型不是已固化精确 JVM Class | 统一合约类型来源或换名称 |
| `BINDING_CYCLE` | 依赖图存在环 | 改结构；拓扑变化后可重试 |
| `ACTIVATION_FAILED` | 组件启动失败 | 修正后 `retryAsync()` 或 reconfigure |
| `ROLLBACK_FAILED` | 公开枚举项；当前 Core 生产路径没有发射点 | 不作为当前排障分支 |
| `CLEANUP_FAILED` | Lifecycle 条目清理失败 | `retryAsync()` 只重试失败条目 |
| `NON_CONVERGENT_RECONCILE` | 自动重激活超过上限 | 稳定结构或配置后重试 |
| `INVALID_LIFECYCLE_OPERATION` | 目标、参数或调用时机非法 | 检查状态和 Runtime 是否 closing |
| `INVALID_MOUNT_ID` | mountId 空白或当前 Context 已占用 | 换 mountId 或释放旧挂载 |
| `INVALID_CONFIG` | 类型化配置为 null、类型错误，或 normalizer 抛错、返回 null、返回错误类型 | 修正 config 或 normalizer |

### Loader `LoaderDiagnosticCode`

| 诊断码 | 含义 | 常见处理 |
|---|---|---|
| `RESOLUTION_FAILED` | resolver 未返回实现或解析抛错 | 检查 FactoryRef、版本、factory catalog |
| `CONFIG_INVALID` | raw decoder 或 Core normalizer 拒绝 | 修正 raw config 或 typed config |
| `INVALID_TREE` | 期望树或 retry path 非法 | 修正路径、父子关系和重复项 |
| `BASE_UNAVAILABLE` | base Context 不属于 Runtime 或不是 ACTIVE | 检查 Context 状态和归属 |
| `CONTEXT_CONFLICT` | 路径或 mountId 被其他所有者占用 | 清理占用方或调整路径 |
| `STRUCTURE_REJECTED` | 受控挂载或 Runtime 结构事务被拒 | 读 Core diagnostics |
| `TEARDOWN_FAILED` | 组件或 Context 清理未到 DISPOSED | 修复资源后重试 |
| `REPLACEMENT_BLOCKED` | 新实现被拒，旧实现已补偿恢复 | 保持旧版本并读原因 |
| `COMPENSATION_FAILED` | 回滚或补偿自身失败，可能有残余 | 读全部诊断并手动确认 |
| `ACTIVATION_FAILED` | 组件 FAILED，需要显式 retry | `loader.retry(path)` |
| `CLOSED` | Loader 已关闭 | 重建 Loader 或修正调用时序 |

## JVM 重启边界

大多数失败可以在当前 JVM 内恢复：

- 组件 start 失败：修复后 retry；
- 配置错误：修正后重新 mount 或 reconfigure；
- 清理失败：修复资源后重复 close 或 retry；
- artifact drain 失败：释放外部引用后 retryDrain。

需要重启 JVM 的情况：

1. 插件 ClassLoader 或 native 资源已泄漏且没有编程释放路径；
2. 宿主或第三方库持有插件对象且无法释放；
3. PF4J stop/unload 半失败后无法通过 retryDrain 收敛；
4. 外部线程或资源管理器已经进入无法安全重复操作的状态。

在这些边界上，Knotra 保留 FAILED 或 DRAIN_FAILED 和诊断，不会把未完成的清理报告为成功。

并发与排空测试见 [Knotra 测试指南](<Knotra 测试指南.md>)；插件工程与共享合约实践见 [Knotra 插件工程化手册](<Knotra 插件工程化手册.md>)。
