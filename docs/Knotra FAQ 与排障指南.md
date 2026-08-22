# Knotra FAQ 与排障指南

## 第一步：读取稳定状态

```java
RuntimeSnapshot snapshot = runtime.advanced().snapshot();

snapshot.mounts().stream()
        .filter(mount -> mount.state() == ComponentState.FAILED)
        .forEach(mount -> System.out.printf(
                "mount=%s component=%s state=%s%n",
                mount.mountId(),
                mount.componentId(),
                mount.state()));

snapshot.diagnostics().stream()
        .filter(diagnostic -> diagnostic.failure() != FailureInfo.EMPTY)
        .forEach(diagnostic -> System.out.printf(
                "%s %s %s%n",
                diagnostic.code(),
                diagnostic.targetId(),
                diagnostic.failure().summary()));
```

如果要定位某个挂载，优先使用 `handleId()`；如果只有业务名称，使用 `mountId()` 或 `componentId()`。

## 状态机

| 状态 | 含义 | 下一步 |
|---|---|---|
| WAITING | 缺必需 Capability 或等待过渡调度 | 检查 capability 注册和依赖描述 |
| STARTING | 启动事务进行中 | 有界等待，不重复提交结构变更 |
| ACTIVE | 当前 Activation 活跃 | 可调用 |
| STOPPING | 停止并排空中 | 等待 drain，不要强行释放 |
| FAILED | 启动或清理失败 | 读取诊断，修复后 retryAsync |
| DISPOSED | 终态，挂载已移除 | 需要时重新 mount |

状态是瞬时观察值。等待当前挂载过渡完成使用 `whenSettled()`（返回 `ComponentState`，只覆盖该挂载自身）；等待 ACTIVE 使用 `requireActive(Duration)`。

## Settlement 常见误解

### awaitSettled 正常返回，为什么不是全部 ACTIVE？

操作 settlement（publication、registration、事务）表示本次操作的传播和 drain 收敛，并递归等待本次操作触发的 owned children。受影响挂载启动失败会记录在报告中，不一定让 future 异常完成；`MountHandle.whenSettled()` 等挂载方法返回的是 `ComponentState`，只描述单个挂载自身。

```java
SettlementReport report = change.awaitSettled(Duration.ofSeconds(10));
if (report.hasFailedMounts()) {
    report.failedMounts().forEach(outcome ->
            System.out.println(outcome.mountId() + " " + outcome.state()));
}
```

### hasFailedMounts 为 false，为什么 allActive 也是 false？

`allActive()` 要求影响集非空且全部 ACTIVE。影响集为空时没有失败挂载，但不构成“全部 ACTIVE”的健康证明。动态代理消费方无需重建时，提供方更新的影响集可能为空。

### report 正常，怎么确认我的挂载可用？

```java
handle.requireActive(Duration.ofSeconds(10));
```

这是具体挂载的 ACTIVE 断言，失败抛出 `MountNotActiveException` 并携带状态、目标标识和诊断。

## Publication 排查

### update 被拒绝

检查 `publication.state()`：

- `UNPUBLISHED`：主动撤销是终态，需要重新创建 Publication；`unpublish()` 返回的 change 中 `registration()` 为 null。
- `DISPLACED`：外部 revoke、同 key 外部替换、Context 释放或 Runtime close 移除了当前注册。
- `PUBLISHED`：仍可更新。

并发 update 只有一个线性化结果，但每个成功调用都会得到自己的 `PublicationChange`。不要共享一个 change 对象等待不同操作。

### 能否撤销后继续使用旧 Registration？

不能。被 replace 或 revoke 的旧 Registration 已失效。Advanced API 中 `replace()` 返回新 Registration，`revoke()` 返回本次撤销的 settlement。

### 事务里的 StagedRegistration 是什么？

它是事务记录期间的类型化 token。提交后仍可作为 opaque handle 撤销，但不会升级为已提交 Registration，也没有 replace 或 settlement 方法。

## 启动失败

1. 读取挂载诊断和 `FailureInfo.exceptionType()`。
2. 检查必需 Capability 是否在同一 Context 可见。
3. 检查配置 decoder 与 factory normalizer。
4. 检查构造器或 initializer 是否执行 I/O。
5. 修复后调用 `retryAsync()`，等待返回状态。

```java
ComponentState state = handle.retryAsync()
        .toCompletableFuture()
        .get(10, TimeUnit.SECONDS);
assertEquals(ComponentState.ACTIVE, state);
```

## 诊断码

| 诊断码 | 含义 | 处置 |
|---|---|---|
| MISSING_CAPABILITY | 必需能力缺失 | 检查发布 Context 与 Capability 类型 |
| CAPABILITY_SLOT_OCCUPIED | 同 Context 同名槽位已占用 | 撤销旧注册或使用 update |
| CAPABILITY_TYPE_CONFLICT | 同名能力绑定不同 Java 类型 | 修正 key，无法强制替换 |
| BINDING_CYCLE | 依赖图环 | 打破环或使用动态依赖 |
| ACTIVATION_FAILED | 启动失败 | 查看 FailureInfo 和启动日志 |
| ROLLBACK_FAILED | 提交或回滚失败 | 保持现场，检查清理状态 |
| CLEANUP_FAILED | 受管资源清理失败 | 修复资源后 retry |
| NON_CONVERGENT_RECONCILE | 自动重激活超过迭代限制 | 检查依赖振荡 |
| INVALID_LIFECYCLE_OPERATION | 操作非法或目标已失效 | 检查句柄状态和事务参数 |
| INVALID_MOUNT_ID | mountId 缺失或冲突 | 使用稳定唯一 ID |
| INVALID_CONFIG | 配置为 null、decoder/normalizer 失败 | 修正 raw 配置与类型 |

## 清理失败

清理失败不代表旧对象已经安全释放。Knotra 保留 FAILED 状态和诊断，等待显式 retry：

```java
assertEquals(ComponentState.FAILED, handle.disposeAsync()
        .toCompletableFuture()
        .get(10, TimeUnit.SECONDS));

assertEquals(ComponentState.DISPOSED, handle.retryAsync()
        .toCompletableFuture()
        .get(10, TimeUnit.SECONDS));
```

常见原因：

- 连接池 close 阻塞或抛错。
- 线程未响应中断。
- 异步 disposer 返回的 stage 永不完成。
- Spring destroy 吞掉异常。
- 本地缓存持有组件实例。

## 动态调用失败

`dynamic` 与 `dynamicCapability` 的 required 只约束首次启动。Bean ACTIVE 后提供方消失不会自动停用，调用会失败。

排查：

1. `runtime.root().view().find(TYPE)` 检查能力是否存在。
2. 检查调用方和提供方是否在同一 Context。
3. 确认接口来自共享 contract 模块。
4. 确认没有在方法租约内启动长期后台任务。
5. 多方法一致性要求使用 `DynamicCapability.call`，而不是普通代理。

## Context 找不到能力

能力查找沿 Context 树向上进行：

1. 子 Context 可遮蔽父 Context。
2. 提供方必须发布到可见父 Context 或目标子 Context。
3. Context 释放后其中注册全部移除。
4. 子 Context 的 Publication 不会自动改发布到根。

排查时打印 capability name、类型二进制名和 contextId，不要只打印对象 `toString`。

## 快照中有诊断，但不知道目标

诊断的 `targetId` 通常是 handleId；Loader 快照可把 path 映射到 handleId：

```java
LoaderSnapshot.EntrySnapshot entry = loader.snapshot()
        .entry("payment.primary")
        .orElseThrow();

RuntimeDiagnostic diagnostic = runtime.advanced().snapshot().diagnostics().stream()
        .filter(item -> item.targetId().equals(entry.handleId()))
        .findFirst()
        .orElseThrow();
```

## 插件 loader 未回收

按顺序检查：

1. artifact 是否 UNLOADED。
2. adapter ownership 是否为空。
3. runtime mounts 中是否还有该 factory。
4. 宿主静态字段是否保存插件实例、Class 或异常。
5. 线程池是否继承插件 loader。
6. ThreadLocal 是否清理。
7. 日志、JMX、注册中心、序列化缓存是否未注销。

Knotra snapshot、report 和 `FailureInfo` 不保存插件私有 Class 或 loader。`PublicationChange` 可以持有共享合约 Class；如果测试无法回收，应先确认该 Class 是否属于共享 contract 模块。

## 如何开启更多失败详情

```java
KnotraConfig config = new KnotraConfig(
        "order-runtime",
        256,
        KnotraConfig.FailureDetailPolicy.defaults().withStackTraces(true));

KnotraRuntime runtime = KnotraRuntime.create(config);
```

详情有界：默认最多 3 层 cause、32 帧堆栈、每段 500 字符。开启堆栈会保留文本形式的帧，不会保留 Throwable 或 ClassLoader。

## 什么时候用事务

只有需要多个结构变更原子提交时使用：

```java
runtime.advanced().transact(transaction -> {
    transaction.revoke(oldHandle);
    return transaction.provide(context, Message.class, nextMessage);
});
```

单次 publish、update、unpublish、register 或 revoke 不需要手写事务，直接使用对应 Simple/Advanced API 即可得到操作 settlement。mount 返回的是挂载句柄：用 `requireActive(Duration)` 断言健康，或用 `whenSettled()`（返回 `ComponentState`）等待自身过渡。
