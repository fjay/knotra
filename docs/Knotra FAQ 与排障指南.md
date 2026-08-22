# Knotra FAQ 与排障指南

> 💡 **面向读者**：当您的组件卡在某个状态（如 `WAITING` 或 `FAILED`）、调用报错或插件卸载遇到问题时，本文提供“症状 $\to$ 原因 $\to$ 解决方案”的快速排查路径。

---

## 🧭 1. 排障三板斧（30 秒快速定位）

遇到任何异常情况，第一步是**打印运行时快照（Snapshot）**：

```java
// 获取当前系统快照
RuntimeSnapshot snapshot = runtime.snapshot();

// 1. 检查是否存在错误诊断码 (Diagnostics)
snapshot.diagnostics().forEach(diag -> {
    System.err.printf("[告警] 错误码: %s, 说明: %s, 关联路径: %s%n",
            diag.code(), diag.message(), diag.path());
});

// 2. 检查哪些组件处于非 ACTIVE 状态
snapshot.components().stream()
        .filter(c -> c.state() != ComponentState.ACTIVE)
        .forEach(c -> {
            System.err.printf("[异常组件] ID: %s, 当前状态: %s, 目标: %s%n",
                    c.componentId(), c.state(), c.goal());
        });
```

---

## 🚦 2. 状态机速查表

### 2.1 组件状态（ComponentState）

```mermaid
stateDiagram-v2
    [*] --> WAITING : 依赖缺失 / 环依赖
    WAITING --> STARTING : 依赖就绪 / 收到更新
    STARTING --> ACTIVE : start() 执行成功
    STARTING --> FAILED : start() 抛出异常
    ACTIVE --> STOPPING : 依赖被替换 / 配置更新 / 销毁
    STOPPING --> WAITING : 重启中等待新依赖
    STOPPING --> STARTING : 触发下一代启动
    STOPPING --> DISPOSED : 销毁完成 (终态)
    FAILED --> STARTING : 显式调用 retryAsync()
    FAILED --> DISPOSED : 销毁
```

| 状态 | 通俗含义 | 常见原因 | 对应处理措施 |
|---|---|---|---|
| **`WAITING`** | **在原地等候**（尚未启动） | 缺少必需依赖（`REQUIRED` 未满足）、存在依赖环、或配置不满足条件。 | 检查缺少哪个 `CapabilityKey`，调用 `runtime.provide()` 提供它。 |
| **`STARTING`** | **正在启动中** | `start()` 逻辑耗时较长，或子组件正在启动。 | 检查 `start()` 中是否有长阻塞操作。 |
| **`ACTIVE`** | **正常运行中** | 状态健康，正常提供服务。 | 无需处理。 |
| **`STOPPING`** | **正在优雅停止** | 正在等待在途请求排空（Drain），或正在执行异步清理。 | 等待排空完成，或检查是否有未释放的连接阻塞。 |
| **`FAILED`** | **启动/清理失败** | 业务初始化抛错，或 `close()` 清理时发生异常。现场已保留。 | 查看快照诊断信息，修复后调用 `handle.retryAsync()`。 |
| **`DISPOSED`** | **已彻底销毁** | 终态，组件已从运行时完全卸载。 | 无。 |

---

## 📋 3. 常见诊断码（DiagnosticCode）与解决方案

| 诊断码 (Enum) | 触发原因 (为什么报错) | 解决办法 (怎么修) |
|---|---|---|
| **`MISSING_CAPABILITY`** | 组件声明了必需依赖（`required`），但当前 Context 树中找不到对应的 Provider。 | 1. 确认该依赖是否已通过 `runtime.provide()` 发布；<br/>2. 确认 Key 的名称字符串和 `Class<T>` 是否完全一致。 |
| **`BINDING_CYCLE`** | 组件之间存在循环依赖（例如 A 依赖 B，B 也依赖 A）。 | 调整架构，打破循环依赖，或将其中一方改为无状态的 `dynamicProxy` 动态依赖。 |
| **`INVALID_CONFIG`** | 类型化配置对象为 `null`，或配置校验器（`normalizer`）抛出了校验异常。 | 检查传入的 Config 对象是否合法，确保 normalizer 返回非 null 的规范配置。 |
| **`ACTIVATION_FAILED`** | 组件的 `start()` 方法或构造函数抛出了异常。 | 查看异常堆栈，修复业务代码错误后调用 `handle.retryAsync()` 重新激活。 |
| **`CLEANUP_FAILED`** | 组件销毁时，某个已登记的清理钩子（如 `close()`）抛出了异常。 | 检查资源释放逻辑是否幂等；修复后调用 `handle.retryAsync()` 会自动重试失败的清理条目。 |
| **`CAPABILITY_SLOT_OCCUPIED`** | 同一个 Context 内尝试重复注册相同名称的 Capability。 | 检查是否重复调用了 `provide()`；若想替换，请使用 `Provided.replace()`。 |

---

## ❓ 4. 高频疑难问题解答（FAQ）

### Q1：为什么我的组件挂载后，一直是 `WAITING` 状态？

**诊断方法**：
检查快照中的 `requirements`：
```java
ComponentSnapshot comp = snapshot.components().get(0);
comp.requirements().forEach(req -> {
    System.out.printf("依赖 [%s], 类型: %s, 是否满足: %s%n",
            req.key().name(), req.kind(), req.satisfied());
});
```

**可能原因**：
1. **依赖未发布**：该组件声明了 `Beans.required(KEY)`，但还没有任何地方调用 `runtime.provide(KEY, ...)`。
2. **Key 类型不匹配**：名称虽然一样，但宿主使用的是 `ClassA.class`，插件使用的是 `ClassB.class`（常见于 ClassLoader 隔离问题）。
3. **父子 Context 隔离**：子 Context 中的组件无法访问平级或子级 Context 中的注册，只能访问自己及祖先 Context 的注册。

---

### Q2：调用 `Provided.replace()` 替换服务后，为什么下游没有立即生效？

**解答**：
- `replace()` 自身的**事务提交是同步的**（立即返回新的 `Provided<T>` 句柄）；
- 但下游依赖该服务的消费方组件的**重载与收敛是异步虚拟线程执行的**（为了防止阻塞当前线程）。

**正确写法**：
如果您的代码需要同步确认所有下游组件已经就绪，请等待收敛 Future：
```java
Provided<Greeting> v2 = greeting.replace(new AdvancedGreeting());

// 等待依赖收敛全部完成
v2.whenSettled().toCompletableFuture().join();

// 确保下游组件已达到 ACTIVE
greeterHandle.requireActive();
```

---

### Q3：为什么动态代理（Dynamic Proxy）调用抛出 `CapabilityUnavailableException`？

**可能原因**：
1. **当前没有任何 Provider**：该动态依赖未配置 Provider，或者旧 Provider 刚刚被撤销且尚未发布新 Provider。
2. **Provider 正在优雅停机中**：旧 Provider 已进入 `DRAINING` 状态，不再接受新请求。

**正确处理模式**：
动态依赖允许临时缺失，业务代码中建议对临时不可用做降级处理：
```java
try {
    paymentProxy.pay(order);
} catch (CapabilityUnavailableException e) {
    // 降级处理：如写入重试队列，或返回“支付渠道正在升级中”
    fallbackQueue.enqueue(order);
}
```

---

### Q4：组件清理失败变成 `FAILED` 状态，如何恢复？

**解答**：
Knotra 遵循**“故障不吞没、现场全保留”**原则。当清理失败时，不会伪造成功，而是将状态置为 `FAILED`，成功释放的资源不会被重复释放。

**恢复操作**：
```java
// 显式触发重试，Knotra 会精准重试此前失败的那一条清理动作
handle.retryAsync()
        .toCompletableFuture()
        .join();

// 确认重试后进入 DISPOSED 或 ACTIVE
System.out.println("重试后状态: " + handle.state());
```

---

### Q5：卸载 PF4J 插件后，Metaspace 内存没有下降，怀疑 ClassLoader 泄漏，怎么排查？

**排查清单**：
1. **静态变量引用**：宿主代码中是否有 `static List` 或静态缓存持有了插件中加载的对象？
2. **线程未终止**：插件内部是否自行创建了非守护线程（`new Thread()` 或 `ExecutorService`）且没有在 `close()` 中关闭？
3. **ThreadLocal 泄漏**：插件线程是否设置了 `ThreadLocal` 且未调用 `.remove()`？
4. **第三方框架缓存**：如 Jackson、Log4j 等全局缓存了插件的 Class 对象。

> 💡 **Knotra 的安全保证**：
> Knotra 自身的 `Snapshot`、`ComponentHandle`、`Diagnostic` 均采用纯元数据设计，**绝对不会持有插件的 Class 或 ClassLoader 引用**。只要插件自身规范清理了线程与引用，插件 ClassLoader 会在卸载后被 JVM 垃圾回收（GC）。
