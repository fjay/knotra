# Knotra 线程模型与生产实践

本文集中回答三类问题：**你的代码在哪个线程上跑、可以阻塞多久、和既有生态怎么共存**。适用于 `0.1.0-SNAPSHOT`；语义背景见[运行时设计文档](<Knotra 运行时设计文档.md>)。

## 执行器总览

Knotra 没有全局线程池，各模块使用独立执行器：

| 层 | 执行器 | 线程 | 说明 |
|---|---|---|---|
| `knotra-core` | `newVirtualThreadPerTaskExecutor()` | 无名虚拟线程 | 每次组件生命周期过渡提交一个任务 |
| `knotra-loader` | `newSingleThreadExecutor()` | 守护线程 `loader-<id>-coordinator` | 所有 reconcile 操作在此串行 |
| `knotra-events` | `newCachedThreadPool()` | 守护线程 `event-bus-<N>-worker` | 异步分发；TCCL 设为 bus 创建线程的 TCCL |
| EventBus 关闭 | 临时线程 | 守护线程 `event-bus-<N>-close` | 等待自有执行器终止 |

没有内建定时器、调度器或心跳线程。

## 组件作者的线程契约

- **`start()` 在 Core 的虚拟线程上执行，且在协调器锁外。** 慢初始化（连数据库、拉配置）不会阻塞其他组件或宿主事务。
- 同一 `ComponentHandle` 的过渡严格串行：start、stop、reconfigure、retry、dispose 排成一条链。`start()` 阻塞只延迟**自己**的下一次过渡，不会拖住协调器，但会推迟依赖自己的重激活。
- 虚拟线程上应避免长时间持有 `synchronized` 或执行会 pin 载体的原生调用；需要重锁的代码优先用 `ReentrantLock` 或把锁移出 `start()`。
- **组件实例跨 Activation 复用**：`ComponentFactory.create()` 只在挂载时调用一次；reconfigure、provider 替换都会重新调用同一个组件实例的 `start()`，且前一次 Activation 的资源应已通过 LifecycleScope 清理。跨代状态放组件字段时，必须在 `start()` 里重置。
- `start()` 抛 `Exception` 或 `Error` 都会被捕获：暂存注册与资源回滚，组件进入 `FAILED`，错误文本进入诊断；`whenSettled()` 正常完成为 `FAILED` 状态，不会异常完成。
- 同步 disposer 在清理虚拟线程上调用；`onCloseAsync` 登记的动作自行决定 stage 完成线程，`manageAsync` 则直接托管实现 `AsyncCloseable` 的资源。

## 宿主调用线程

- `runtime.transact(...)` 的 callback 在**调用线程**执行，包括 factory `create()` 与 typed `normalizeConfig()`；拒绝时抛出携带诊断的 `TransactionRejectedException`，不返回可忽略的失败值。
- 协调器临界区只做确定性校验与代际发布：factory、normalizer 和用户 `start()` 都不持有协调器锁。
- `retryAsync()` 只对 `FAILED` 组件合法；其他状态返回异常完成的 `IllegalStateException`，不排队、不修改状态。
- `whenSettled()` 的完成线程是推动最后一次过渡完成的线程（通常是 Core 虚拟线程，或最后一个异步 disposer 的完成线程）；不要在回调里假设线程身份。
- `runtime.closeAsync()` 复用根 Context 清理路径：设置 closing（之后新 mutation 被拒绝）、组件过渡仍在 Core 虚拟线程执行、close future 在最后一段 settlement 完成线程上完成。成功时组件 `whenSettled()` 得到 `DISPOSED`；清理失败时组件保持 `FAILED`、close future 异常完成、执行器保留供重试。

## EventBus 线程

- `dispatch(EventDefinition.Sync, ...)`：accept 后在**调用线程**逐个执行 listener。
- `dispatch(EventDefinition.Parallel, ...)`：每个 listener 调用提交到 `event-bus-<N>-worker`。
- Serial/Bail/Waterfall 的 `dispatch(...)` 折叠成 stage 链，第一段可能在调用线程执行，后续各段在前一段的完成线程执行。
- **TCCL 规则**：listener 回调执行前，TCCL 切换为 `listener.getClass().getClassLoader()`，回调结束恢复。插件 listener 里用 TCCL 加载资源时拿到的是插件自己的 loader。
- `closeAsync()` 拒绝新工作、等待关闭前已接受的分发收敛，然后 `shutdownNow()` 自有执行器；若构造时注入了外部执行器则不会关闭它。

## 与 Spring 共存

Knotra 不提供 Spring 集成层（这是明确非目标），但两者可以干净共存。以下是经过 API 语义验证的推荐模式：

**宿主模式**：Spring Boot 应用本身就是宿主。在启动阶段 `KnotraRuntime.create()`，把 runtime 注册为 Bean 并在销毁回调里 `closeAsync()`：

```java
@Configuration
class KnotraHostConfig {
    @Bean(destroyMethod = "close")
    KnotraRuntime knotraRuntime() {
        return KnotraRuntime.create();
    }
}
```

Spring 管理的 Bean 可以通过 `runtime.transact(...)` 做原子结构变更，或使用 Runtime 的单操作 convenience；关闭顺序确保 adapter/loader 先于 Runtime。

**组件内嵌 DI 模式**：组件 `start()` 里创建自己的小型容器（例如一个 `AnnotationConfigApplicationContext`），把容器关闭登记进 LifecycleScope：

```java
@Override
public void start(ActivationContext context, PluginConfig config) {
    var app = new AnnotationConfigApplicationContext();
    app.registerBean(PluginConfig.class, () -> config);
    app.refresh();
    context.lifecycle().manage("spring-context", (AutoCloseable) app::close);
    context.provide(SERVICE, app.getBean(Service.class));
}
```

这样每次重激活都拿到新容器，旧容器随旧 Activation 关闭，不会跨代泄漏。

**两条纪律**：不要把 Knotra 句柄（handle、RegistrationHandle）存进长生命周期的静态字段；不要让宿主代码长期持有插件创建的对象——两者都会阻止 ClassLoader 回收。

## 监控接线

Knotra 自有源码**没有任何日志输出**（core/events/loader/pf4j 均无 logger 调用）；`knotra-pf4j` 传递的 PF4J 内部日志走 SLF4J。观测的正确入口是轮询三层 Snapshot：

```java
runtime.snapshot().diagnostics().forEach(d -> counter(d.code().name()).increment());
adapter.artifacts().forEach(a -> gauge(a.state().name()));
loader.snapshot().diagnostics().forEach(d -> counter(d.code().name()).increment());
```

诊断码是稳定枚举，适合直接映射为指标标签；EventBus 的 `whenIdle()` 可以作为空闲信号。Knotra 不内建 Micrometer/Prometheus 端点，接线由宿主完成。

## 容量与性能边界

当前版本**未发布基准测试数据**，以下是机制层面可以确定的约束：

- 单组件过渡严格串行：同一个 handle 的 start/stop 不会并发。
- 依赖闭包级联重启的规模等于受影响闭包的大小；provider 频繁替换会成比例放大重启次数。
- 自动收敛有上限：`maxReconcileIterations` 默认 256，超限后停止自动重启并报 `NON_CONVERGENT_RECONCILE`。
- 协调器临界区只做校验与发布，用户代码在锁外；结构事务的排队时间主要由其他事务的临界区长度决定，不由组件启动时间决定。
- Snapshot 构建当前结构的完整不可变视图，结构越大成本越高；高频轮询大快照需自行评估开销。

生产采用前，请按自身的组件规模、替换频率和快照轮询频率做负载测试；仓库不提供这些数字，也不替你假设它们。

## 安全与资源隔离

Knotra 的 ClassLoader 边界是**合约层面**的（共享包委派、类型身份校验），不是安全沙箱：

- 不做插件权限模型、代码签名校验或访问控制；
- 不做组件级资源配额（线程数、内存、组件数量）；
- 只应加载可信来源的插件 JAR。

## 相关文档

- [Knotra 运行时设计文档](<Knotra 运行时设计文档.md>)：并发协议与失败恢复的完整定义。
- [Knotra FAQ 与排障指南](<Knotra FAQ 与排障指南.md>)：线程相关症状（STARTING 长期停留、close 卡住）的排查。
- [Knotra 测试指南](<Knotra 测试指南.md>)：并发与时序行为的测试方法。
