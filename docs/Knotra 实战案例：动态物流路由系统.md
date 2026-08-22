# 动态物流路由系统：从普通 POJO 到可卸载插件

本文用一个物流路由系统演示 Knotra 的完整使用路径：先写普通 Java 业务对象，再把它们挂到 Runtime，随后引入仓库级 Context、动态调用、进程内事件、PF4J 插件和 desired state reconcile。案例只描述当前 `0.1.0-SNAPSHOT` API。

案例是虚构的工程示例，不对应某个线上系统；文中不给出吞吐、延迟或可用性数据。`ParcelInbox` 代表持久消息队列入口，消息可靠性由该队列实现提供，Knotra 不把 EventBus 当作持久队列。

## 物流业务和真正的问题

系统持续处理包裹任务记录。每条记录进入指定仓库的队列分区后，路由模块结合目的地、承运商服务范围和仓库规则，决定下一步交给哪家承运商以及使用哪种配送服务。

主要角色：

- **Parcel**：一条待路由的包裹任务记录。
- **warehouse**：站点或租户，例如上海、深圳；不同仓库可以使用不同路由规则。
- **RoutePlanner**：路由规则引擎，是本例最核心的可替换 provider。
- **ParcelInbox**：宿主提供的持久队列入口。
- **ParcelDispatcher**：队列消费者，绑定某一代路由规则并确认消息。
- **v1 / v2**：上海路由规则的两版插件 JAR。

把新 JAR 放进 JVM 只是第一步。真正的问题是：

1. 已经拿到旧 `RoutePlanner` 的 dispatcher 何时退出；
2. 已经交给旧回调的包裹由谁等待；
3. HTTP 连接、刷新线程和事件订阅按什么顺序释放；
4. 半初始化的新规则是否可见；
5. 卸载失败后，artifact 和组件分别停在什么状态；
6. 业务代码保留的插件对象或 `ClassLoader` 引用由谁负责。

Knotra 的边界也随之明确：它管理 Capability、Activation、固定 BindingSet、生命周期、结构事务和插件排空；它不提供消息持久化、零停机蓝绿切换或业务对象泄漏回收。

## 最小共享合约和 Capability

宿主与插件都必须看到同一个 JVM `Class`。路由接口、配置 record 和 Capability key 放在宿主提供的共享合约包中，例如 `com.acme.logistics.contract`。

```java
package com.acme.logistics.contract;

import io.knotra.CapabilityKey;

import java.time.Duration;
import java.net.URI;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

public interface RoutePlanner {
    CompletionStage<Route> plan(Parcel parcel);
}

public interface RouteAdvisor {
    Optional<String> serviceClass(Parcel parcel);
}

public interface ParcelInbox extends AutoCloseable {
    ParcelSubscription consume(
            String warehouseId,
            Function<ParcelDelivery, CompletionStage<Void>> handler);
}

public interface ParcelSubscription {
    /** closeAsync 完成前，已经交给 handler 的包裹必须已经完成确认。 */
    CompletionStage<Void> closeAsync();
}

public record RouterConfig(URI ruleEndpoint, Duration timeout) {}

public final class LogisticsCapabilities {
    public static final CapabilityKey<RoutePlanner> ROUTE_PLANNER =
            CapabilityKey.of("logistics.route-planner", RoutePlanner.class);

    public static final CapabilityKey<RouteAdvisor> ROUTE_ADVISOR =
            CapabilityKey.of("logistics.route-advisor", RouteAdvisor.class);

    public static final CapabilityKey<ParcelInbox> PARCEL_INBOX =
            CapabilityKey.of("logistics.parcel-inbox", ParcelInbox.class);
}
```

`CapabilityKey` 的身份由稳定名称和精确 Java 类型组成。插件私下打包同名接口不会被当作同一个合约；`knotra-pf4j` 会在共享合约包校验中拒绝。

`ParcelInbox` 由宿主创建和拥有。Knotra 只传递这个 Capability；撤销 registration 不会自动关闭队列连接。

## 业务代码先保持普通 POJO

路由实现和队列消费者不依赖 Knotra：

```java
public final class HttpRoutePlanner implements RoutePlanner, AutoCloseable {
    private final RouterConfig config;

    public HttpRoutePlanner(RouterConfig config) {
        this.config = config;
    }

    @Override
    public CompletionStage<Route> plan(Parcel parcel) {
        // 调用规则服务并返回路由结果；内部实现省略。
    }

    void warmUp() throws Exception {
        // 建立首批连接或预热规则缓存；失败应抛出异常。
    }

    @Override
    public void close() {
        // 只释放 HttpRoutePlanner 自己创建的客户端和线程。
    }
}

public final class ParcelDispatcher implements DispatchControl {
    private final String warehouseId;
    private final RoutePlanner planner;
    private final ParcelInbox inbox;
    private final Optional<RouteAdvisor> advisor;
    private ParcelSubscription subscription;

    public ParcelDispatcher(
            String warehouseId,
            RoutePlanner planner,
            ParcelInbox inbox,
            Optional<RouteAdvisor> advisor) {
        this.warehouseId = warehouseId;
        this.planner = planner;
        this.inbox = inbox;
        this.advisor = advisor;
    }

    void start() {
        subscription = inbox.consume(warehouseId, delivery ->
                planner.plan(delivery.parcel())
                        .thenCompose(delivery::complete));
    }

    CompletionStage<Void> stopAsync() {
        return subscription.closeAsync();
    }

    @Override
    public DispatchStatus status() {
        // 返回只读状态；实现省略。
    }
}
```

这段代码可以单独单测：传入 fake planner、fake inbox 和空的 `Optional<RouteAdvisor>`，直接调用 `start()` 和 `stopAsync()`。构造器注入是业务代码的常态；Knotra 概念留在装配层。

## 用 Beans 声明依赖、输出和生命周期

`knotra-beans` 把 POJO 构造成 `ComponentFactory`。路由组件是 provider：

```java
private static BeanDefinition<RouterConfig, HttpRoutePlanner> routerFactory() {
    return Beans.component("regional-route-planner", RouterConfig.class)
            .create(HttpRoutePlanner::new)
            .normalizeConfig(config -> {
                if (config.ruleEndpoint() == null || config.timeout() == null) {
                    throw new IllegalArgumentException(
                            "ruleEndpoint and timeout are required");
                }
                return new RouterConfig(
                        config.ruleEndpoint(),
                        config.timeout().truncatedTo(ChronoUnit.SECONDS));
            })
            .initializer(HttpRoutePlanner::warmUp)
            .provide(ROUTE_PLANNER)
            .build();
}
```

dispatcher 是 consumer。required 依赖直接传值，optional 依赖传 `Optional<T>`；两个输出会一起暂存、一起提交：

```java
public record DispatcherConfig(String warehouseId) {}

private static BeanDefinition<DispatcherConfig, ParcelDispatcher> dispatcherFactory() {
    return Beans.component("parcel-dispatcher", DispatcherConfig.class)
            .with(
                    Beans.required(ROUTE_PLANNER),
                    Beans.required(PARCEL_INBOX),
                    Beans.optional(ROUTE_ADVISOR))
            .create((config, planner, inbox, advisor) -> new ParcelDispatcher(
                    config.warehouseId(),
                    planner,
                    inbox,
                    advisor))
            .initializer(ParcelDispatcher::start)
            .destroyAsyncWith(ParcelDispatcher::stopAsync)
            .provide(DISPATCH_CONTROL)
            .provideAs(DISPATCH_STATUS, ParcelDispatcher::status)
            .build();
}
```

每次 Activation 的顺序是：

```text
normalize config
-> resolve pinned dependencies
-> create a fresh POJO
-> register bean cleanup
-> run initializer
-> resolve all outputs
-> commit outputs atomically
```

因此 `warmUp()` 或任一输出映射失败时，本次 POJO 会被清理，其他输出也不可见。`HttpRoutePlanner` 满足 `AutoCloseable`，默认 AUTO 策略会登记 close；dispatcher 没有实现 Knotra 的关闭接口，而是在装配层显式指定 `stopAsync()`，业务类仍然不需要导入 Core 类型。

`BeanDefinition` 和它创建的 Component 外壳可以跨 Activation 复用，但它们不保存当前 POJO、依赖或 `ActivationContext`。业务对象每次 Activation 新建。

## 在 Root 装配并等待稳定

先在 Root Context 跑通一条链路。宿主先提供队列入口，再挂路由和 dispatcher：

```java
try (ParcelInbox inbox = openDurableInbox();
     KnotraRuntime runtime = KnotraRuntime.create()) {

    runtime.provide(PARCEL_INBOX, inbox);

    ComponentHandle<RouterConfig> router = runtime.mount(
            "route-planner",
            routerFactory(),
            new RouterConfig(URI.create("https://rules.example"), Duration.ofSeconds(3)));

    ComponentHandle<DispatcherConfig> dispatcher = runtime.mount(
            "parcel-dispatcher",
            dispatcherFactory(),
            new DispatcherConfig("shanghai"));

    requireActive(router);
    requireActive(dispatcher);

    // 进程继续运行。
}

private static void requireActive(ComponentHandle<?> handle) throws Exception {
    ComponentState state = handle.whenSettled()
            .toCompletableFuture()
            .get(30, TimeUnit.SECONDS);
    if (state != ComponentState.ACTIVE) {
        throw new IllegalStateException(
                handle.mountId() + " settled as " + state);
    }
}
```

`mount()` 返回时只表示结构事务已提交，`start()` 仍在 Runtime 的 Activation 线程上执行。必须等待 `whenSettled()` 并确认状态是 `ACTIVE`。

try-with-resources 按声明逆序关闭，所以上面的顺序会先关闭 Runtime，再关闭宿主拥有的 `ParcelInbox`。这符合“组件先停止，队列连接最后释放”的方向。

## 原子替换 provider，下游 pinned 重激活

pinned 是默认语义。dispatcher 的 BindingSet 记录它启动时绑定的 `ROUTE_PLANNER` registration；provider 变化时，旧 dispatcher 先排空并关闭，然后新 dispatcher 用新绑定启动。

同一个 mount 槽位可以在一个结构事务中替换：

```java
TransactionReceipt<ComponentHandle<RouterConfig>> replaced = runtime.transact(tx -> {
    tx.dispose(router);
    return tx.mount(
            runtime.root(),
            "route-planner",
            routerFactory(),
            new RouterConfig(
                    URI.create("https://rules-v2.example"),
                    Duration.ofSeconds(2)));
});

router = replaced.value();
requireActive(router);
requireActive(dispatcher);
```

事务只发布一个新 generation。等待 `dispatcher.whenSettled()` 时，旧 Activation 已完成订阅排空和资源清理，新 Activation 已绑定新 provider。不要把 `TransactionReceipt.settlement()` 当作全局静默信号；观察具体组件时使用该组件的 handle。

同一 handle 的旧新 Activation 串行执行，不并行共存。需要真正蓝绿并行时，必须在 Knotra 外设计双实例和流量路由协议。

## 配置变化保留 handle

规则服务地址或超时变化不需要替换工厂：

```java
ComponentState state = router.reconfigureAsync(
                new RouterConfig(
                        URI.create("https://rules.example"),
                        Duration.ofSeconds(5)))
        .toCompletableFuture()
        .get(30, TimeUnit.SECONDS);

if (state != ComponentState.ACTIVE) {
    runtime.snapshot().diagnostics().forEach(System.err::println);
}
```

`reconfigureAsync()` 保留同一个 `ComponentHandle`，增加配置 revision，并创建新 Activation。新配置先经过 normalizer；旧 planner 关闭后，新 planner 才启动。`ROUTE_PLANNER` 的新 registration 又会让 pinned dispatcher 重激活。

依赖集合是定义的一部分，不能随某次配置值增减。若新配置需要不同依赖图，应替换整个 Factory；若只是“可缺失的增强”，从一开始声明 optional，creator 收到 `Optional<T>`。

## 按仓库隔离和遮蔽

Root 放全国默认路由，每个仓库一个子 Context。下面的事务从一个空 Runtime 开始；若沿用上一节的 root-only 试验，应先释放旧结构，或把两段启动流程合并成一个事务。上海本地路由挂在自己的 Context 后，会遮蔽父级同名 Capability；本地 registration 撤销后，父级默认值重新可见。深圳没有本地路由，始终继承 Root 默认值。

```java
record SiteTopology(
        ContextHandle shanghai,
        ContextHandle shenzhen,
        ComponentHandle<RouterConfig> defaultRouter,
        ComponentHandle<RouterConfig> shanghaiRouter,
        ComponentHandle<DispatcherConfig> shanghaiDispatcher,
        ComponentHandle<DispatcherConfig> shenzhenDispatcher) {}

SiteTopology topology = runtime.transact(tx -> {
    ContextHandle root = runtime.root();
    ContextHandle shanghai = tx.childContext(root, "warehouse-shanghai");
    ContextHandle shenzhen = tx.childContext(root, "warehouse-shenzhen");

    tx.provide(root, PARCEL_INBOX, inbox);

    ComponentHandle<RouterConfig> defaultRouter = tx.mount(
            root, "route-planner", routerFactory(), defaultRouterConfig());

    ComponentHandle<RouterConfig> shanghaiRouter = tx.mount(
            shanghai, "route-planner", routerFactory(), shanghaiRouterConfigV1());

    ComponentHandle<DispatcherConfig> shanghaiDispatcher = tx.mount(
            shanghai,
            "parcel-dispatcher",
            dispatcherFactory(),
            new DispatcherConfig("shanghai"));

    ComponentHandle<DispatcherConfig> shenzhenDispatcher = tx.mount(
            shenzhen,
            "parcel-dispatcher",
            dispatcherFactory(),
            new DispatcherConfig("shenzhen"));

    return new SiteTopology(
            shanghai,
            shenzhen,
            defaultRouter,
            shanghaiRouter,
            shanghaiDispatcher,
            shenzhenDispatcher);
}).value();
```

Context 只定义能力可见性，不表示线程池或 `ClassLoader` 隔离。上海和深圳可以使用同名 mountId，因为挂载身份是 contextId 加 mountId。

本地路由消失时，上海 dispatcher 会按最新 generation 收敛到 Root 默认路由。收敛过程可能让基于默认值的候选 Activation 在提交前变 stale；stale 候选会被回滚，不算业务启动失败。

## 什么时候改成 Dynamic

pinned 适合 dispatcher 这种长期订阅：一代 Activation 内所有包裹都落在同一个 provider 上，provider 变化时整个 dispatcher 重建。

Dynamic 适合无状态单调用，例如一个只调用 `plan(parcel)` 的查询面板：

```java
public record BoardConfig(String warehouseId) {}

BeanDefinition<BoardConfig, DispatchBoard> boardFactory =
        Beans.component("dispatch-board", BoardConfig.class)
                .with(Beans.dynamicRequired(ROUTE_PLANNER))
                .create(DispatchBoard::new)
                .provide(DISPATCH_BOARD)
                .build();
```

`DispatchBoard` 的构造器仍然接收 `RoutePlanner`，但这个对象是方法级 proxy。每次 `plan()`：

```text
原子获取 consumer/provider 租约
-> 固定到一个已提交 provider
-> 执行一次方法
-> 同步返回或返回的 CompletionStage 完成后释放租约
```

provider 替换或撤销时，旧 provider 先停止接收新租约，cleanup 等待在途租约归零。consumer 关闭时同样先关闭调用闸门，等待已开始的调用结束，再执行 LifecycleScope 清理。因此旧对象清理后不会再被动态入口调用。

边界也很明确：

- 连续两个 proxy 方法可能落在不同 provider 代际；
- `available()` 只是 advisory，不持有租约；
- callback 返回的连接、session 或内部句柄如果逃逸，Knotra 无法追踪；
- 多方法一致性必须使用一次 `call` 或 `callAsync`。

如果路由过程需要多个方法落在同一个 provider 上，注入显式入口：

```java
BeanDefinition<NoConfig, RoutingService> factory =
        Beans.component("routing-service")
                .with(Beans.dynamicCapabilityRequired(ROUTE_PLANNER))
                .create(RoutingService::new)
                .provide(ROUTING_SERVICE)
                .build();

Route route = routingService.planner().call(planner -> {
    RoutingSession session = planner.begin();
    session.selectCarrier(parcel);
    return session.commit();
});
```

一次 `call` 固定同一个 provider；`callAsync` 的租约会保持到返回的 stage 完成，无论成功或失败。不要用两个连续 proxy 方法模拟事务。

## EventBus 承担已接受分发的收敛

`knotra-events` 适合路由完成后的进程内通知，例如刷新运营面板或指标。先把总线作为普通 Capability 挂到 Root：

```java
ComponentHandle<NoConfig> eventBus = runtime.mount(
        "event-bus", new EventBusFactory());
requireActive(eventBus);
```

定义 serial 事件：

```java
static final EventDefinition.Serial<RouteFinished> ROUTE_FINISHED =
        EventDefinition.serial("logistics.route-finished", RouteFinished.class);
```

生产版 dispatcher 定义增加 required `EVENT_BUS`，POJO 在 `start()` 中订阅，在 `stopAsync()` 中与队列订阅一起关闭：

```java
.with(
        Beans.required(ROUTE_PLANNER),
        Beans.required(PARCEL_INBOX),
        Beans.optional(ROUTE_ADVISOR),
        Beans.required(EventCapabilities.EVENT_BUS))
.create((config, planner, inbox, advisor, bus) ->
        new ParcelDispatcher(config.warehouseId(), planner, inbox, advisor, bus))
```

`ParcelDispatcher` 可以在路由确认后 dispatch `ROUTE_FINISHED`。EventBus 的关闭契约是：关闭请求之前的已接受 dispatch 会执行完成，关闭后的新 dispatch 被拒绝。组件换代、provider 排空或 artifact 卸载会等待这些已接受回调。

EventBus 不是持久队列。包裹任务的可靠性仍由 `ParcelInbox` 和消息系统保证；事件丢失或进程崩溃后的补投不能靠进程内总线推导。

## 把路由 provider 装进 PF4J artifact

当上海规则从“改配置”变成“换整版算法”时，把实现打成插件 JAR。插件通过 SPI 导出类型化工厂；`BeanDefinition` 本身就是 `ComponentFactory`：

```java
@Extension
public final class RegionalRouterProvider implements RuntimeComponentProvider {
    @Override
    public Collection<ExportedComponentFactory<?>> factories() {
        return List.of(ExportedComponentFactory.of(
                RouterConfig.class,
                RouterConfigDecoder::decode,
                routerFactory()));
    }
}
```

`RouterConfig.class` 与 `RoutePlanner.class` 必须来自共享合约包；decoder 可以在插件中实现，但输出必须精确为共享的 `RouterConfig` 类型。加载前先创建 adapter，并把共享包名交给它：

```java
try (Pf4jArtifactAdapter adapter = Pf4jArtifactAdapter.create(
        Path.of("plugins"),
        runtime,
        Set.of("com.acme.logistics.contract"))) {

    ArtifactSnapshot v1 = adapter.loadArtifact(
            Path.of("plugins/regional-router-v1.jar"));

    ArtifactFactoryHandle<RouterConfig> routerV1 = adapter.factories()
            .resolve("regional-route-planner", RouterConfig.class)
            .orElseThrow();

    ComponentHandle<RouterConfig> shanghaiRouter = routerV1.mount(
            topology.shanghai(),
            "route-planner",
            shanghaiRouterConfigV1());

    requireActive(shanghaiRouter);

    List<ArtifactOwnership> owned = adapter.ownership(v1.artifactId());
}
```

`loadArtifact()` 只加载插件并登记工厂目录，不会隐式挂载业务组件。每次显式 mount 都记录在 ownership 中；drain 时 adapter 会释放这些 owned handle，再 stop 和 unload PF4J 插件。

## 用 Loader 表达嵌套期望树

当仓库、默认路由和 dispatcher 的组合来自配置中心时，用 `KnotraLoader` 表达完整期望状态。classpath resolver 提供默认路由和 dispatcher，PF4J bridge 提供插件路由：

```java
FactoryRef DEFAULT_ROUTER = FactoryRef.of("default-route-planner");
FactoryRef DISPATCHER = FactoryRef.of("parcel-dispatcher");
FactoryRef LOCAL_V1 = FactoryRef.of("regional-route-planner", "1.0.0");

ClasspathFactoryResolver classpath = ClasspathFactoryResolver.builder()
        .add(DEFAULT_ROUTER, routerFactory(), ConfigDecoder.typed(RouterConfig.class))
        .add(DISPATCHER, dispatcherFactory(), ConfigDecoder.typed(DispatcherConfig.class))
        .build();

ComponentFactoryResolver resolver =
        Pf4jFactoryResolver.withFallbacks(adapter, classpath);
```

期望树的父子关系就是 Context 继承关系。`network` 挂默认路由；`shanghai` 挂本地路由，其子 dispatcher 看到本地值遮蔽后的结果；`shenzhen` 直接继承 `network` 默认值：

```java
ComponentTree desiredV1 = ComponentTree.of(
        ComponentEntry.configured(
                "network",
                DEFAULT_ROUTER,
                defaultRouterConfig(),
                ComponentEntry.configured(
                        "shanghai",
                        LOCAL_V1,
                        shanghaiRouterConfigV1(),
                        ComponentEntry.configured(
                                "dispatcher",
                                DISPATCHER,
                                new DispatcherConfig("shanghai"))),
                ComponentEntry.configured(
                        "shenzhen",
                        DISPATCHER,
                        new DispatcherConfig("shenzhen"))));

try (KnotraLoader loader = KnotraLoader.over(runtime, runtime.root(), resolver)) {
    ReconcileResult result = loader.reconcile(desiredV1);
    result.requireConverged();
}
```

Loader 为每个路径分配专属 Context 和 mountId，只使用 resolver 返回的受控挂载策略。每次 `reconcile()` 都提交完整期望树：

- 缺失路径补挂；
- 不再期望的路径释放；
- 实现身份变化时替换 handle；
- 身份不变且配置变化时 reconfigure；
- mount 事务或受控挂载被拒绝时，按 LIFO 补偿本批此前新增；mount 已提交但 `start()` 失败时保留 `FAILED` entry，等待显式 retry；
- FAILED 条目不自动重试。

`converged` 表示期望树无剩余差异且无诊断，不承诺所有组件都是 `ACTIVE`。required provider 缺失导致的 `WAITING` 可以是合法收敛状态。

## v1 排空卸载后再加载 v2

两个活跃 artifact 不能提供同一个 factoryId。升级同 factoryId 的插件必须先完成 v1 drain 和 unload，再加载 v2。

为了不让上海包裹在升级窗口失去 provider，先把上海路径切回默认路由，再卸载 v1：

```java
private static ComponentTree desiredWith(FactoryRef shanghaiRouter) {
    return ComponentTree.of(
            ComponentEntry.configured(
                    "network",
                    DEFAULT_ROUTER,
                    defaultRouterConfig(),
                    ComponentEntry.configured(
                            "shanghai",
                            shanghaiRouter,
                            shanghaiRouterConfig(),
                            ComponentEntry.configured(
                                    "dispatcher",
                                    DISPATCHER,
                                    new DispatcherConfig("shanghai"))),
                    ComponentEntry.configured(
                            "shenzhen",
                            DISPATCHER,
                            new DispatcherConfig("shenzhen"))));
}

loader.reconcile(desiredWith(DEFAULT_ROUTER)).requireConverged();

adapter.unloadArtifactAsync(v1.artifactId())
        .toCompletableFuture()
        .get(30, TimeUnit.SECONDS);

ArtifactSnapshot v2 = adapter.loadArtifact(
        Path.of("plugins/regional-router-v2.jar"));

FactoryRef LOCAL_V2 = FactoryRef.of("regional-route-planner", "2.0.0");
loader.reconcile(desiredWith(LOCAL_V2)).requireConverged();
```

`unloadArtifact()` 的 drain 顺序是：

```text
拒绝该 artifact 的新 mount
-> 等待 in-flight mount 提交或回滚
-> 先释放依赖方和 owned mount
-> stop 并 unload PF4J 插件
-> 释放 catalog、factory 和 ClassLoader 引用
```

成功后 v1 是 `UNLOADED`，v2 才能复用 `regional-route-planner` 这个 factoryId。不能把这段流程描述成 v1 和 v2 两个同 factoryId artifact 并存再由 resolver 选择版本。若确实需要两版本并存做灰度，应给它们不同 factoryId，并显式设计流量边界。

## 失败、诊断和重试

不同失败停在不同层级：

| 失败 | 可观察位置 | 恢复 |
|---|---|---|
| creator、initializer 或输出失败 | component `FAILED`，输出不可见，已登记 cleanup 回滚 | `handle.retryAsync()` |
| lifecycle 清理失败 | component `FAILED`，失败 entry 保留 | 修复资源后 `handle.retryAsync()`，只重试失败 entry |
| REQUIRED provider 缺失 | component `WAITING`，goal 仍是运行 | 发布可见 provider 后自动调度 |
| 配置 null、类型错误或 normalizer 拒绝 | `TransactionRejectedException`，结构事务整体拒绝 | 修正配置后重新提交 |
| Loader mount 事务或受控挂载被拒绝 | 本批此前新增按 LIFO 补偿，结果携带路径诊断 | 修正 desired tree 或工厂后再次 reconcile |
| Loader 条目 start 失败 | `loader.snapshot()` 中该路径 `FAILED` | `loader.retry(path).requireConverged()` |
| PF4J drain 失败 | artifact `DRAIN_FAILED`，ownership 和诊断保留 | `adapter.retryDrain(artifactId)` |

诊断来源也分层：

```java
runtime.snapshot().diagnostics();
loader.snapshot();
adapter.artifact(artifactId);
adapter.diagnostic(artifactId);
```

`RuntimeSnapshot` 是纯数据，不引用存活组件实例、factory、disposer、`Class` 或 `ClassLoader`；长期保存不会钉住已卸载插件。`ArtifactSnapshot` 同样不持 live 对象。清空失败现场会让问题不可诊断，Knotra 的策略是保留状态并允许显式重试。

## 升级期间一个包裹的结果

- **关闭前已经交给旧 handler**：旧订阅的 `closeAsync()` 等待它完成确认；旧 dispatcher 清理完成后，旧路由 provider 才清理自己的客户端和线程。
- **尚未交给 handler**：消息仍在持久队列中，等下一代订阅继续获取。
- **上海切到默认路由期间**：dispatcher 绑定 Root 或 `network` 中的默认 `RoutePlanner`，包裹按默认规则处理。
- **v2 发布后**：本地 capability 再次遮蔽默认值，dispatcher 以新 BindingSet 启动，后续包裹使用 v2。
- **v2 start 失败**：半初始化输出不发布；上海可以停留在默认路由。如果上海使用的是 required 且没有可见 fallback，dispatcher 保持 `WAITING`，包裹留在队列。

这保证一个 Activation 内不会混用 v1 与 v2，但不等于零停机。严格无缝迁移需要外部双实例、请求级版本路由或负载均衡协议；单个 ComponentHandle 的设计目标是避免旧新 Activation 重叠。

## Spring 作为可选模块边界

如果一组路由相关对象已经是 Spring Bean，可以用 `knotra-spring` 把整个 child context 作为一个 Knotra Component：

```java
ComponentFactory<NoConfig> dispatchModule =
        SpringModules.noConfig("dispatch-spring")
                .annotatedClasses(DispatchSpringConfig.class)
                .required("routePlanner", ROUTE_PLANNER)
                .required("inbox", PARCEL_INBOX)
                .optionalAsOptional("advisor", ROUTE_ADVISOR)
                .expose(DISPATCH_CONTROL, "dispatchControl")
                .build();
```

每次 Activation 创建一个新的 Spring child context；pinned provider 或配置变化时整个 context 重建。外部 Capability 通过 Spring external singleton registry 注入，Spring 不会销毁这些借入对象。宿主 Spring singleton 需要长期持有动态 interface 时，使用 `SpringDynamicBridge` 及其同样的调用租约语义。

Spring 是可选集成，不是业务接入 Knotra 的前提。普通对象图优先使用 `knotra-beans`；只有当模块内部已经依赖 Spring 生命周期和配置模型时，才引入 child context。

## 适用和不适用

适合：

- 规则引擎、插件实现、外部客户端等 provider 需要运行期替换；
- 多仓库或多租户需要 capability 可见性和本地遮蔽；
- 消费方订阅、线程池、HTTP 连接和事件监听需要与 provider 代际一起排空；
- 插件升级必须回答 ownership、drain、unload 和失败重试；
- 期望结构来自配置中心，需要声明式 reconcile。

不适合：

- 组件集合固定，重启进程即可解决配置变化；
- 只需要一次性构建对象图，不需要运行期替换；
- 只希望依赖类路径扫描，不接受显式装配；
- 要求框架内置消息持久化、定时任务或全局事务管理；
- 已有强引用逃逸到未托管代码，却希望框架自动回收插件 ClassLoader。

## 最小采用步骤

1. 选一个真实需要替换的 provider 和一个直接 consumer，用普通 POJO 加 `knotra-beans` 跑通 root 链路。
2. 为 consumer 写重激活测试：替换 provider、撤回 provider、optional 出现和消失。
3. 把所有 Activation 创建的连接、订阅和线程纳入 AUTO 或自定义 disposer，并测试清理失败后的 retry。
4. 引入仓库 Context，测试本地遮蔽、撤销后 fallback 和 start 失败。
5. 只给无状态单调用加 dynamic；多方法操作使用 `call` 或 `callAsync`。
6. 用 EventBus 处理进程内通知，把持久任务入口继续留给消息队列。
7. 把真正按 JAR 发布的实现移入 PF4J artifact，显式导出类型化 factory。
8. 用 Loader 管理仓库期望树，并把 v1 unload、v2 load 和 fallback 纳入升级验收。
9. wiring 稳定后，再把适合固定契约的 Bean 改为编译期生成的 `*_KnotraFactory`。
10. 最后评估 Spring child context 或宿主 bridge，只在现有 Spring 边界明确需要时引入。

验证当前仓库：

```bash
mvn clean verify
```

## 延伸阅读

- [Knotra API 与集成指南](<Knotra API 与集成指南.md>)
- [Knotra Beans 与 Spring 集成指南](<Knotra Beans 与 Spring 集成指南.md>)
- [Knotra 插件工程化手册](<Knotra 插件工程化手册.md>)
- [Knotra 线程模型与生产实践](<Knotra 线程模型与生产实践.md>)
- [Knotra 测试指南](<Knotra 测试指南.md>)
- [Knotra FAQ 与排障指南](<Knotra FAQ 与排障指南.md>)
