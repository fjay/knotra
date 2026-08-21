# Knotra

**应用不重启，组件可替换。** Knotra 是一个 JVM 动态组件运行时：应用启动后仍可以替换实现、升级插件、局部重启或按 Context 切换服务，同时保持依赖绑定、在途任务与资源清理的一致性。

![Java](https://img.shields.io/badge/Java-21%2B-orange)
![Maven](https://img.shields.io/badge/Maven-3.9%2B-blue)
![Version](https://img.shields.io/badge/version-0.1.0--SNAPSHOT-blue)
![Tests](https://img.shields.io/badge/tests-230%20passing-brightgreen)

> **项目状态**：`0.1.0-SNAPSHOT`，尚未发布到 Maven Central。0.x 阶段不承诺跨版本 API 兼容；当前公开 API 已按“默认失败不可忽略、Core 配置强类型、集成边界显式 decode”重构。

## 为什么需要它

把一个 JAR 加载进 JVM 很容易，困难的是加载之后的运行语义：

- 新组件初始化完成前不能被其他组件看见。
- 旧组件仍有在途任务时，必须先排空再关闭。
- 组件启动期间依赖被替换，这次启动必须作废并按新依赖重试。
- 插件卸载清理失败时要保留现场并允许重试，不能伪造成功。
- 一组结构修改必须整体提交或整体拒绝。

Knotra 把 Context、Capability、Activation、依赖代际和资源所有权作为一等运行时结构管理，而不是把这些责任留给普通注册表或 `start()/stop()` 回调。

## 快速开始

当前版本需在同一个 Maven reactor 内引用，或先执行 `mvn install`：

```xml
<dependency>
  <groupId>io.knotra</groupId>
  <artifactId>knotra-core</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

下面定义一个消费 `Greeting` 的无配置组件，发布 v1 provider，再用原子事务替换为 v2：

```java
import io.knotra.*;
import java.util.concurrent.TimeUnit;

public final class Demo {
    interface Greeting {
        String greet(String name);
    }

    static final CapabilityKey<Greeting> GREETING =
            CapabilityKey.of("app.greeting", Greeting.class);

    static final class GreeterFactory implements ComponentFactory<NoConfig> {
        @Override
        public Component<NoConfig> create() {
            return new Component<>() {
                @Override
                public ComponentDescriptor descriptor() {
                    return ComponentDescriptor.of(
                            CapabilityRequirement.required(GREETING));
                }

                @Override
                public void start(ActivationContext context, NoConfig config) {
                    Greeting greeting = context.require(GREETING);
                    System.out.println(greeting.greet("world"));
                }
            };
        }
    }

    public static void main(String[] args) throws Exception {
        try (KnotraRuntime runtime = KnotraRuntime.create()) {
            RegistrationHandle v1 = runtime.provide(
                    GREETING, name -> "v1: hello " + name);

            // 无配置 mount 不需要传 NoConfig.INSTANCE。
            ComponentHandle<NoConfig> greeter =
                    runtime.mount("greeter", new GreeterFactory());
            greeter.whenSettled().toCompletableFuture()
                    .get(10, TimeUnit.SECONDS);

            runtime.transact(tx -> {
                tx.revoke(v1);
                return tx.provide(
                        runtime.root(),
                        GREETING,
                        name -> "v2: bonjour " + name);
            });
            greeter.whenSettled().toCompletableFuture()
                    .get(10, TimeUnit.SECONDS);
        }
    }
}
```

输出：

```text
v1: hello world
v2: bonjour world
```

替换 provider 后没有手工重启代码。Runtime 会先停止绑定旧 registration 的消费方，逆序清理其 Activation 资源，再绑定新 registration 启动下一代 Activation。

## 默认路径与专家路径

单个根 Context 操作使用 Runtime convenience：

```java
RegistrationHandle registration = runtime.provide(KEY, value);
ComponentHandle<AppConfig> app = runtime.mount("app", factory, config);
ComponentHandle<NoConfig> metrics = runtime.mount("metrics", metricsFactory);
```

多个结构修改需要原子提交时使用 `transact`：

```java
TransactionReceipt<RegistrationHandle> receipt = runtime.transact(tx -> {
    tx.revoke(oldRegistration);
    tx.mount(runtime.root(), "worker", workerFactory);
    return tx.provide(runtime.root(), SERVICE, replacement);
});

receipt.settlement().toCompletableFuture().join();
```

事务成功才返回 `TransactionReceipt`。配置拒绝、slot 冲突或非法生命周期操作会抛出 `TransactionRejectedException`，异常携带 `List<RuntimeDiagnostic>`；即使调用方忽略返回值，也不会静默丢失失败。

`settlement()` 只等待该事务直接触发的过渡，不表示 Runtime 全局静止。观察特定组件使用 `ComponentHandle.whenSettled()`。

## 一分钟理解模型

| 概念 | 含义 |
|---|---|
| `Context` | Capability 可见范围。子 Context 能看到父级注册，也可以用本地注册遮蔽父级 |
| `CapabilityKey<T>` | 名称与 JVM 合约类型组成的服务身份；依赖按 registration 代际跟踪 |
| `ComponentHandle<C>` | 跨多次 Activation 保持稳定的逻辑挂载点 |
| `Activation` | 组件按固定 BindingSet 运行的一代实例；启动失败或 stale 会回滚 |
| `LifecycleScope` | Activation 拥有的资源树，默认确定性 LIFO 清理，失败条目可重试 |
| `TransactionReceipt` | 已成功提交的结构代际、返回值和直接 settlement |

组件依赖必须预先声明：

```java
ComponentDescriptor.of(
        CapabilityRequirement.required(DATABASE),
        CapabilityRequirement.optional(METRICS));
```

`ActivationContext.require/find` 只能访问 descriptor 声明的 key。宿主读取使用唯一入口 `runtime.root().view()` 或其他 `ContextHandle.view()`；读取不会建立依赖绑定。

## 配置边界

Core 的配置是强类型的。`ComponentFactory<C>` 可以校验并归一化同一个 `C`：

```java
public final class ToolFactory implements ComponentFactory<ToolConfig> {
    @Override
    public ToolConfig normalizeConfig(ToolConfig config) {
        if (config.command().isBlank()) {
            throw new IllegalArgumentException("command must not be blank");
        }
        return new ToolConfig(config.command().trim(), config.timeoutSeconds());
    }

    @Override
    public Component<ToolConfig> create() {
        return new ToolComponent();
    }
}
```

Map、JSON tree 或配置文件节点不属于 Core mount 类型。Loader 和 PF4J export 使用 `ConfigDecoder<C>` 在集成边界把 raw 值转换成 `C`，之后才进入 Core。这样直接调用保持编译期类型安全，声明式装配仍可接受外部配置形态。

配置归一化抛错或返回 null 时使用稳定诊断码 `INVALID_CONFIG`。

## 生命周期与资源

同步资源、同步动作、异步动作和异步资源分别登记：

```java
Connection connection = context.lifecycle()
        .manage("database", openConnection());
context.lifecycle().onClose("flush", this::flush);
context.lifecycle().onCloseAsync("worker", this::stopWorkerAsync);

EventSubscription subscription = bus.subscribe(EVENT, listener);
context.lifecycle().manageAsync("listener", subscription);
```

`AsyncCloseable` 资源可直接交给 `manageAsync`。串行 scope 按全局 LIFO 清理；显式 `parallelChild` 的直属条目并发清理。

组件动作均明确使用异步后缀：

```java
handle.reconfigureAsync(newConfig);
handle.retryAsync();
handle.disposeAsync();
```

启动或清理失败结算为 `ComponentState.FAILED`，保留诊断与可重试现场。`ComponentHandle.close()` 会阻塞等待清理，最终不是 `DISPOSED` 时抛出异常，不会静默接受失败。

## EventBus

添加 `knotra-events` 后，将 `EventBusFactory` 作为普通组件挂载：

```java
ComponentHandle<NoConfig> provider =
        runtime.mount("event-bus", new EventBusFactory());
provider.whenSettled().toCompletableFuture().join();
```

事件 mode 编码在 definition 的静态类型里，只声明一次：

```java
static final EventDefinition.Serial<JobFinished> JOB_FINISHED =
        EventDefinition.serial(JobFinished.class);

EventBus bus = context.require(EventCapabilities.EVENT_BUS);
EventSubscription subscription = bus.subscribe(JOB_FINISHED, event -> {
    persist(event);
    return CompletableFuture.completedFuture(true);
});
context.lifecycle().manageAsync("job-finished", subscription);

EventDispatch<JobFinished> report = bus.dispatch(JOB_FINISHED, event)
        .toCompletableFuture()
        .join();
```

`Sync`、`Parallel`、`Serial`、`Bail` 和 `Waterfall` 是不同 definition 类型。`subscribe` 与 `dispatch` 通过 overload 推断正确 listener，无法把 serial definition 传给 parallel dispatch。

Listener 失败进入 `EventDispatch.failures()`；accepted dispatch、subscription close 与 bus close 仍遵循排空语义。

## PF4J 与 Loader

插件导出共享配置 token、raw decoder 和类型化 factory：

```java
@Extension
public final class ToolProvider implements RuntimeComponentProvider {
    @Override
    public Collection<ExportedComponentFactory<?>> factories() {
        return List.of(ExportedComponentFactory.of(
                ToolConfig.class,
                raw -> decodeToolConfig(raw),
                new ToolFactory()));
    }
}
```

宿主可以直接类型化挂载：

```java
try (Pf4jArtifactAdapter plugins = Pf4jArtifactAdapter.create(
        Path.of("plugins"), runtime, Set.of("com.example.contract"))) {

    plugins.loadArtifact(Path.of("plugins/tool.jar"));

    ArtifactFactoryHandle<ToolConfig> factory = plugins.factories()
            .resolve("tool", ToolConfig.class)
            .orElseThrow();
    ToolConfig config = factory.decodeConfig(rawConfig);
    ComponentHandle<ToolConfig> tool =
            factory.mount(runtime.root(), "tool", config);
}
```

工厂目录只有一个入口 `plugins.factories()`：

- `list()` / `find(id)` 返回只读文本元数据。
- `resolve(id)` 返回 wildcard executable handle，供官方桥接安全捕获泛型。
- `resolve(id, Class<C>)` 返回宿主直接使用的类型化 handle。

声明式装配使用 `knotra-loader` 与官方 `knotra-pf4j-loader`：

```java
try (KnotraLoader loader = KnotraLoader.over(
        runtime,
        runtime.root(),
        Pf4jFactoryResolver.of(plugins))) {

    loader.reconcile(ComponentTree.of(
            ComponentEntry.configured(
                    "tools/main", FactoryRef.of("tool", "1.0.0"), rawConfig),
            ComponentEntry.of(
                    "metrics", FactoryRef.of("metrics", "1.0.0"))))
            .requireConverged();
}
```

桥接负责 factory 版本匹配、decoder、实现 fingerprint、泛型捕获和受控槽位挂载。宿主不再编写 `Class<C>` 映射、unchecked cast 或 fingerprint 拼接。需要 classpath fallback 时使用：

```java
ComponentFactoryResolver resolver = Pf4jFactoryResolver.withFallbacks(
        plugins, classpathResolver);
```

Loader 的 `reconcile` 返回时相关条目已 settle，但 settle 不等于 `ACTIVE`：必需依赖缺失可以是 `WAITING`。`requireConverged()` 验证 desired state 无诊断，不把合法的 `WAITING` 错当成异常。

## 可观测性

`runtime.snapshot()`、`loader.snapshot()`、artifact snapshot 和 EventBus snapshot 都是不可变 DTO，不引用 component、listener、provider value、Throwable、Class 或 ClassLoader。长期保存 Snapshot 不会阻止插件卸载后的 ClassLoader 回收。

诊断消费者应匹配稳定枚举，不要匹配 message 文本：

```java
runtime.snapshot().diagnostics().stream()
        .filter(item -> item.code() == DiagnosticCode.CLEANUP_FAILED)
        .forEach(this::alert);
```

## 模块

| 模块 | 职责 | 编译依赖 |
|---|---|---|
| `knotra-core` | Context、Capability、事务、Activation、LifecycleScope、Snapshot | 无运行时依赖 |
| `knotra-events` | 类型化、可排空的 EventBus | Core |
| `knotra-pf4j-spi` | 插件导出的共享 factory/decoder SPI | Core、PF4J provided |
| `knotra-pf4j` | artifact 加载、目录、受控挂载、drain、ClassLoader 防护 | Core、SPI、PF4J、ASM |
| `knotra-loader` | desired component tree reconcile | Core |
| `knotra-pf4j-loader` | PF4J catalog 到 Loader resolver 的官方桥接 | Loader、PF4J |
| `knotra-integration-tests` | 真实插件 JAR 与跨模块验证，仅测试 | 全部模块 |

## 文档

- [API 与集成指南](<docs/Knotra API 与集成指南.md>)：公开接口、失败语义与完整组合示例。
- [运行时设计文档](<docs/Knotra 运行时设计文档.md>)：Context、BindingSet、Activation、事务提交与清理状态机。
- [实战案例：动态物流路由系统](<docs/Knotra 实战案例：动态物流路由系统.md>)：插件升级、旧任务排空和 ClassLoader 回收。
- [插件工程化手册](<docs/Knotra 插件工程化手册.md>)：从 Maven 工程到可加载 artifact。
- [线程模型与生产实践](<docs/Knotra 线程模型与生产实践.md>)：回调线程、阻塞边界、关闭顺序和监控。
- [测试指南](<docs/Knotra 测试指南.md>)：依赖替换、清理失败、drain 竞态和 GC 测试。
- [FAQ 与排障指南](<docs/Knotra FAQ 与排障指南.md>)：按状态与诊断码排查。
- [公开 API 重构设计](<docs/20260821 Knotra 公开 API 重构设计文档.md>)：本次破坏性 API 重构的边界、机制与验证要求。

## 非目标

- 不提供通用 DI、AOP 或 Spring 替代品。
- 不做分布式协调或跨 JVM 热替换。
- 不提供插件权限沙箱、签名校验或资源配额，只应加载可信代码。
- Loader 不监听文件系统，期望状态由调用方显式提交。
- 不规定 YAML、JSON 或配置中心格式；raw decode 属于集成边界。

## 构建与验证

```bash
mvn clean verify
```

当前 reactor 包含 230 项测试：Core 98、Events 44、PF4J 37、Loader 36、跨模块集成 15。集成测试会构建真实 PF4J fixture JAR，并验证官方 Loader bridge、nested tree、配置拒绝、ownership、drain 竞态、并发关闭和 ClassLoader GC。
