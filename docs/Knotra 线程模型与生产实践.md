# Knotra 线程模型与生产实践

Knotra 的生命周期协调和业务启动是分离的：协调器维护结构一致性，Activation 的 start、stop 和资源清理在受控执行边界上运行。使用者的核心责任是不阻塞协调边界，并把跨 Activation 资源交给 LifecycleScope。

## 执行边界

| 边界 | 允许的工作 | 禁止的工作 |
|---|---|---|
| 事务回调 | 记录 provide / revoke / mount / dispose 意图 | I/O、等待 settlement、调用 Loader |
| Activation start | 构建对象、验证配置、申请并登记资源 | 发布未验证输出、长时间无界等待 |
| Lifecycle cleanup | 关闭连接、取消任务、等待有限清理 | 启动新业务请求、忽略异常 |
| Dynamic proxy 调用 | 短期方法租约和业务计算 | 长时间占用 lease 做后台任务 |
| 业务线程 | 调用 capability、await settlement | 直接修改内部 runtime 状态 |

`awaitSettled()`、`requireActive(Duration)` 和测试里的 `get(timeout)` 是阻塞便利入口。它们不改变 settlement 的异步执行模型，只等待已经返回的变更对象。

等待范围分两类：Publication、Registration 和事务返回的操作 settlement 会递归等待本次操作触发的 owned children；`MountHandle.whenSettled()` 只等待该挂载自身的生命周期过渡，父挂载 ACTIVE 不代表它新提交的子挂载都已收敛。

## 无界等待

生产代码不要使用：

```java
// 反例：没有预算，故障时线程可能永久停留。
stage.toCompletableFuture().get();
```

应使用：

```java
change.awaitSettled(Duration.ofSeconds(10));
```

或：

```java
ComponentState state = handle.whenSettled()
        .toCompletableFuture()
        .get(10, TimeUnit.SECONDS);
```

try-with-resources 调用的 `close()` 同样是无界阻塞。入门示例为了篇幅使用它；生产停机应等待 `closeAsync()` 并施加预算：

```java
runtime.closeAsync()
        .toCompletableFuture()
        .get(30, TimeUnit.SECONDS);
```

超时会抛出结构化异常并恢复中断标记。调用方应把它当作真实故障处理，记录诊断并决定重试或降级，不要循环 sleep。

## TCCL 规则

Spring、ServiceLoader、反射代理和序列化框架经常读取线程上下文类加载器（TCCL）。Knotra 集成边界负责在需要的启动和清理阶段安装正确 loader，并在 finally 中恢复原 TCCL。

业务代码遵循：

1. 共享合约放在宿主可见的 contract 模块。
2. 插件私有类型不进入宿主 API、快照或诊断 DTO。
3. 不在普通业务线程手工切换 TCCL。
4. 自定义类加载器集成必须恢复 TCCL。
5. 线程池中的线程不能捕获插件 loader；提交任务时显式选择宿主 loader 或插件作用域。

需要多 ClassLoader Spring 配置类时显式声明：

```java
var factory = SpringModules.noConfig("plugin-spring")
        .annotatedClasses(PluginConfig.class)
        .classLoader(pluginClassLoader)
        .expose(PluginOutput.class)
        .build();
```

## 动态调用租约

方法级代理适合无状态调用：

```java
PaymentGateway gateway = context.subscribe(PaymentGateway.class)
        .proxy(PaymentGateway.class);
ChargeResult result = gateway.charge(request);
```

每个方法在调用期间固定一个 provider，方法返回后租约释放。在代理方法里启动后台任务会让任务逃出租约边界，替换语义不再成立。

多个方法必须一致时：

```java
DynamicCapability<Pricing> pricing = context.subscribe(Pricing.class);
Quote quote = pricing.call(current -> new Quote(
        current.price(orderId),
        current.discount(orderId)));
```

异步操作使用 `callAsync`；返回的 CompletionStage 完成前租约保持有效，Knotra drain 会等待该 stage 收敛。

## 资源管理

组件和 Bean 不应实现“猜测式 close”。资源创建后立即登记：

```java
DataSource dataSource = createDataSource(config);
context.lifecycle().manage("order-datasource", dataSource);
```

异步资源：

```java
HttpClient client = HttpClient.create(config);
context.lifecycle().manageAsync("http-client", client);
```

清理顺序是 LIFO：

```java
context.lifecycle().onClose("cache", () -> cache.invalidateAll());
context.lifecycle().onCloseAsync("worker", worker::shutdown);
```

后台线程必须支持取消，并在线程内捕获异常。不要使用非守护线程承载无法结束的任务。

## 优雅停机

外层停机流程：

1. LB 或入口层停止接收新请求。
2. 等待外层业务请求完成，超出预算则记录未完成请求。
3. 关闭 Loader 与 PF4J adapter，先释放受管子树和 artifact。
4. 关闭宿主动态桥和挂载。
5. 关闭 Runtime。
6. 对 FAILED 清理保留诊断，显式重试。

示例：

```java
externalServer.stop acceptingRequests();
externalServer.drain(Duration.ofSeconds(30));

loader.closeAsync()
        .toCompletableFuture()
        .get(30, TimeUnit.SECONDS);
adapter.closeAsync()
        .toCompletableFuture()
        .get(30, TimeUnit.SECONDS);

runtime.closeAsync()
        .toCompletableFuture()
        .get(30, TimeUnit.SECONDS);
```

清理失败不要被转换成“关闭成功”。保留 FAILED 状态和诊断是可恢复性的一部分。

## 监控

所有 Runtime snapshot 都通过 Advanced API 读取：

```java
RuntimeSnapshot snapshot = runtime.advanced().snapshot();
```

常用指标：

```java
Gauge.builder("knotra.mounts.active", () ->
                runtime.advanced().snapshot().mounts().stream()
                        .filter(mount -> mount.state() == ComponentState.ACTIVE)
                        .count())
        .register(registry);

Gauge.builder("knotra.mounts.failed", () ->
                runtime.advanced().snapshot().mounts().stream()
                        .filter(mount -> mount.state() == ComponentState.FAILED)
                        .count())
        .register(registry);

Gauge.builder("knotra.registrations", () ->
                runtime.advanced().snapshot().registrations().size())
        .register(registry);

Gauge.builder("knotra.diagnostics", () ->
                runtime.advanced().snapshot().diagnostics().size())
        .register(registry);
```

建议告警：

- FAILED 挂载数量非零，且持续超过恢复预算。
- 结构代际长期增长但没有对应发布记录。
- activation 重启频率异常。
- artifact drain 或 unload 超时。
- cleanup failure 反复重试失败。

指标读取线程只持有 snapshot 纯数据，不会引用组件实例或插件 loader。

## 日志

日志应包含：

- mountId / handleId
- capability name 与类型名
- contextId
- generation
- diagnostic code
- FailureInfo summary

不要把 `Throwable` 放进长期诊断对象。应用日志可以在异常发生现场打印堆栈，Knotra 诊断保留的是有界稳定摘要。

## 容量与超时建议

| 操作 | 建议预算 |
|---|---|
| 本地 Bean 激活 | 5-15 秒 |
| 连接外部系统 | 外部客户端自身超时，小于 settlement 预算 |
| 提供方替换收敛 | 按受影响挂载数量评估 |
| artifact drain | 30-120 秒 |
| Runtime close | 大于最慢受管清理预算 |

预算应来自配置并可观测。任何等待超时都应有日志、指标和恢复动作。
