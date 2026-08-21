# Knotra FAQ 与排障指南

本文按"症状 → 原因 → 处理"组织常见问题，适用于 `0.1.0-SNAPSHOT`。所有行为描述以当前源码为准；API 用法见[API 与集成指南](<Knotra API 与集成指南.md>)，语义细节见[运行时设计文档](<Knotra 运行时设计文档.md>)。

## 排查总纲

Knotra 的失败处理原则是**先保留状态与诊断，再显式重试**，不会伪造成功。排查永远从 Snapshot 开始：

```java
RuntimeSnapshot snapshot = runtime.snapshot();   // 组件、绑定、注册、清理状态、诊断
List<ArtifactSnapshot> artifacts = adapter.artifacts(); // 全部受管 artifact 状态、ownership、诊断
LoaderSnapshot loaderState = loader.snapshot();  // 收敛条目、路径、诊断
```

看三类信息：

1. **状态**：`ComponentState`、`ArtifactState` 等枚举，判断卡在哪个阶段（速查表见下文）。
2. **诊断码**：稳定枚举，适合程序化判断；消息文本可能调整，不要按消息字符串编程。
3. **绑定集**：组件的 `BindingSet` 显示它绑定的是哪一次注册，用于回答"它为什么重启 / 为什么不启动"。

恢复操作都是幂等推进的：已成功项跳过，失败项保留。`retry()`、`retryDrain(...)`、重复 `closeAsync()`、重复 `dispose()` 都是安全操作。

## 状态速查

| ComponentState | 含义 | 卡在这个状态通常说明 |
|---|---|---|
| `WAITING` | 已挂载未启动 | REQUIRED 依赖缺失，或存在 binding cycle |
| `STARTING` | 启动事务进行中 | 用户 `start()` 未返回；长期停留先检查是否有阻塞调用 |
| `ACTIVE` | 当前 Activation 活跃 | 正常 |
| `STOPPING` | 正在停止，等待清理收敛 | 清理慢或清理卡住，看 LifecycleScope 条目状态 |
| `FAILED` | 启动或清理失败 | 读诊断码；`retry()` 可重试 |
| `DISPOSED` | 已释放并移除 | 终态 |

| ArtifactState | 含义 | 卡在这个状态通常说明 |
|---|---|---|
| `ACTIVE` | 接受类型化受控挂载 | 正常 |
| `DRAINING` | 停止新挂载，等待在途挂载并 dispose | 在途挂载未收敛；看 owned handle 状态 |
| `DRAIN_FAILED` | drain 或 PF4J 卸载失败 | 读诊断；修复后 `retryDrain(artifactId)` |
| `FAILED` | 加载或启动失败且已回滚 | 读诊断，修正后重新 load |
| `UNLOADED` | 插件已停止卸载，ClassLoader 可回收 | 终态 |

单个受管资源的 `CleanupState`：`PENDING`（未清理或清理中）、`SUCCEEDED`（已释放）、`FAILED`（清理失败、错误保留、可重试）。

## 启动与依赖

### 挂载后组件一直 WAITING，怎么办？

**原因**：通常是 `MISSING_CAPABILITY`（REQUIRED 依赖缺失），少数是 `BINDING_CYCLE`（依赖图有环，自动重启被抑制）。

**处理**：

1. 看 snapshot 中该 handle 的 bindings 与 diagnostics；
2. 缺依赖：在可见的 Context 上 `provide(...)` 对应 capability，组件会在 provider 出现后自动启动；
3. 有环：调整组件的依赖声明解除环；同一 fingerprint 反复超限会停止自动重启，改结构后 fingerprint 重置，或显式 `retry()`。

### 替换了一个 `equals()` 相等的提供方，组件为什么还是重启了？

这是设计行为，不是 bug。绑定身份按**注册身份**（registration id）比较，不按对象相等比较：即使新旧值相等、甚至同一个对象，只要是一次新注册，绑定代际就变了。这样可以防止"等值替换"掩盖真实的提供方切换。

### OPTIONAL 依赖只是"可选"，为什么它变化也触发重启？

`OPTIONAL` 只影响启动条件（缺失也能启动），不影响绑定追踪。OPTIONAL 与 REQUIRED 进入同一个 `BindingSet`：可选提供方出现、消失或替换，同样产生新的 Activation。否则运行时无法观测组件对可选提供方的隐式依赖。

### `require(...)` 报 capability 未声明？

`ActivationContext.require/find` 只能访问 descriptor 声明过的 key，未声明是组件契约错误。把对应 `CapabilityRequirement` 加进 `ComponentDescriptor`；宿主侧的 `RuntimeContext` 没有这个限制，但它不建立绑定、不触发依赖追踪，不要混用两者。

### `start()` 执行到一半依赖被替换，组件会带着过期状态运行吗？

不会。这类启动会被判定为 stale activation：暂存注册回滚，按最新代际重新启动，且**不计为业务失败**（不会进 FAILED 诊断）。如果反复发生，说明结构变化过于频繁或存在环，参考上文 `BINDING_CYCLE`。

## Capability 与类型

### 遇到 `CAPABILITY_TYPE_CONFLICT`？

同一个 capability name 在一个 Runtime 生命周期内固定绑定一个精确的 JVM `Class`。之后任何同名不同 `Class` 的使用（requirement、host provide、staged provide）都会被拒绝——即使两个类全限定名相同（典型场景：不同插件 ClassLoader 各自加载了同名合约类）。

**处理**：让合约类型只来自共享合约包（`io.knotra`、`io.knotra.pf4j.spi`、`org.pf4j`，以及构造 adapter 时显式声明的包），插件私有类型不要用作 Capability 合约。冲突发生后只能换 name 或统一类型来源。

### 遇到 `CAPABILITY_SLOT_OCCUPIED`？

同一 Context 的同名同类型 slot 只有一个当前注册。第二次 `provide` 会拒绝。

**处理**：先 `revoke()` 旧注册；或者，如果意图是"局部覆盖"，把新提供方发布到子 Context，用遮蔽（shadowing）语义而不是强占父 slot。

## 生命周期与清理

### 组件进入 FAILED，怎么恢复？

按诊断码分三类：

- `ACTIVATION_FAILED`：`start()` 抛错，已回滚暂存注册与资源。修正原因后 `retry()` 或 reconfigure；
- `CLEANUP_FAILED`：LifecycleScope 条目清理失败，失败条目保留错误文本，`retry()` 只重试失败条目，不重放已成功项；
- `ROLLBACK_FAILED`：提交或回滚流程自身失败，读诊断中的原始错误。

### 关闭卡住（STOPPING 不收敛），看什么？

两个常见来源：

1. **异步 disposer 不完成**：`manageAsync(...)` 登记的清理 future 永不完成。检查 disposer 逻辑；
2. **事件监听等待自身**：listener 里同步等待包含自己的关闭流程。listener 应返回 stage 让 dispatch chain 异步收敛。

各层 close 是幂等的：修复后重复调用 `closeAsync()` 会继续残余清理，不会从头再来。

### 关闭顺序是什么？

从外到内：`loader.closeAsync()` → `adapter.closeAsync()` → `runtime.closeAsync()`。`try-with-resources` 中声明顺序相反即可。任何一层失败：先读 snapshot/diagnostics，修复后重试同一操作，不要假设已关闭。

## EventBus

### 两个插件里"同名同结构"的事件类型，为什么订阅不上？

事件身份是精确的 JVM `Class` 对象，不只是类型名。跨 artifact ClassLoader 时，两个全限定名相同的类仍是不同身份。合约事件类型必须放在共享合约包，由宿主加载。

### `closeAsync()` 在等什么？

等待"关闭请求被观察到之前已接受"的分发收敛（accepted dispatch quiescence）；之后的新工作被拒绝。重复 close 返回同一 future，幂等。

## PF4J Artifact

### typed resolve 返回空，和 token 错误是一回事吗？

不是：

- **factory 不存在**：`resolve(...)` 返回 `Optional.empty()`；
- **token 不匹配**：立即抛 `IllegalArgumentException`。经 Loader 桥接时记录为 `RESOLUTION_FAILED`，不会伪装成空结果。

排查用 `factoryCatalog()` 的只读元数据（含稳定的 `configTypeName()`）确认 factory id 与配置类型名。

### 遇到 `DRAIN_FAILED`？

drain 流程是：关闭新挂载 → 等待在途挂载 → 逻辑 dispose 全部 owned handle（依赖该 artifact 的下游优先）→ PF4J stop → PF4J unload → 释放 ClassLoader 引用。任一环节失败即进入 `DRAIN_FAILED`，保留 diagnostics 与 ownership，不伪造成功。

**处理**：读诊断定位失败环节（通常是某个组件 teardown 或外部资源），修复后 `retryDrain(artifactId)` 从断点继续。并发 unload 同一 closure 会加入同一个 drain future。若 native 资源或引用已无法在 JVM 内释放，最终恢复边界是重启 JVM。

### 插件显示 UNLOADED，但 ClassLoader 一直没被回收？

Runtime 侧不保留引用（两条路径都用弱引用 GC 测试断言过）。生产环境回收取决于没有**其他**对象持有插件对象、`Class` 或 ClassLoader。排查宿主代码是否把 capability value、组件对象或插件类逃逸保存到未托管结构里；组件把对象交给外部代码后，释放只能依赖该代码自身。

### 插件私有类型作为合约被拒，怎么改？

用作 capability 合约、config token 或动态 provide 合约的类型，如果来自插件私有包，会在 discovery 或 activation 前被拒绝（不会污染全局类型表）。把这些类型移到共享合约包，由宿主加载。

### mount 报 null config？

artifact handle 的类型化挂载强制非空配置。无配置工厂也必须传 `NoConfig.INSTANCE`；有配置时传正确类型的 raw 值，归一化由 Core 调 factory schema 完成。

## Loader

### 遇到 `CONTEXT_CONFLICT`？

Loader 的路径命名空间是保留的：目标路径上的 Context 或挂载 ID 被其他所有者占用时拒绝，Loader 不认领外来结构。检查是否有宿主或其他 Loader 实例占用了同一路径 / mountId。

### reconcile 失败会留下半个挂载吗？

不会。解析或配置归一化失败（`RESOLUTION_FAILED`、`CONFIG_INVALID`、`INVALID_TREE`）时整批在挂载前失败；替换失败时回滚已添加项或补偿恢复旧实现（`REPLACEMENT_BLOCKED`、`COMPENSATION_FAILED`）。存在残余风险时读诊断逐条确认，修正后下一次 reconcile 收敛。

### reconcile 与插件 drain 并发会发生什么？

被 drain 的 mount 会失败并回滚，不留 partial entry；下一次 reconcile 可以选择其他 resolver 提供的本地或新 artifact 实现。这是设计内的竞态处理，不需要调用方加锁。

### 遇到 `ACTIVATION_FAILED` 但没改过代码？

Loader 的 `ACTIVATION_FAILED` 表示组件 Activation 失败或重配置后处于 FAILED，需要显式 retry。它通常继承自 Core 侧 `ACTIVATION_FAILED` / `CLEANUP_FAILED` 的诊断，先按上文"组件进入 FAILED"的流程定位。

## 诊断码速查

### Core（`DiagnosticCode`）

| 诊断码 | 含义 | 常见处理 |
|---|---|---|
| `MISSING_CAPABILITY` | 必需 Capability 缺失，组件保持 WAITING | provide 后自动启动 |
| `CAPABILITY_SLOT_OCCUPIED` | 目标 Context 同名 slot 已占用 | 先 revoke，或用子 Context 遮蔽 |
| `CAPABILITY_TYPE_CONFLICT` | 同名 capability 与已固化类型冲突 | 统一合约类型来源 |
| `BINDING_CYCLE` | 依赖图有环，自动重启被抑制 | 改结构，或显式 retry |
| `ACTIVATION_FAILED` | 组件启动失败 | 修正后 `retry()` / reconfigure |
| `ROLLBACK_FAILED` | 激活提交或回滚流程失败 | 读诊断中的原始错误 |
| `CLEANUP_FAILED` | 受管条目清理失败 | `retry()` 只重试失败条目 |
| `NON_CONVERGENT_RECONCILE` | 自动收敛超次数未收敛 | 改结构后 fingerprint 重置 |
| `INVALID_LIFECYCLE_OPERATION` | 操作非法（目标已释放、参数无效、运行时关闭中） | 检查目标状态后重试 |
| `INVALID_MOUNT_ID` | 挂载 ID 缺失或被占用 | 换 ID 或释放旧挂载 |
| `INVALID_CONFIG` | 配置不符合 schema（当前为保留码） | 修正配置 |

### Loader（`LoaderDiagnosticCode`）

| 诊断码 | 含义 | 常见处理 |
|---|---|---|
| `RESOLUTION_FAILED` | 工厂解析失败或抛异常 | 检查 FactoryRef / resolver / token |
| `CONFIG_INVALID` | schema 归一化或校验拒绝，整批失败 | 修正配置 |
| `INVALID_TREE` | 期望树结构非法 | 修路径、父子关系、去重 |
| `BASE_UNAVAILABLE` | 基础 Context 不可用 | 确认 Context 属于运行时且 ACTIVE |
| `CONTEXT_CONFLICT` | 路径 / mountId 被其他所有者占用 | 清理占用方 |
| `STRUCTURE_REJECTED` | 运行时结构事务被拒绝 | 读 Core 侧诊断 |
| `TEARDOWN_FAILED` | 清理未收敛到 DISPOSED | 重试 reconcile / dispose |
| `REPLACEMENT_BLOCKED` | 工厂替换被拒绝，旧实现已恢复 | 读原因，保持旧版本运行 |
| `COMPENSATION_FAILED` | 回滚自身失败，可能有残余 | 读诊断确认残余，手动清理 |
| `ACTIVATION_FAILED` | 组件 FAILED，需显式 retry | 按组件 FAILED 流程处理 |
| `CLOSED` | Loader 已关闭 | 重建 Loader 或检查调用时序 |

## 什么时候需要重启 JVM

Knotra 的恢复边界很明确：只要失败原因是 JVM 内可修复的（组件 bug、外部服务暂时不可用、配置错误），显式重试都能收敛。以下情况无法在当前 JVM 内恢复，需要重启：

- 插件 ClassLoader 或 native 资源已泄漏且没有任何途径释放；
- 宿主持有了插件对象且无法编程性释放；
- PF4J stop/unload 半失败后无法通过 `retryDrain` 收敛。

在这些情况下，Knotra 会保留 `DRAIN_FAILED` 与诊断如实反映状态，而不是假装卸载成功。

## 相关文档

- [Knotra API 与集成指南](<Knotra API 与集成指南.md>)：公开 API、接入方式与关闭顺序。
- [Knotra 运行时设计文档](<Knotra 运行时设计文档.md>)：失败语义的完整定义。
- [Knotra 实战案例：动态物流路由系统](<Knotra 实战案例：动态物流路由系统.md>)：升级与排空的完整场景。
