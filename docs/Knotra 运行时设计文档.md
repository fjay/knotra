## 背景

Knotra 是一个面向 JVM 21 的动态组合运行时，解决的是“应用启动以后组成仍会变化”的一类工程问题：能力提供者在运行中被替换、组件局部重启、插件被加载和卸载、不同上下文需要看到不同实现，以及一次组件运行所创建的注册、监听器、连接和后台任务必须随这次运行一起撤销。

这个问题不能只靠 Service Locator、`start()/stop()`、EventBus 或 PluginManager 的组合解决。普通查找只能回答“当前能否找到一个对象”，无法回答“这个组件绑定的是哪一次 provider 注册”、“provider 替换后哪些直接和间接依赖必须先退出”、“旧运行尚未清理完时能否启动下一代”、“cleanup 失败后还能不能安全重试”。Knotra 把这些关系作为运行时的一等结构管理：Context 决定可见性，Capability 表示类型化依赖，ComponentHandle 表示稳定逻辑挂载，Activation 表示一次可验证和可撤销的运行，LifecycleScope 表示资源所有权。

当前仓库已经是按该模型实现的六模块 Maven 工程，版本为 `io.knotra:knotra-parent:0.1.0-SNAPSHOT`。Core、Events、PF4J adapter、Loader 和跨模块集成测试均可构建与运行；本文描述的是当前源码中的实际行为，而不是最初设想或后续演进计划。

Cordis 的动态组合思想是 Knotra 的设计来源之一，尤其是能力注册身份、组件运行代际和可逆生命周期这些概念。Knotra 不实现 Cordis API，不承诺 Cordis 兼容，也没有旧模型迁移层；它使用独立的 `io.knotra` 契约和语义。旧 Cordis 设计只作为思想来源保留。

Knotra 的核心取舍是把结构变化做成显式事务，把用户启动代码放到协调锁外执行，再通过代际和绑定身份做乐观提交。这样可以避免用户 `start()` 阻塞全局协调器，但实现必须接受 stale activation 回滚、有界 reconcile 和较严格的前置声明约束。这个复杂度用于换取三个不变量：STARTING 期间发布的能力不可见；ACTIVE 的组件状态、固定 BindingSet 和全部注册在一个 generation 原子出现；旧 Activation 未 settle 前同一 Handle 不会创建下一份运行。

当前边界内不做以下事情：

- 不提供通用 DI 容器、AOP、代理拦截或 Spring 兼容层。
- 不自研插件仓库、插件市场、版本解析 UI 或远程安装协议。
- 不做分布式协调、跨进程一致性或多 JVM 热替换。
- 不承诺回收被宿主或业务代码继续引用的插件对象和 ClassLoader。
- Loader 不监听文件系统；期望状态由调用方显式提交。
- 配置没有全局文件格式；每个 factory 通过 `ConfigSchema` 归一化自己的配置。
- 不提供 Cordis 兼容 API 或旧版本共存迁移。

建议分支：`refactor/knotra-runtime`。

## 整体设计

Knotra 的 Maven reactor 要求 Java 21 和 Maven 3.9 以上，模块如下：

| 模块 | 职责 | 关键依赖边界 |
|---|---|---|
| `knotra-core` | Context tree、Capability、ComponentHandle、Activation、BindingSet、LifecycleScope、结构事务、Snapshot 与诊断 | 无运行时依赖；Enforcer 禁止 PF4J、ASM、Events、Loader 和 adapter 进入 Core |
| `knotra-events` | 类型化 EventBus，作为普通 Capability 发布 | 只依赖 Core；禁止 PF4J 与 Loader |
| `knotra-pf4j-spi` | 插件实现的共享 SPI 与显式配置 token | Core；PF4J `3.13.0` 为 `provided` |
| `knotra-pf4j` | PF4J artifact 加载、typed factory mount、drain、ownership、ClassLoader guard | Core、SPI、PF4J `3.13.0`、ASM `9.7` |
| `knotra-loader` | 声明树 reconciliation、稳定路径、配置更新、工厂替换、rollback | 只依赖 Core；禁止 PF4J 和 adapter |
| `knotra-integration-tests` | 真实 fixture JAR 与跨模块生命周期验收 | 仅 test scope，不 install、不 deploy |

`knotra-core` 的主要入口是 `KnotraRuntime.create(KnotraConfig)`。宿主通过 `runtime.mutate(...)` 做结构写操作，通过 `runtime.context()`、`runtime.snapshot()` 和各 Handle 做只读观察与生命周期请求。Core 内部由 `DefaultKnotraRuntime` 协调一个不可变 `RuntimeView`，核心实现位于 `knotra-core/src/main/java/io/knotra/internal/DefaultKnotraRuntime.java`。

整体链路是：

```text
宿主 structural mutation
    -> 单次 RuntimeView.Draft 校验与准备
    -> publishOnce() 发布下一个 generation
    -> 提交可执行 side plan
    -> Component 调度器按 Handle 串行执行生命周期
    -> start() 在协调锁外运行
    -> optimistic validate
    -> ACTIVE 原子提交 / stale rollback / FAILED / DISPOSED
```

这个设计的职责划分是：Core 知道组合、依赖和生命周期，不知道 artifact；PF4J adapter 知道 artifact、ClassLoader 和 factory 边界，不直接决定组合语义；Loader 只比较期望树和当前树，通过 opaque controlled mount 使用 Core，不接触 Runtime 的完整写接口；EventBus 是普通 Capability，不进入内核。

## Core Runtime

### Context Tree 与 Capability

`ContextHandle` 表示 Context 树中的一个节点，`ContextInfo.canonicalPath()` 提供稳定路径。Root context 初始为 `ACTIVE`，宿主可以在事务里创建 child context。Context 是 Capability 的可见性边界，不是线程或 ClassLoader 边界。

Capability 使用 `CapabilityKey<T>(String name, Class<T> type)` 表示。名字和 Java `Class` 都是 key 的组成部分，且 `Class` 按 JVM 对象身份比较。Runtime 在全局类型表中按 capability name 固定 exact `Class`：同一个 runtime 生命周期内，一个名字一旦绑定到某个 `Class`，后续 requirement、host provide 或 activation staged provide 再使用同名不同 `Class` 会得到 `CAPABILITY_TYPE_CONFLICT`，即使两个类的全限定名或序列化形态相同。这个规则跨 Context 生效，用于防止不同插件把同名契约解释成不同二进制类型。

Capability 查找沿 Context 父链进行：

```text
child context
    -> 自身 registration
    -> parent
    -> ancestor
    -> 缺失
```

同一 Context 的同名同类型 capability slot 只有一个当前 registration；child registration 会 shadow parent。child 注册撤销后，解析会重新暴露 parent registration，并使相关 BindingSet 变化。这个变化不是值比较，而是 registration identity 比较。

Registration 由 `registrationId` 唯一标识。宿主通过 `RuntimeMutation.provide(...)` 发布，Activation 通过 `ActivationContext.provide(...)` staged 发布。新旧 provider 的 value 即使 `equals()` 相同，甚至都是同一个 Java 对象，只要 registration id 不同，绑定身份就不同，依赖该 slot 的 Activation 必须重新生成。因此“用等值对象替换 provider”不会被误判为没有变化。

只读读取有两个不同边界：

- `RuntimeContext.require/find` 是宿主读取接口，可以查询当前 Context 父链上任意 visible capability；它没有 Component descriptor，不会建立 Binding，也不会影响生命周期。
- `ActivationContext.require/find` 只能访问 descriptor 声明过的 key；未声明 key 是组件契约错误，不会隐式建立依赖。

两个接口都按 exact key 命中；同名不同 `Class` 会在结构校验阶段被拒绝，不会退化为按类名匹配。
Runtime 快照中的 capability 只有 name 和 type name 字符串，不保存 `Class` 对象本身，避免插件卸载后被 snapshot 钉住。

### Component、Handle 与 Activation

Component 是可声明依赖和启动逻辑的功能单元：

```java
public interface Component<C> {
    ComponentDescriptor descriptor();
    void start(ActivationContext context, C config) throws Exception;
}
```

`ComponentDescriptor` 预先声明 `CapabilityRequirement`。`REQUIRED` 和 `OPTIONAL` 的区别只影响启动条件，不影响绑定追踪：两者都进入固定 BindingSet；OPTIONAL 当前存在时记录 registration id，当前缺失时记录显式 missing binding。OPTIONAL provider 出现、消失或替换同样触发新的 Activation。这样可以避免组件在 Runtime 无法观测的 optional provider 上留下隐式生命周期依赖。

身份分成四层：

- `factoryId`：实现来源标识。
- `(contextId, mountId)`：逻辑挂载键；同一 Context 内 mountId 唯一。
- `handleId`：稳定 ComponentHandle 标识，配置重启和绑定重绑不改变。
- `activationId`：一次实际运行标识，每次 start/reconfigure/rebind 都是新 Activation。

`ComponentHandle<C>` 暴露 `state()`、`goal()`、`configRevision()`、`whenSettled()`、`reconfigure()`、`retry()` 和 `dispose()`。Handle 是稳定挂载，不是当前组件实例；当前执行保存在 Activation 和 LifecycleScope 中，settle 后会释放可执行引用。

组件状态是：

```text
WAITING -> STARTING -> ACTIVE -> STOPPING -> DISPOSED
                         \-> FAILED
```

`ComponentGoal` 只有 `RUNNING` 和 `DISPOSED`。`WAITING` 表示 goal 仍是 RUNNING 但 required binding 缺失；`FAILED` 可能来自用户 start 失败后的残留清理失败，也可能是启动失败已完全 rollback；`DISPOSED` 表示逻辑挂载已移除。FAILED 不会自动猜测应否重启，调用方需要显式 `retry()`，或者等待新的配置/绑定 fingerprint 改变后由 reconcile 继续处理。

### 宿主结构事务

宿主不能拿到长期可写的 Runtime mutation 对象。每次 `runtime.mutate(action)` 创建一次性 `RuntimeMutation`，可用操作包括：

```java
RegistrationHandle provide(ContextHandle, CapabilityKey<T>, T);
void revoke(RegistrationHandle);
ContextHandle childContext(ContextHandle parent, String name);

ComponentHandle<C> mount(ContextHandle, String mountId,
                         ComponentFactory<C>, C config);
ComponentHandle<C> mount(..., MountOptions options);
ComponentHandle<C> reconfigure(ComponentHandle<C>, C config);
void dispose(ComponentHandle<?> handle);
void dispose(ContextHandle context);
```

事务流程是：先在协调锁外执行宿主 callback，callback 中的操作被记录为 intents 并能拿到 provisional handle；随后进入 coordinator，把全部 intents 应用到一个 `RuntimeView.Draft`。任何 intent 违反 context 状态、mountId、canonical path、capability exact type 或其他结构约束时，整个 mutation 返回 `MutationResult.rejected(diagnostics)`，不会发布旧 draft。全部 intents 通过后才调用一次 `draft.publishOnce()`，将不可变 `RuntimeView` 的 generation 加一并替换 volatile view。

这意味着：

- 一个 mutation 里的多个 provide、context 和 mount 要么全部进入同一 generation，要么全部不可见。
- callback 可以使用同事务返回的 provisional registration/context handle 再撤销或继续构建结构。
- callback 抛出运行异常时，mutation 拒绝，不会出现前几个 intent 已发布的结果。
- 没有 view 变化的事务成功返回当前 generation，不制造空代际。
- mutation committed 返回时，新 `RuntimeView` 已经发布并提交 side plan；`settlement()` 只等待本次 mutation 直接触发的 component/context transitions settle，不表示 runtime 全局 quiescence，也不覆盖之后独立 transition 或普通异步业务资源。特定 Handle 的终态应等待 `whenSettled()`。
- Runtime 进入 closing 后新的结构 mutation 被拒绝。

`publishOnce()` 后才提交 executable side plan，例如调度 dirty component、登记 context disposal future、执行自动重启 reservation。这个顺序保证可执行代码看到的都是已发布 generation，不会基于一个可能被后续 intent 拒绝的 draft 运行用户逻辑。

`ContextHandle.dispose()` 与 `runtime.closeAsync()` 是公开 lifecycle request，而不是要求调用方再包一层 host structural transaction；它们内部同样构造 draft、发布 generation 并返回 settlement。宿主仍可在显式 structural transaction 中表达结构性 dispose intent。

### Activation 事务与乐观校验

当 required capability 全部存在且 goal 为 RUNNING 时，Core 在协调锁内创建 STARTING reservation：

- 计算当前 effective bindings，生成固定 BindingSet。
- 为每个 present binding 捕获 value，后续 `start()` 中的 `require/find` 只读这次捕获。
- 创建 root LifecycleScope。
- 把 Activation 记为 `STARTING`，Handle 记为 `STARTING`。
- 一次性发布该 reservation。

STARTING reservation 对 Snapshot 可见，但它的 staged registration 不进入全局 registrations，其他 component 的 effective binding 也看不到它。因此并发读者可以观察到“某 Handle 正在启动”，但观察不到半个 provider 表面；这是有意区分状态可见与能力表面不可见。

用户 `start()` 在 coordinator 锁外执行。它可以通过 `ActivationContext` 读取声明 binding、staged provide、staged child mount、登记资源；`start()` 返回后 context 立即关闭，之后任何 `require/find/provide/mountChild/lifecycle` 都失败，防止组件在 Runtime 视角已经结束后继续登记新资源。

`start()` 结束后 Core 重新进入 coordinator 做 optimistic validate。校验顺序和语义是：

1. Handle 仍存在且 goal 仍是 RUNNING。
2. Context 仍是 ACTIVE。
3. Handle config revision 和 executable desired revision 仍等于本次 Activation 的 revision。
4. Activation 未被结构变化标记 stale。
5. 每个声明 requirement 的当前 effective binding 与捕获 BindingSet 的 present 状态和 registration id 完全一致；OPTIONAL 的出现或消失同样算变化。
6. staged capability 与全局 exact type 表和其他 STARTING provisional registration 不冲突。
7. staged capability slot 在目标 Context 中未被占用。
8. staged child 的 mountId、批内 mountId 和 capability type 不冲突。
9. 以当前 committed graph 加本次及其他 STARTING 的 tentative registration 构造依赖图，Tarjan 检查 SCC；发现环则拒绝本次提交并抑制自动重试。
10. 以上结构校验都通过后，才把用户 `start()` 抛出的异常视为业务启动失败。

如果变化只是 stale，例如 start 期间 provider/context/config/goal 改变，本次 Activation 先 rollback 自己的 LifecycleScope 和 staged child，不记录为组件业务失败；随后按最新 generation 重新 reconcile。业务 start 异常则 rollback 已登记资源与 staged registration，Handle 进入 FAILED，可通过 `retry()` 显式重试。

成功提交在同一个 `publishOnce()` 中完成：

- staged registrations 进入全局 registration 表。
- staged child Handle 进入 WAITING，父 Activation 是 owner。
- Activation 变 ACTIVE。
- Handle 变 ACTIVE。
- 因新 registration 受影响的 consumer 及其 ownership descendant 先被 logical detach。
- child Activation 在父提交后的 post-commit plan 中调度，不在父用户的 `start()` 栈内递归启动。

### 依赖变化、owned child 与 teardown

provider 撤销、替换或 Context shadow 关系变化时，Core 不是只异步通知 consumer。mutation 在 draft 中计算直接绑定旧 registration 的组件，加上这些组件的 owned child，再传递闭包得到直接和间接 dependent closure。这个 closure 的 Activation 被标记 STOPPING，其 owned registrations 在同一 draft 中撤销；随后才发布 view 并调度物理 cleanup。

物理清理顺序同样有方向：

- consumer 先于 provider settle。
- owned child 先于 owner 清理。
- Context disposal 覆盖整个 subtree，并把其中组件及 owned descendants 的 goal 改为 DISPOSED。
- Runtime close 从 root Context 递归收敛。
- provider 的资源 teardown 等待相关 dependent cleanup future，不会在 consumer 仍引用旧 provider value 时先释放底层连接。

同一个 Handle 的 transition 串行排队。旧 Activation 处于 STOPPING 或 cleanup FAILED 时，新 binding 不会立刻创建第二份运行；等旧 Activation settle 后，Handle 依最新 goal 与 binding 进入 WAITING/FAILED/DISPOSED。这解决了 provider 快速替换时的双实例问题。

Activation 内通过 `mountChild(...)` 创建的是 provisional handle。父提交失败时这些 child 不进入可执行结构，mountId 可在父 retry 后复用；父提交成功后 child 属于该父 Activation，父重激活或 dispose 会先终止这批 owned child，而不是把它们误当成可独立重启的普通 dependent。

### LifecycleScope

`LifecycleScope` 负责 Activation 内的可逆资源所有权：

```java
<T extends AutoCloseable> T manage(String description, T resource);
ManagedHandle onClose(String description, Runnable disposer);
ManagedHandle manageAsync(String description, AsyncDisposer disposer);
LifecycleScope child(String description);
LifecycleScope parallelChild(String description);
```

`manage(AutoCloseable)` 返回原资源，便于赋值和链式创建；`onClose` 适合 lambda 或没有 AutoCloseable 形态的对象；`manageAsync` 接受返回 `CompletionStage<Void>` 的 disposer。scope 可以嵌套。默认 scope 是确定性 LIFO：同一 boundary 的直接 node 按登记倒序清理，child scope 作为 parent 的一个 node，在被到达时递归清理。只有 `parallelChild` 显式声明并行时，该 boundary 的直接 nodes 才并行清理；并行边界内部每个 child scope 的语义仍由该 child 自己声明。

cleanup 是异步的：

- 同步 disposer 抛出的错误记录到对应 entry。
- 异步 disposer 返回的 stage 完成后才 settle entry，null stage 视为成功。
- 一个 entry 失败不会短路 sibling，后续 entry 仍尝试清理。
- entry 有 `PENDING/SUCCEEDED/FAILED`、attempts 和 bounded last error。
- 已 SUCCEEDED 的 entry 在 retry 中跳过，只有 FAILED entry 会重新调用 disposer。
- scope 汇总为 `OPEN/STOPPING/FAILED/SUCCEEDED`。

Activation cleanup 全部成功后，如果 goal 是 DISPOSED，则移除 Handle；如果 goal 是 RUNNING，则按最新结构进入 WAITING 并可能自动 reconcile。cleanup 有失败时 Handle 停在 FAILED，保留失败 Activation 和失败 entry，`ComponentHandle.retry()` 重新排队，只重试未成功清理项；清理成功后才会按当前 goal 和配置启动新 Activation。因此 cleanup retry 不会重启业务实例，也不会重复关闭已成功资源。

### SCC 与有界 reconcile

Core 使用当前 STARTING/ACTIVE Activation 的 BindingSet 构造“consumer -> provider Handle”依赖图，并把本次和并发 STARTING 的 staged registrations 纳入 tentative graph。提交前用 Tarjan 算法检测节点数大于一的 SCC 或自环，防止 child capability shadow 和多组件 staged publish 形成反馈环。

如果 provider/config/context 在启动期间连续变化，Core 用 `reconcileFingerprint` 判断结构 fingerprint 是否变化：fingerprint 变化会重置尝试次数；同一 fingerprint 的自动 reconcile 受 `KnotraConfig.maxReconcileIterations` 限制，默认 256。达到上限后产生 `NON_CONVERGENT_RECONCILE`，组件不再自动重启，直到显式 retry 或结构 fingerprint 改变。这个策略避免了“每次启动又改变下一个启动条件”的无限循环。

### Snapshot 与诊断

`runtime.snapshot()` 返回不可变 `RuntimeSnapshot`，包含：

- generation。
- Context id、parent、name、state、canonical path。
- Component handle 的 mount、origin、owner activation、parent handle、state、goal、config revision、descriptor requirements。
- Activation 的 id、state、config revision、BindingSet 与 lifecycle scope id。
- Registration 的 id、capability name/type name、context id、owner kind/id。
- Lifecycle scope 树、managed entry 的 cleanup state、attempts 和 bounded error text。
- 稳定 enum 诊断码。

诊断码在 `DiagnosticCode` 中固定，包括 missing capability、slot occupied、type conflict、binding cycle、activation failed、rollback failed、cleanup failed、non-convergent reconcile、invalid lifecycle operation、invalid mount id 和 invalid config。其中 `INVALID_CONFIG` 目前是保留码；当前 schema 抛错、返回 null 或返回错误类型通常在 mutation 边界表现为 `INVALID_LIFECYCLE_OPERATION`。Schema 的契约是返回非空且类型正确的配置对象。Snapshot 与诊断不包含 live component、factory、disposer、Throwable 对象、`Class` 或 ClassLoader；错误只保留有界稳定文本。持有 Snapshot 不会阻止插件 ClassLoader 回收。

## Events

`knotra-events` 不把事件调度写成内核能力。`EventBusFactory` mount 一个 `EventBusComponent`，该组件创建 `DefaultEventBus`，把 bus 自己交给 `LifecycleScope.manageAsync(..., bus::closeAsync)` 托管，并发布 `CapabilityKey<EventBus>("knotra.event-bus", EventBus.class)`。消费者像依赖其他 capability 一样 require EventBus，订阅也必须由消费者 lifecycle 托管。

事件定义是 `EventDefinition<T>(EventKey<T>, EventMode)`，支持五种模式：

- `SYNC`：调用线程顺序执行，每个 listener 失败进入结果。
- `PARALLEL`：所有 listener 并行执行，汇总成功数和失败。
- `SERIAL`：按注册顺序串行，listener 返回 boolean 决定是否继续。
- `BAIL`：串行，listener 返回 true 表示认领并停止后续。
- `WATERFALL`：串行转换事件值，失败停止后续。

Event identity 使用事件名加 exact JVM `Class`。`EventKey.name()` 是 `Class.getName()`，但 bus 内部为每个事件名维护 canonical `EventBinding`，保存首次绑定时的 `Class` 对象，并用引用计数同时跟踪活跃 subscription 和 accepted dispatch。同名不同 exact `Class` 的定义会被拒绝，即使二进制类名相同、来自另一个 ClassLoader。

subscription 取消或 bus close 会先封闭入口：订阅从 registry 移除，close 还会把订阅 registry 与 binding registry 清空。但已经 accepted 的 dispatch 对象持有原 `EventBinding`；它的计数在回调完成后释放。只有活跃 subscription 和 accepted dispatch 都归零，binding 才算 idle 并可移除。因此同一个长期 bus 不会因为按类名缓存而永久钉住已卸载插件，也不会允许同名不同类事件并发混用；binding idle 后，重新加载的插件可以重新绑定同一事件名。

quiescence 是 close 契约的一部分：

- `EventSubscription.unsubscribe()` 只移除未来 dispatch，不阻塞等待。
- `closeAsync()` 拒绝未来 dispatch，并等待该订阅已经 accepted 的 dispatch 完成；重复调用返回同一个 stage。
- `EventBus.whenIdle()` 等待当前 accepted dispatch。
- `EventBus.closeAsync()` 拒绝新订阅和 dispatch，等待全部 accepted dispatch，然后停止自己拥有的 executor；重复调用返回同一 stage。
- 回调可以取消自身订阅或触发 bus close，但不得在同一线程阻塞等待自己的 close。

异步 worker 的默认 TCCL 是创建 bus 时的宿主 TCCL。每个 listener callback 执行期间临时设置为注册 listener 的 ClassLoader，结束或抛错后恢复原 TCCL。这个规则让插件 listener 能加载自己的资源，同时避免 worker 线程长期持有插件 loader。`EventDispatch` 和 `EventBusSnapshot` 只含稳定 DTO 与文本，不保存 listener 或 Throwable 对象。

## PF4J Artifact 边界

`knotra-pf4j` 把 PF4J 限定为 artifact 和 ClassLoader 边界。`Pf4jArtifactAdapter.create(Path pluginsRoot, KnotraRuntime runtime, Set<String> sharedContractPackages)` 暴露的接口不返回 PluginManager，也不允许插件直接进入 Core。

插件通过 `RuntimeComponentProvider extends ExtensionPoint` 导出：

```java
Collection<ExportedComponentFactory<?>> factories();
```

`ExportedComponentFactory<C>` 显式携带 `Class<C> configType` 和 `ComponentFactory<C>`。这个 config token 在 artifact discovery 时校验，不是普通元数据。

artifact 加载是事务式的：

1. 扫描 repository 与目标 JAR，解析依赖闭包。
2. 只加载缺失依赖，复用已管理依赖。
3. 按依赖顺序调用 PF4J start；任一状态不是 `STARTED` 即失败。
4. 通过 journal 记录本次加载与启动的前置依赖。
5. 发布 managed artifact 与 factory catalog；目标 artifact 必须导出 provider，依赖 artifact 可以作为依赖被管理。
6. 任一步失败时按 journal 回滚已加载/已启动内容，保留结构化 load failure 诊断。

tokenless API 是只读的。`factoryCatalog()`、`resolver().resolve(factoryId)` 和 `handles()` 返回 `ArtifactFactoryCatalogEntry`，只包含 artifact id/version/path、factoryId 和 config type name，不能 mount、normalize config，也不能 cast 回 executable handle。可挂载视图必须通过：

```java
Optional<ArtifactFactoryHandle<C>> resolve(String factoryId, Class<C> configType);
```

token 使用 exact `Class.equals`。factory 不存在时 typed resolve 返回 `Optional.empty()`；token 不匹配时立即抛 `IllegalArgumentException`，Loader 会把它记录为 `RESOLUTION_FAILED`，不会伪装成空结果。正确 typed handle 的 `mount(...)` 要求非空且是声明的 config type；输入可以仍是待归一化的 raw typed config，实际归一化由 Core 调 factory schema 完成。无配置 factory 必须传 `NoConfig.INSTANCE`。因此 raw cast 或绕过泛型不能把错误配置推迟到组件 `start()` 才失败。

每个通过 typed handle mount 的 Handle 都归 artifact adapter 所有，即使 mount 提交瞬间 artifact 已进入 drain，也会进入 ownership 集合。unload/retryDrain/close 的 drain 过程是：

```text
ACTIVE
  -> 关闭新 mount、移除 catalog、等待 in-flight mount
  -> DRAINING
  -> 逻辑 dispose 全部 owned handle 与 dependent artifact closure
  -> PF4J stop
  -> PF4J unload
  -> UNLOADED 并释放 wrapper/ClassLoader 引用
```

任一组件 teardown、PF4J stop 或 unload 失败时，相关 artifacts 进入 `DRAIN_FAILED`，保留 diagnostics 和 ownership，不伪造 unload 成功；`retryDrain(artifactId)` 在外部问题修复后继续完成 drain。并发 unload 同一 closure 会加入同一个 drain future；adapter close 会对全部 managed artifacts drain，失败时保留 coordinator 和诊断，下一次 close 可重试。

ClassLoader policy 强制跨边界类型来自宿主 shared parent。默认 shared package 是 `io.knotra`、`io.knotra.pf4j.spi` 和 `org.pf4j`，构造 adapter 时额外声明的 package 也加入该集合。插件私有 config token、capability contract、动态 provide contract 和 recursive GuardedContext 中的私有接口都会在 discovery 或 activation 前被拒绝，不会先进入 Core 的全局 exact type 表。成功 unload 和 load failure rollback 都使用 weak reference 测试验证 plugin ClassLoader 可回收；实际进程中的回收仍依赖外部代码没有保留插件对象、Class 或 loader。

Artifact snapshot 和 ownership 也是稳定文本与 id：origin、handle、state、dependency、factory 与 loader description，不暴露 PluginManager、PluginWrapper、factory 或 ClassLoader 对象。

## Loader

`KnotraLoader` 管理期望声明树，而不是监听文件或直接操作 PF4J。`KnotraLoader.owned(runtime, resolver)` 创建自己的 base context；`over(runtime, context, resolver)` 使用调用方指定 context。Loader 内部是单线程异步 coordinator，`reconcileAsync(...)` 与 `retryAsync(...)` 串行执行，`closeAsync(...)` 也经过同一队列，且禁止 coordinator 线程重入调用这些 API。

期望树由 `ComponentEntry(path, FactoryRef, config, children)` 组成。路径被规范化为稳定 `/` 分隔树，不允许 `..`，不允许重复 normalized path，child 必须属于声明的 parent。每个 path 对应一个 loader 管理的 child Context，并把 path 作为 `mountId`，因此 Core 的逻辑键是 `(contextId, path)`。嵌套声明的 Context 路径与树路径一致。

Resolver 返回 opaque 的 `ResolvedComponentDefinition`：

- `FactoryIdentity`：必填 factory id、必填 implementation fingerprint；version 可选，空字符串表示未声明。identity 相等要求三者都相等。
- `ConfigSchema<Object>`：把原始配置归一化为实现可接受的类型。
- `ControlledMountStrategy`：在 loader 分配的单一 slot 中执行一次 mount。
- `ReconfigureStrategy`：默认直接调用 Core handle 的 reconfigure。

Loader 先完整 prepare 期望树：解析所有 factory、normalize 所有 config、检查 parent 完整性、base context 状态、canonical context 归属和 mount slot 冲突。prepare 或配置校验失败时不会修改当前树。

reconcile 按阶段执行：

1. 清理已由外部 dispose 的托管 handle/context。
2. 对仍被期望但 Context 已 FAILED 的路径先重试 disposal。
3. 移除不再期望的子树。
4. `FactoryIdentity` 变化时先 dispose 旧实现，并等待旧 Handle settlement 到 `DISPOSED` 后才在同一 slot mount 新实现；新实现被拒绝时尝试恢复旧实现，补偿失败则保留 `COMPENSATION_FAILED` 诊断。
5. 添加缺失项；缺失 Context 在一个 Core structural transaction 中创建，任一 mount 失败时按倒序 dispose 已 mount 项并回滚本轮创建的 context。
6. 相同 factory identity 的配置变化通过 reconfigure 更新，FAILED 项不自动 retry，只记录最新配置并要求显式 `retry(path)`。

Controlled mount 边界由 `ControlledMountContext` 定义：

```java
ContextHandle context();
String mountId();
<C> CompletionStage<ComponentHandle<C>> mount(
        ComponentFactory<C> factory, C config, MountOptions options);
```

实现内部是 single-use slot：第二次 mount、非 ACTIVE context 或 slot 不匹配都会失败。strategy 只拿到这个对象和已归一化配置，拿不到 `KnotraRuntime`、`RuntimeMutation`、任意 context disposal 或 host provide 能力。Loader 会校验 strategy 返回的 handle 确实位于分配的 `(context, mountId)`，否则立即 dispose 并拒绝。

PF4J 桥接由宿主提供 resolver：typed resolve artifact factory，包装其 config schema 与 controlled mount strategy。桥接定义的 schema 先把 desired tree 中的 raw config 归一化为正确类型，再传给 single-use controlled mount；直接调用 typed artifact handle 时则传非空 typed raw config，由 Core 的 factory schema 归一化。这样 Loader 自身无 PF4J 依赖，adapter 不向 Loader 暴露 raw factory 或 PluginManager。当 reconcile 与 artifact drain 并发时，被 drain 的 mount 失败并回滚，Loader 不留下 partial entry；下一次 reconcile 可选择其他 resolver 提供的本地或新 artifact 实现。

`LoaderSnapshot` 只包含 path、context/handle id、factory identity、config revision、state 与 goal。Loader close 在普通场景 dispose 它管理的顶层 context 或 owned base context；如果 Runtime close 已接管 base subtree，Loader 只识别该所有权、清理自身托管簿记并停止 coordinator，不重复发起 teardown，也不代替调用方等待 Runtime close future。需要确认 Core 收敛时，调用方继续等待 `runtime.closeAsync()`。

## 并发与失败恢复

Core 的并发协议可以概括为三层：

- `RuntimeView` 是不可变 generation；读操作拿到一致视图。
- coordinator 只做短临界区结构 mutation、activation validate/commit 和状态发布；用户 start、资源 disposer、Event dispatch 和 artifact load/drain 不占用这个锁。
- 每个 ComponentHandle 有串行 transition 队列；并发 reconfigure、provider replacement、dispose、retry 和 close 合并为同一 Handle 的顺序生命周期。

主要失败行为如下：

| 场景 | 当前行为 | 恢复方式 |
|---|---|---|
| 结构事务任一 intent 无效 | 整个 mutation 拒绝，不发布 | 修正输入后重新提交 |
| required capability 缺失 | Handle 停 WAITING，不执行 start | provider 出现后自动 reconcile |
| start 抛错 | rollback resources/staged registrations，Handle FAILED | `retry()` 或修正后 reconfigure |
| start 期间 binding/config/context/goal 变化 | stale activation rollback，不记业务失败 | 按最新 generation reconcile |
| binding cycle / non-convergence | 拒绝提交；同一 fingerprint 超限后停止自动重启 | 改结构后 fingerprint 重置，或显式 retry |
| cleanup 部分失败 | 继续尝试其他 entry，Handle FAILED，失败 entry 保留 | `retry()` 只重试失败 entry |
| provider 撤销/替换 | 先 logical detach direct/indirect dependents 和 owned children，再物理清理 provider | 无需人工恢复；等待 settle |
| Context cleanup 失败 | subtree Context 进入 FAILED 并保留 | 再次 dispose 同 Context 完成剩余清理 |
| EventBus close | 拒绝新工作，等待 accepted dispatch | 重复 close 返回同一 future |
| PF4J component/stop/unload 失败 | artifact 停 `DRAIN_FAILED`，保留 ownership 和诊断 | `retryDrain(...)` |
| Loader 批量添加/替换失败 | 回滚已添加项或补偿恢复旧实现 | 修正诊断后下一次 reconcile/retry |
| Runtime close cleanup 失败 | close future 异常完成，coordinator/诊断保留 | 再次 `closeAsync()` 继续残余清理 |

异常聚合不吞掉原始业务错误的优先级：结构 stale 优先于用户 start error；apply 与 cleanup 同时失败时，原 apply 错误和 cleanup 错误分别进入诊断，不用 cleanup 异常覆盖启动原因。错误文本有长度上限，避免恶意异常 message 造成诊断膨胀。

## 发布与运行

当前版本是 `0.1.0-SNAPSHOT`，属于常规 JVM library 版本发布，无持久数据格式、索引迁移或历史状态回填。完整发布流程是在仓库根执行 `mvn clean verify`，reactor 按依赖顺序构建，`knotra-integration-tests` 不 install、不 deploy。若发布构件出现问题，回退到上一个常规版本并重启使用方 JVM 即可；Core 与 Loader 自身没有跨进程持久状态需要补偿。

运行期恢复原则是先保留状态与诊断，再提供显式重试。组件 cleanup、Context dispose、Runtime close、artifact drain 和 Loader close 都是幂等推进的：已成功项跳过，失败项保留。若失败原因来自 native 资源、外部进程或 JVM 内无法释放的引用，Knotra 不假装清理成功；操作者修复外部问题后重试。若插件 ClassLoader 或 native 资源已经泄漏且无法释放，最终恢复边界是重启 JVM。

## 当前限制

- Loader 没有 filesystem watcher、配置中心轮询或 HMR 协议；调用方必须在期望状态变化时调用 reconcile。
- Runtime 不管理业务对象逃逸后的引用。组件把 capability value 或自身对象交给未托管代码后，清理与 ClassLoader 回收只能依赖该代码自行释放。
- ClassLoader 回收测试使用 weak reference 和显式 GC 验证 Runtime 自身不保留引用；生产 GC 时机和外部引用仍由 JVM 与宿主决定。
- PF4J stop/unload 半失败只能保留 `DRAIN_FAILED` 或 residual diagnostic 并等待 retry；不能在当前 JVM 内强制认为插件已安全卸载，极端情况下需要重启。
- Core 的 SCC 检查覆盖当前 STARTING/ACTIVE 与 tentative graph；不做通用全局固定点求解，non-convergence 达到上限后停止自动重启。
- 事件分发保证 accepted dispatch quiescence，不保证跨进程、持久队列、Exactly-once 或宕机恢复。
- 配置语义由 factory schema 决定；Runtime 不提供统一 YAML/JSON schema、secret 管理或动态配置中心。
- `knotra-loader` 不内置 PF4J resolver；宿主需要显式提供 opaque bridge。
- Cordis 只有思想来源关系，没有任何兼容 API 或迁移工具。

## 测试

当前 checkout 的完整 reactor `mvn clean verify` 已通过，共 226 项测试，0 failures，0 errors，0 skipped：

| 模块 | 测试数 |
|---|---:|
| `knotra-core` | 95 |
| `knotra-events` | 44 |
| `knotra-pf4j` | 37 |
| `knotra-loader` | 36 |
| `knotra-integration-tests` | 14 |
| 合计 | 226 |

关键行为矩阵如下：

| 验证对象 | 输入与路径 | 预期结果 |
|---|---|---|
| Context 继承/shadow | child 覆盖 parent capability 后撤销 child | child 消费者重激活，解析回 parent registration |
| exact Class 契约 | 同名 capability 使用不同 JVM `Class` | mutation/activation 拒绝 `CAPABILITY_TYPE_CONFLICT` |
| registration identity | 用等值 value 重新 provide | 新 registration id 触发 consumer 新 Activation |
| optional binding | OPTIONAL provider 出现/消失 | BindingSet 显式变化并重激活 |
| 结构事务 | 同一 mutation 多个 provide/context/mount，后续 intent 失败 | 整个 transaction 拒绝，无 partial generation |
| STARTING 表面 | start 中 provide 并发 snapshot | STARTING reservation 可见，staged capability 不可见 |
| stale start | start 阻塞期间替换 provider/config | 旧 activation rollback 并按最新结构 reconcile，不记业务失败 |
| 原子 ACTIVE | 成功 start 与 registrations/child/state | 同一 generation 发布 |
| child mount | 父成功/失败时 staged child | 成功后 WAITING 并调度；失败后丢弃且 mountId 可复用 |
| dependent teardown | provider 撤销并替换 | direct/indirect consumer 先 STOPPING，provider 后 cleanup |
| owned child | 父 Activation 重激活/dispose | owned child 先终止，不与新 child 争同一 mountId |
| Lifecycle LIFO | nested/parallel/async entries | 默认倒序，显式 parallel 并行，异步等待 settle |
| entry retry | 一个 cleanup 失败，一个成功 | 继续清理 sibling；retry 只调用失败 entry |
| SCC | tentative binding 形成环 | activation 拒绝并给出 `BINDING_CYCLE` |
| non-convergence | 同一 fingerprint 反复触发 | 达到上限后 `NON_CONVERGENT_RECONCILE`，不自动重启 |
| Snapshot/GC | 保留 Core/Event/PF4J/Loader snapshot 后 unload | plugin ClassLoader weak reference 可回收 |
| Events identity | 不同 ClassLoader 中同名事件类并发注册 | exact Class 不同即拒绝；引用释放后可 rebind |
| Events quiescence | accepted dispatch 期间 subscription/bus close | 等待已接受回调，拒绝新工作，不重复投递 |
| Events TCCL | 插件 listener 回调 | callback 使用 listener loader，结束恢复，worker 不长期持有 |
| PF4J typed token | 错误 config type、raw cast、null config | resolve 或 factory creation 前结构化失败 |
| PF4J catalog | tokenless resolver/catalog | 只有稳定文本 metadata，无法 mount |
| PF4J load rollback | 依赖闭包中途失败 | journal 回滚已加载/已启动内容 |
| PF4J drain | in-flight mount、component cleanup、dependent artifact | 等待并 leaf-first 清理；失败进入 `DRAIN_FAILED` 可 retry |
| PF4J contract | plugin-private config/capability/dynamic provide | shared identity guard 拒绝，不污染 Core type map |
| Loader rollback | 批量添加中某项失败 | 倒序 dispose 已添加项并回滚本轮 context |
| Loader replacement | factory identity 变化且新实现失败 | 尝试恢复旧实现；补偿失败保留诊断 |
| Loader/PF4J race | reconcile mount 与 artifact drain 并发 | 不留下 partial mount，下次 reconcile 可用本地实现恢复 |
| 并发 close | Runtime、Loader、adapter、EventBus close 交叉 | 已接受的 Event/生命周期工作 settle，失败可重试，不互相伪成功 |
| 集成链路 | 真实 fixture JAR + Core + Events + Loader bridge | nested tree、schema、ownership、snapshot 和 GC 全部走公开 API |

集成模块会构建真实 PF4J fixture JAR，只通过公开 API 断言行为；测试不依赖生产内部强转、`Thread.sleep()` 或专用后门。并发测试主要使用 latch、future 与 Awaitility 的有界等待。
