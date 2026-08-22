# Knotra 线程模型与生产实践

本文描述当前 `0.1.0-SNAPSHOT` 的线程、执行器、ClassLoader 和生产运维边界。结论只针对当前源码；公开 API 与用法见 [Knotra API 与集成指南](<Knotra API 与集成指南.md>)，POJO 与 Spring 用法见 [Knotra Beans 与 Spring 集成指南](<Knotra Beans 与 Spring 集成指南.md>)。

**最简单的规则**：

1. 结构事务 callback 在调用线程执行，只做意图准备，不要阻塞在外部资源上。
2. 组件 `start()` 和 lifecycle disposer 不在宿主调用线程执行，可以做初始化和清理，但必须最终返回或完成 stage。
3. 动态调用在调用方线程进入 provider，长调用会延迟旧 provider 和消费方的清理。
4. EventBus listener 的 TCCL 是 listener 自己的 ClassLoader，不要依赖宿主线程默认 TCCL。
5. 所有 `closeAsync()` 都必须等待返回的 stage，失败后读 Snapshot 再重试。

## 执行器总览

Knotra 没有全局线程池，也没有内建定时器、调度器或心跳线程：

| 层 | 执行器 | 线程 | 串行性 |
|---|---|---|---|
| Core 组件过渡 | `newVirtualThreadPerTaskExecutor()` | 未命名虚拟线程 | 同一 ComponentHandle 的过渡串行；不同 handle 可并发 |
| Loader | 单线程 executor | 守护线程 `loader-<id>-coordinator` | 一个 Loader 的 reconcile、retry、close 串行 |
| PF4J adapter | 单线程 executor | 守护线程 `knotra-pf4j-artifact-coordinator` | artifact 加载、读取、drain 状态变更串行 |
| EventBus | cached thread pool | 守护线程 `event-bus-<N>-worker` | Parallel listener 可并发；Serial/Bail/Waterfall 按 stage 链顺序执行 |
| EventBus 关闭 | 临时线程 | 守护线程 `event-bus-<N>-close` | 等待自有 executor 终止 |

`knotra-beans` 和 `knotra-spring` 不创建自己的执行器。Bean 创建和 Spring child context 启动发生在 Core 的 Activation 虚拟线程上；异步清理的完成线程由返回的 stage 决定。

EventBus 组件使用上述自有 cached pool。当前公开 API 不提供 EventBus executor 注入入口；不要通过实现细节或反射替换执行器。

## 普通组件规则

组件外壳和业务对象的生命周期不同：

```text
ComponentFactory.create()
    只在挂载准备时创建 Component 外壳

每次 Activation
    start(context, config)
    -> 创建本次业务对象或资源
    -> 登记 LifecycleScope
    -> 发布本次 Capability
```

因此组件外壳可以跨 Activation 复用，但必须无状态。不要把业务对象、依赖、上一次 `ActivationContext`、动态代理或 provider 值缓存到外壳字段。

执行边界：

- `start()` 在 Core 虚拟线程执行，且不持有 Runtime 协调器锁。连接数据库、读取远程配置等慢操作不会阻塞其他组件启动或宿主事务。
- 同一 handle 的 start、stop、reconfigure、retry、dispose 合并为一条过渡链；前一个过渡未完成时，后一个请求共享同一个结算 Future 或等待下一次调度。
- `start()` 阻塞会延迟自己的下一次过渡，也会推迟依赖它的下游重激活，但不会占住协调器线程。
- 虚拟线程内应避免长时间持有 `synchronized` 或执行会 pin carrier 的原生调用；重锁优先使用 `ReentrantLock`，长阻塞尽量移出 `start()`。
- `start()` 抛出的 `Exception` 或 `Error` 被捕获并进入失败清理；暂存注册和已登记资源回滚，组件进入 `FAILED`，诊断保留根因文本。
- `ActivationContext` 只在本次 `start()` 内有效。`start()` 返回后继续保存并调用它属于组件契约错误。

清理登记应遵循可逆顺序：先创建资源，立即登记 cleanup，再执行初始化，最后发布 Capability。这样初始化失败、输出冲突或 Activation 变 stale 时，资源都能随本次 Activation 回滚。

## 宿主事务准备

`runtime.transact(...)` 的 callback 在调用线程执行：

```java
var receipt = runtime.transact(tx -> {
    tx.revoke(old);
    return tx.provide(runtime.root(), TOOL, replacement);
});
```

callback 内只记录结构意图。对于 mount，调用线程会执行 factory 的 `create()` 与 `normalizeConfig()`，但不会执行 `Component.start()`。factory 创建对象应保持轻量；昂贵资源留给 Activation 的 `start()`。

Runtime 协调器临界区只做确定性校验、依赖图计算和代际发布：

- factory、normalizer、组件 `start()` 和 lifecycle disposer 不持有协调器锁；
- 任一意图非法则整个事务拒绝，已修改的草稿不发布；
- 事务提交是单个 generation，不暴露半提交结构。

callback 抛出异常时抛出 `TransactionRejectedException`，携带结构化诊断；不会返回可忽略的失败值。事务成功返回 `TransactionReceipt`，其中 `settlement()` 只等待该事务直接触发的过渡和注册排空，不是 Runtime 全局静默信号。

## 异步结算

结构提交后，Core 在虚拟线程上驱动组件过渡：

1. 先在协调器内为 STOPPING 组件计算依赖方先于提供方的顺序；
2. 再把每个保留的过渡任务提交到 Core 虚拟线程；
3. 同步 disposer 在清理虚拟线程执行；
4. 异步 disposer 返回 stage，完成线程由该 stage 决定；
5. 所有直接和间接受影响组件清理完成后，事务 settlement 或 close future 完成。

LifecycleScope 默认按全局登记序列 LIFO 清理。`parallelChild(...)` 只并行该 child 的直属条目，不并行相邻 scope 或父级后续条目。清理失败不会阻止其他条目执行；失败条目保留在 Snapshot 中，供 `retryAsync()` 只重试失败部分。

`whenSettled()` 的完成线程是推动最后一次过渡完成的线程。不要在回调里假设线程名、TCCL 或 ContextLoader；需要特定 loader 时必须自己保存并在 `finally` 中恢复 TCCL。

`runtime.closeAsync()` 会先设置 closing 状态，之后新的结构事务被拒绝；根 Context 子树清理仍在 Core 虚拟线程推进。全部清理成功后 Core executor 关闭；清理失败时 close future 异常完成，executor 保留供下一次 close 或组件 retry 收敛。

## Dynamic 调用

DYNAMIC 依赖通过 `ActivationContext.subscribe(key)` 获得 `DynamicCapability<T>`。调用在调用方线程进入：

- 获取 lease 时 Runtime 只在协调器内完成原子性检查，随即离开锁；
- provider 方法或 `call(...)` callback 在调用线程执行；
- `callAsync(...)` 的 callback 也在调用线程启动，但返回的 `CompletionStage` 可以在任意线程完成。

两种调用边界不同：

```java
// proxy 的每个方法独立获取 lease；连续两次方法调用可能命中不同 provider
Api proxy = dynamic.proxy();
proxy.begin();
proxy.commit();

// 一个 callback 只获取一次 lease，多方法事务固定同一 provider
dynamic.call(api -> {
    api.begin();
    api.commit();
    return null;
});
```

租约规则：

- 同步方法返回或抛出异常后释放 provider 与 consumer lease；
- 返回 `CompletionStage` 的 proxy 方法、`callAsync` 和异步 callback 会持有 lease 到 stage 完成；
- provider 注册被提交撤销或替换后立即 retired，新调用不能拿到旧 provider；
- 旧 provider 的 lifecycle cleanup 等待旧 lease 归零；
- consumer 进入 STOPPING 时关闭准入闸门，已开始的调用继续，新调用抛 `DynamicCapabilityClosedException`；
- Runtime close 同样等待在途 dynamic lease。

因此一个长时间未完成的 dynamic 调用会推迟旧 provider cleanup、consumer teardown、组件 reconfigure settlement、host registration revoke settlement 和 Runtime close。这是防止“调用进行中对象被销毁”的刻意行为。

required 与 optional 的差别只作用于首次启动：

- `dynamicRequired` 在候选 Activation 可启动前要求 provider 已提交存在；
- 启动期间 required provider 的存在性发生变化，典型是候选启动后原 provider 被撤销；候选会 stale 回滚并按最新状态重新调度，不计业务失败；
- consumer 进入 ACTIVE 后，provider 消失或替换不重建 consumer；
- provider 缺失时 `available()` 为 false，调用抛 `CapabilityUnavailableException`；
- 新 provider 提交后，下一次调用自动解析新 registration。

`available()` 是 advisory 观测值，不建立租约。它返回 true 后，另一个线程仍可能先完成替换；它返回 false 也不代表之后调用一定失败。真正的调用在协调器内原子获取 consumer gate 与 provider lease。

动态 proxy 只支持 Java interface，并且 `proxy(Class<P>)` 的参数必须与 CapabilityKey 的精确 contract `Class` 相同。子接口会被拒绝。DYNAMIC 边界同样参与依赖环检测，动态边不能用来绕过 `BINDING_CYCLE`。

## Beans 清理

`knotra-beans` 把普通构造器适配为 Activation-owned Bean。它不创建线程：

```text
解析依赖
    -> creator 创建新 Bean
    -> null 则 Activation 失败
    -> 立即登记 cleanup
    -> initializer
    -> 解析全部输出
    -> 发布输出
```

输出发布发生在同一个候选 Activation 中。任何输出 mapper 返回 null、类型不匹配或发布被拒绝时，本次候选整体回滚，不会提交部分 Capability。

生命周期策略：

| 策略 | 行为 |
|---|---|
| `AUTO` | `AsyncCloseable` 优先走 `manageAsync`；否则 `AutoCloseable` 走同步 close；普通对象不登记清理 |
| `destroyWith` | 自定义同步 disposer，替换 AUTO 推断 |
| `destroyAsyncWith` | 自定义异步 disposer，settlement 等待 stage |
| `unmanaged` | Knotra 不登记 cleanup，创建方或借入方自行拥有对象 |

自定义 disposer 必须可重试。第一次清理失败会让组件进入 `FAILED`，成功条目不会重放；调用 `retryAsync()` 时只重新执行失败条目。

## EventBus

每次 EventBus 组件 Activation 创建新的 `DefaultEventBus` 和新的自有 executor。工厂外壳没有激活期状态。

分发模式：

| Mode | 执行线程 |
|---|---|
| `SYNC` | dispatch 调用线程，按订阅顺序逐个执行 |
| `PARALLEL` | 本次分发的 listener snapshot 全部提交到 EventBus executor 并发执行 |
| `SERIAL` | stage 链顺序执行；第一段通常在调用线程，后续段在前一段完成线程 |
| `BAIL` | 顺序执行；listener 本身同步返回 claimed |
| `WATERFALL` | 顺序执行；前一个返回的 stage 完成后才把变换值传给下一个 |

分发一旦被接受，listener 集合和事件绑定的精确 JVM `Class` 固定。之后注册、取消订阅或关闭请求不会改变这次分发的可见集合。

每个 listener 回调执行前，TCCL 切换为 `listener.getClass().getClassLoader()`，回调结束恢复原 TCCL。Parallel executor 工作线程的默认 TCCL 是 bus 创建线程当时的 TCCL，但回调期间会被 listener TCCL 覆盖。

`whenIdle()` 等待调用被观察到之前已经接受的分发。`closeAsync()` 的观察边界是写锁内设置 closed：此前接受的分发全部收敛，之后的新订阅和分发被拒绝。close 成功前 EventBus 会 `shutdownNow()` 自有 executor，并等待终止；已接受的 listener 返回的 stage 决定最终收敛时间。

listener 内不要同步等待包含自己的 subscription 或 bus 关闭，否则会形成自等待死锁。需要异步清理时返回 stage，让 dispatch chain 和 lifecycle cleanup 自然收敛。

## Spring 集成

`SpringModules` 的组件外壳无状态；每次 Activation 创建一个全新的 `AnnotationConfigApplicationContext`。它不创建 Spring 专属线程，启动发生在 Core Activation 虚拟线程，清理发生在 Core lifecycle 清理路径。

ClassLoader 选择：

- 使用 `classLoader(...)` 时，该 loader 同时设置到 Spring context，并覆盖 start、customizer、refresh、output 解析和 cleanup 的 TCCL；
- 未显式设置时，有 annotated classes 则使用第一个 annotated class 的 loader；
- 没有 annotated classes 时使用 `knotra-spring` 模块的 loader；
- 多个 annotated classes 来自不同 loader 时，`build()` 直接拒绝，必须显式选择一个 loader。

不要把 `classLoader(...)` 理解成 customizer-only 选项。它的作用覆盖完整 Activation 边界，并在每段结束的 `finally` 中恢复调用线程原 TCCL。

启动顺序：

1. 创建 child context；
2. 先向 Knotra LifecycleScope 登记清理；
3. 设置 context ClassLoader 并切换 TCCL；
4. register annotated classes；
5. 把 typed config 注册为外部 singleton；
6. 把外部 Capability 注册为外部 singleton；
7. 执行 customizer；
8. `refresh()`；
9. 按声明解析全部输出；
10. 恢复原 TCCL 后，由 Activation 提交输出。

外部 config 和 Capability 使用 Spring 的 external singleton registry 注册。它们不会收到 Spring 的初始化回调，也不会被 child context 执行 `DisposableBean`、`@PreDestroy` 或推断的 `close()`。对象所有权仍属于宿主或原 provider。

refresh 或 customizer 失败时，Knotra 会清理 Spring 已创建的早期 singleton，并让组件进入 `FAILED`，不发布输出。输出查找失败同样回滚整个候选 Activation。

清理顺序：

```text
可选 SpringContextCloser hook
    成功
        -> 物理关闭 child context
    失败或返回异常 stage
        -> 保留 context 和 hook，组件 FAILED，可 retry
```

没有 hook 时，active context 走 `close()`；refresh 未完成的 inactive context 走 `destroySingletons()`。Spring 对自己内部 Bean 的销毁异常可能由 Spring 记录并吞掉；这些错误不能可靠驱动 Knotra retry。需要清理失败可观测时使用 `SpringContextCloser`，并让 hook 幂等。

Pinned required 或 optional 的 provider 变化会重建整个 child context。dynamic required/optional 注入的是 Core 动态 proxy；provider 替换不重建 context，代理方法在调用时获取新 provider lease。

宿主 Spring singleton 需要稳定接口时可使用 `SpringDynamicBridge`。Bridge 本身是一个 Knotra component；`proxy()` 每个方法独立持有 lease，`withCurrent/withCurrentAsync` 在一个 callback 中固定同一 provider。关闭 bridge 会先停止动态调用准入，等待在途调用归零，再释放 bridge component。

## Loader 与 PF4J 协调器

Loader 和 PF4J adapter 都使用单线程协调器，但边界不同：

- Loader coordinator 串行执行 reconcile、显式 retry 和 close。协调器线程上的重入调用会被拒绝，避免组件或受控策略回调自等待。
- PF4J coordinator 串行化 artifact 加载、目录读取、snapshot、drain 状态切换和最终 stop/unload。协调线程内的嵌套读取会内联执行；drain 的异步等待会释放协调线程，之后再重新进入协调器完成最终状态迁移。

不要在组件 `start()` 中阻塞等待同一个 Loader 的 reconcile，也不要在 Loader 受控策略中重入同一个 Loader。跨 Loader 或跨 Runtime 的调用也应有超时和失败路径。

PF4J 加载会先离线解析完整依赖闭包和拓扑顺序，再加载、启动和发布 factory。drain 先拒绝新 mount、等待 in-flight mount、释放 owned component，然后 stop/unload PF4J plugin。任何一步失败保留 `DRAIN_FAILED` 和诊断，下一次 close 或 `retryDrainAsync` 从可重试状态继续。

## 关闭顺序

推荐从外到内：

```java
loader.closeAsync().toCompletableFuture().get(30, TimeUnit.SECONDS);
adapter.closeAsync().toCompletableFuture().get(30, TimeUnit.SECONDS);
runtime.closeAsync().toCompletableFuture().get(30, TimeUnit.SECONDS);
```

Try-with-resources 中资源关闭顺序与声明顺序相反，因此通常先声明 Runtime，再声明 adapter 和 Loader。

并发或反向关闭也能收敛：Runtime close 接管根 Context 时，Loader 和 adapter 会识别该竞态并等待对应清理，而不是重复 dispose 同一结构。但推荐顺序最直接，便于归因。

所有 close 都是幂等推进：

- 成功后的重复 close 复用成功结果；
- 失败后的重复 close 或 retry 只处理剩余清理；
- 不要因为调用了 close 就假设已关闭，必须等待 stage；
- 失败时读取组件、Loader、artifact 三层 Snapshot 后再重试。

## 监控接线

Knotra 自有源码没有 logger 调用：core、beans、processor、events、loader、pf4j、pf4j-loader 和 spring 模块都不输出日志。`knotra-pf4j` 传递的 PF4J 库自身日志仍走 SLF4J。

正确观测入口是 Snapshot：

```java
runtime.snapshot().diagnostics().forEach(this::recordDiagnostic);
runtime.snapshot().lifecycleScopes().forEach(this::recordCleanup);

loader.snapshot().diagnostics().forEach(this::recordLoaderDiagnostic);
loader.snapshot().entries().forEach(this::recordLoaderEntry);

adapter.artifacts().forEach(this::recordArtifactState);
adapter.diagnostic(artifactId).ifPresent(this::recordArtifactDiagnostic);
adapter.ownership(artifactId).forEach(this::recordArtifactOwnership);

eventBus.snapshot().subscriptions().forEach(this::recordSubscription);
```

`adapter.artifacts()` 是状态概览，不携带完整 ownership 和详细 diagnostic。排查单个 artifact 必须使用 `artifact(...)`、`diagnostic(...)` 和 `ownership(...)`。

诊断码是稳定 enum，适合作为指标标签；message 文本可能调整，不要按字符串编程。EventBus 的 `whenIdle()` 可以作为空闲信号，但不是全局组件静默信号。

Knotra 不内建 Micrometer、Prometheus 或健康检查端点，宿主需要自行把 Snapshot、诊断计数、状态集合和 close/settlement 延迟接入观测系统。

## 容量边界

当前版本未发布基准测试数据。机制层面可确定的约束：

- 同一 ComponentHandle 严格串行，状态吞吐受最慢 start 或 cleanup 限制。
- Pinned provider 替换的重新激活范围是受影响依赖闭包；替换频率会成比例放大对象图重建。
- `maxReconcileIterations` 默认 256，必须为正；超过后组件保持 WAITING 并报告 `NON_CONVERGENT_RECONCILE`。
- 结构事务排队时间主要由其他事务的协调器临界区长度决定，而不是组件启动时间。
- Snapshot 构建完整不可变 DTO；结构越大、轮询越频繁，复制和聚合成本越高。
- Loader 和 PF4J coordinator 是单线程；大量 reconcile、artifact 加载和 catalog 读取会在这条链路上排队。
- EventBus parallel listener 的并发度等于每次分发固化的 listener 数，再乘以并发接受的分发数；cached pool 不提供有界队列或背压。
- 动态调用 lease 不设超时；永不完成的同步调用或永不完成的 `CompletionStage` 会永久阻塞相关清理。

生产上线前应按自身的组件数、依赖闭包、替换频率、事件流量、快照轮询频率和最长清理时间做负载测试。

## 安全与资源隔离

Knotra 的 ClassLoader 边界是类型身份和共享包合约，不是安全沙箱：

- 不做插件权限模型、代码签名校验或访问控制；
- 不做线程、内存、连接数或组件数量配额；
- 只应加载可信来源的插件 JAR；
- Capability contract、config token 和共享事件类型必须来自宿主或显式共享合约包；
- PF4J guarded context 会在 require/find/subscribe/provide 和子挂载前校验精确 Class 身份，并强制子挂载继承 artifact 来源。

Runtime、Loader 和 artifact Snapshot 都是纯数据，不引用 provider value、组件实例、disposer、Throwable、`Class` 或 ClassLoader，持有 Snapshot 不会阻止插件回收。ClassLoader 是否能被 GC 取决于宿主和业务代码是否还持有插件对象、接口实例、代理或句柄。

## 生产检查清单

上线前逐项确认：

1. 所有昂贵资源和订阅都登记进当前 Activation 的 LifecycleScope。
2. 同步 disposer 有界执行；异步 disposer 的 stage 一定会完成或失败。
3. 动态多方法事务使用一次 `call`、`callAsync`、`withCurrent` 或 `withCurrentAsync`。
4. 长任务不依赖连续 proxy 方法落在同一 provider。
5. Spring 外部对象不由 child context 销毁；需要可观测清理时配置 retryable closer。
6. 宿主 `runtime.provide(...)` 的对象由宿主负责关闭。
7. EventBus 高峰流量有宿主侧背压或流量控制。
8. Snapshot 和诊断轮询频率经过容量评估。
9. 关闭顺序、超时和失败重试在生产演练中验证。
10. 插件对象没有逃逸进静态字段、全局缓存、线程池闭包或宿主 singleton。

并发与时序测试方法见 [Knotra 测试指南](<Knotra 测试指南.md>)；插件打包、共享合约与卸载实践见 [Knotra 插件工程化手册](<Knotra 插件工程化手册.md>)。
