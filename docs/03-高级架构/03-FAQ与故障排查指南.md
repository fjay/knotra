# FAQ 与故障排查指南

本指南汇集了在使用 Knotra 过程中最常见的问题、状态机流转解析以及生产故障定位步骤。

---

## 快速排障第一步：读取运行时快照与诊断

当系统行为不符合预期时，第一步应通过 `AdvancedRuntime` 获取只读纯数据快照：

```java
RuntimeSnapshot snapshot = runtime.advanced().snapshot();

// 1. 查找所有处于 FAILED 状态的挂载点
snapshot.mounts().stream()
        .filter(m -> m.state() == ComponentState.FAILED)
        .forEach(m -> System.out.printf(
                "挂载点 ID: %s, 组件 ID: %s, 当前状态: %s%n",
                m.mountId(), m.componentId(), m.state()));

// 2. 查看具体失败诊断信息
snapshot.diagnostics().stream()
        .filter(d -> d.failure() != FailureInfo.EMPTY)
        .forEach(d -> System.out.printf(
                "错误码: %s, 目标 ID: %s, 详情: %s%n",
                d.code(), d.targetId(), d.failure().summary()));
```

---

## 挂载点状态机全景

每个挂载点（`MountHandle`）在运行期间具有确定的生命周期状态：

| 状态 | 含义 | 原因与下一步排查 |
|---|---|---|
| **WAITING** | 等待依赖满足 | 缺少必需的 Capability 依赖，或者正在等待依赖服务启动完成。 |
| **STARTING** | 正在激活启动中 | 正在执行构造函数、配置校验或 initializer；带超时等待即可。 |
| **ACTIVE** | 正常就绪（活跃） | 组件已完全就绪，对外暴露的能力可正常调用。 |
| **STOPPING** | 正在停止与排空 | 正在等待在途请求处理完毕（Drain）并逆序释放受管资源。 |
| **FAILED** | 启动或清理失败 | 发生异常（如端口占用、配置错误），读取诊断信息修复后调用 `retryAsync()`。 |
| **DISPOSED** | 终态（已完全卸载） | 挂载点已被彻底释放；若需重新使用需重新执行 `mount`。 |

---

## 高频问题与概念澄清

### Q1: `awaitSettled()` 正常返回了，为什么组件不一定是 ACTIVE 状态？

**解答**：
* Publication、Registration 和结构事务返回的 **操作级收敛（Settlement）** 描述的是“本次变更引发的传播与在途排空已经收敛”。
* 如果下游某个依赖此能力的组件在启动时抛出了异常，该异常会被记录到 `SettlementReport` 中，挂载点会进入 `FAILED`，但这并不妨碍本次变更操作本身的正常收敛。
* **正确做法**：检查报告中是否存在失败节点，并断言具体挂载点：

```java
SettlementReport report = change.awaitSettled(Duration.ofSeconds(10));
if (report.hasFailedMounts()) {
    System.err.println("失败挂载点: " + report.failedMounts());
}

// 确保特定组件已活跃
handle.requireActive(Duration.ofSeconds(10));
```

---

### Q2: `hasFailedMounts()` 为 false，如何确认我的挂载点真正生效了？

**解答**：
* 如果被替换的能力仅被 `Beans.dynamic` 代理依赖，消费方无需销毁重建，此时本次变更的受影响挂载集为空（`hasAffectedMounts() == false`）。
* 空影响集下没有失败挂载，因此 `hasFailedMounts()` 也为 `false`。
* 如果需要验证具体某个挂载点是否处于就绪状态，应直接调用 `handle.requireActive(timeout)` 进行断言。

---

### Q3: 更新 Publication 提示 `DISPLACED` 或被拒绝是什么原因？

**解答**：
* `Publication<T>` 具有生命周期状态：
  * `PUBLISHED`：正常状态，可重复调用 `update(...)`。
  * `UNPUBLISHED`：已被显式撤销，终态不可逆。
  * `DISPLACED`：由于外部发起了替换、父级 Context 被释放或 Runtime 被关闭，槽位已被移除。
* 处于 `DISPLACED` 状态的槽位不再接受 `update(...)`，如需恢复需重新在对应 Context 中调用 `publish(...)`。

---

### Q4: 撤销之后还能找回旧的 `Registration` 吗？

**解答**：
不能。在 Knotra 中，每一次提交都会生成不可变的单向代际。被 replace 或 revoke 的旧 `Registration` 已经永久失效，后续操作必须基于新生成的代际进行。

---

### Q5: 动态代理调用抛出找不到提供方异常怎么办？

**解答**：
`Beans.dynamic` 代理在首次启动时会校验提供方存在，但在后续运行中，若提供方被显式撤销（unpublish）且没有新版本接入，调用方法时会因缺少提供方而失败。
* 检查提供方是否被意外撤销。
* 检查提供方与消费方是否处于同一个 Context 或可见的父子 Context 中。
* 检查能力名称与 Java 类型是否完全一致（注意 ClassLoader 是否相同）。

---

### Q6: `closeAsync()` 有界等待超时了，怎么判断卡在哪里？

**解答**：

`closeAsync()` 的语义是无界排空收敛，不会因为诊断而注入隐藏超时或跳过资源释放。超时后应分别采样四个 owner 的挂起操作快照：

```java
System.err.println(runtime.advanced().pendingOperations().render());
System.err.println(bus.pendingOperations().render());      // knotra-events
System.err.println(adapter.pendingOperations().render());  // knotra-pf4j
System.err.println(loader.pendingOperations().render());   // knotra-loader
```

四份快照相互独立、各自 point-in-time，没有全局聚合视图；某份快照为空只说明采样瞬间没有已知挂起操作，不代表对应 close 已完成，更不能据此推断整体关闭完成。完成与否只以 close future 的收敛为准，诊断只负责回答“还在等什么”。

典型三层关联（插件监听器卡住导致卸载阻塞）：

```text
adapter:   ARTIFACT_DRAIN|pricing-plugin|...|phase=dispose-roots, rootIds=[m-42], closureIds=[pricing-plugin]
runtime:   COMPONENT_TRANSITION|m-42|...|component dispose
           LIFECYCLE_CLEANUP|entry-17|...|async pricing-listener
bus:       EVENT_DISPATCH|event-dispatch-bus-3-12|...|listeners=1
           EVENT_SUBSCRIPTION_DRAIN|event-subscription-bus-3-8|...|ids=[event-dispatch-bus-3-12]
```

adapter 的 `rootIds` 与 core `COMPONENT_TRANSITION` 的 targetId 指向同一挂载句柄；`LIFECYCLE_CLEANUP` 给出该句柄正在清理的生命周期条目；被卡住的 `EVENT_DISPATCH` targetId 出现在 `EVENT_SUBSCRIPTION_DRAIN` 的 `ids=[...]` 中。沿稳定 ID 关联即可定位到具体回调，再决定释放外部依赖、修复代码还是告警。

---

## 诊断码（DiagnosticCode）速查手册

| 诊断码 | 含义 | 处置建议 |
|---|---|---|
| **MISSING_CAPABILITY** | 缺失必需的能力 | 检查提供方是否已发布，以及 Context 可见性边界。 |
| **CAPABILITY_SLOT_OCCUPIED** | 槽位冲突 | 同一 Context 下已存在同名能力，请使用 update 或显式指定不同名称。 |
| **CAPABILITY_TYPE_CONFLICT** | 类型冲突 | 同名能力绑定了不同的 Java Class 类型，修正类型定义。 |
| **BINDING_CYCLE** | 依赖循环 | 检查组件依赖图是否存在环路；将其中一侧改为 `Beans.dynamic` 即可打破硬环路。 |
| **ACTIVATION_FAILED** | 启动激活失败 | 查看 `FailureInfo` 摘要，检查构造函数、依赖项或 initializer 逻辑。 |
| **CLEANUP_FAILED** | 资源清理失败 | 检查 close 方法是否阻塞或抛错；修复后调用 `handle.retryAsync()`。 |
| **NON_CONVERGENT_RECONCILE** | 声明式收敛未收敛 | 检查期望树中是否存在互相依赖振荡的配置。 |
| **INVALID_CONFIG** | 配置无效 | 配置校验（`normalizeConfig`）返回非法值或解码失败，修正输入配置。 |

---

## 启动与清理失败的标准恢复流程

当挂载点进入 `FAILED` 状态时，不需要盲目重启整个应用，Knotra 支持针对单个挂载点的**幂等重试（Retry）**：

```java
// 1. 读取错误详情
ComponentState state = handle.state();
if (state == ComponentState.FAILED) {
    // 2. 外部排查并修复环境问题（如修复了外部配置或网络连接）
    
    // 3. 针对该挂载点发起重试
    ComponentState nextState = handle.retryAsync()
            .toCompletableFuture()
            .get(10, TimeUnit.SECONDS);

    assertEquals(ComponentState.ACTIVE, nextState);
}
```

---

## 插件 ClassLoader 无法被 GC 回收排查法

若在插件卸载后，通过 JVM Profiler（如 VisualVM / JProfiler）发现 `PluginClassLoader` 依然滞留在内存中，请按以下 7 步依次排查：

1. **检查插件状态**：确认 `Pf4jArtifactAdapter` 中该 artifact 状态已变为 `UNLOADED`。
2. **检查宿主挂载**：确认 `runtime.advanced().snapshot().mounts()` 中已无任何使用该插件 Factory 的挂载点。
3. **检查静态字段**：插件代码内是否有 `static` 变量引用了插件类或单例对象。
4. **检查逃逸线程**：插件内启动的线程池、Timer 或守护线程是否未执行 `shutdown()`。
5. **检查 ThreadLocal**：线程池中的复用工作线程是否残留了未清理的 `ThreadLocal` 变量。
6. **检查三方注册**：日志框架（Logback/Log4j）、JMX MBean、Dubbo/Spring 全局注册表是否未注销。
7. **检查共享契约 ClassLoader**：确认 `platform-contract` 模块是由宿主 ClassLoader 加载，而非打在插件 Jar 内部。
