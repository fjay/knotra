# Knotra Beans 与 Spring 集成指南

本文描述当前 `0.1.0-SNAPSHOT` 的三个官方装配模块：

- `knotra-beans`：把普通 Java POJO 构造成 Activation 拥有的 Bean。
- `knotra-beans-processor`：在编译期生成无反射 `ComponentFactory`。
- `knotra-spring`：为每个 Activation 创建 Spring child context，并提供宿主动态 bridge。

Knotra Core 的 Context、Capability、固定 BindingSet、Activation、Lifecycle 和事务语义保持不变。这些模块只减少装配样板，不引入第二套运行时容器。

## 模块与 Maven

项目要求 Java 21+、Maven 3.9+。版本尚未发布到 Maven Central，当前使用本地或内部仓库中的 `0.1.0-SNAPSHOT`。

| 模块 | 引入时机 | 运行依赖 |
|---|---|---|
| `knotra-beans` | 手写 POJO DSL，或使用生成的 Factory | `knotra-core` |
| `knotra-beans-processor` | 编译期注解处理 | 编译期使用，应用运行时不需要 |
| `knotra-spring` | Spring child context 或宿主动态 bridge | `knotra-core`、`spring-context` |

手写 DSL 只需要：

```xml
<dependency>
  <groupId>io.knotra</groupId>
  <artifactId>knotra-beans</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

使用注解生成时，应用保留 `knotra-beans`，并把 processor 放入 compiler plugin 的 processor path：

```xml
<dependency>
  <groupId>io.knotra</groupId>
  <artifactId>knotra-beans</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>

<build>
  <plugins>
    <plugin>
      <groupId>org.apache.maven.plugins</groupId>
      <artifactId>maven-compiler-plugin</artifactId>
      <configuration>
        <proc>full</proc>
        <annotationProcessorPaths>
          <path>
            <groupId>io.knotra</groupId>
            <artifactId>knotra-beans-processor</artifactId>
            <version>0.1.0-SNAPSHOT</version>
          </path>
        </annotationProcessorPaths>
      </configuration>
    </plugin>
  </plugins>
</build>
```

Spring child context 或 bridge 使用：

```xml
<dependency>
  <groupId>io.knotra</groupId>
  <artifactId>knotra-spring</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

## 最小无配置 POJO

业务类保持普通 Java 对象形状，可以实现业务接口和 `AutoCloseable`，但不依赖 Knotra API：

```java
public interface Greeting {
    String greet(String name);
}

public final class GreetingBean implements Greeting, AutoCloseable {
    @Override
    public String greet(String name) {
        return "hello, " + name;
    }

    @Override
    public void close() {
        // 只释放 GreetingBean 自己拥有的资源。
    }
}
```

Capability 和组件定义只出现在应用装配层：

```java
static final CapabilityKey<Greeting> GREETING =
        CapabilityKey.of("app.greeting", Greeting.class);

BeanDefinition<NoConfig, GreetingBean> factory =
        Beans.component("greeting")
                .create(GreetingBean::new)
                .provide(GREETING)
                .build();

ComponentHandle<NoConfig> handle =
        runtime.mount("greeting", factory);
```

每次 Activation 的执行顺序固定：

```text
归一化配置
-> 解析本代依赖
-> creator 创建新 Bean
-> 立即登记 Bean cleanup
-> 执行 initializer
-> 解析并暂存全部输出
-> Core 原子提交输出
```

`BeanDefinition` 和由它创建的 Component 外壳可以跨 Activation 复用，但不保存 Bean、依赖、输出值或 `ActivationContext`。业务 Bean 必须每次 `start()` 新建。creator 返回 `null` 时本次 Activation 失败。

## Required 与 Optional

Required pinned 依赖在本次 Activation 内固定：

```java
static final CapabilityKey<UserRepository> USERS =
        CapabilityKey.of("app.users", UserRepository.class);
static final CapabilityKey<Metrics> METRICS =
        CapabilityKey.of("app.metrics", Metrics.class);

BeanDefinition<NoConfig, UserService> factory =
        Beans.component("user-service")
                .with(Beans.required(USERS))
                .create(UserService::new)
                .provide(USER_SERVICE)
                .build();
```

Optional pinned 依赖传给 creator 的是 `Optional<T>`，缺失时为空，不使用 `null`：

```java
BeanDefinition<NoConfig, UserService> factory =
        Beans.component("user-service")
                .with(
                        Beans.required(USERS),
                        Beans.optional(METRICS))
                .create(UserService::new)
                .provide(USER_SERVICE)
                .build();
```

`UserService` 的构造器相应为：

```java
public UserService(
        UserRepository users,
        Optional<Metrics> metrics) {
    // ...
}
```

依赖 token 与 creator 参数由泛型连接：

| Token | Creator 收到 | Provider 变化 |
|---|---|---|
| `Beans.required(KEY)` | `T` | REQUIRED pinned：启动等待；ACTIVE 后重激活 |
| `Beans.optional(KEY)` | `Optional<T>` | OPTIONAL pinned：可启动；出现、消失或替换都重激活 |
| `Beans.dynamicRequired(KEY)` | `T` 方法级 proxy | 首次启动要求存在；之后 consumer 不重启 |
| `Beans.dynamicOptional(KEY)` | `T` 方法级 proxy | 可缺失启动；之后 consumer 不重启 |
| `Beans.dynamicCapabilityRequired(KEY)` | `DynamicCapability<T>` | 首次启动要求存在；之后 consumer 不重启 |
| `Beans.dynamicCapabilityOptional(KEY)` | `DynamicCapability<T>` | 可缺失启动；之后 consumer 不重启 |

`with(...)` 和 `create(...)` 支持 0 到 5 个依赖。无配置 creator 形状为 `create(d1, ..., dn)`；类型化配置 creator 形状始终是 `create(config, d1, ..., dn)`。builder 支持分批追加依赖：

```java
BeanDefinition<NoConfig, OrderService> factory =
        Beans.component("order-service")
                .with(
                        Beans.required(USER_REPOSITORY),
                        Beans.optional(METRICS))
                .with(Beans.dynamicRequired(PAYMENT_GATEWAY))
                .create((users, metrics, payment) ->
                        new OrderService(users, metrics, payment))
                .provide(ORDER_SERVICE)
                .build();
```

不要用 `CapabilityKey<?>...` 加运行时下标取值；这不是 Knotra 的类型安全路径。

## 类型化配置

配置类型在定义时冻结：

```java
public record CacheConfig(
        long maximumSize,
        Duration expireAfterWrite) {}

static final CapabilityKey<CacheStore> CACHE_STORE =
        CapabilityKey.of("app.cache-store", CacheStore.class);

BeanDefinition<CacheConfig, CacheStore> factory =
        Beans.component("cache", CacheConfig.class)
                .with(Beans.required(STORAGE))
                .create((config, storage) -> new LocalCacheStore(
                        config,
                        storage,
                        storage.metrics()))
                .normalizeConfig(config -> new CacheConfig(
                        config.maximumSize(),
                        config.expireAfterWrite().truncatedTo(ChronoUnit.SECONDS)))
                .provide(CACHE_STORE)
                .build();

ComponentHandle<CacheConfig> cache = runtime.mount(
        "cache",
        factory,
        new CacheConfig(10_000, Duration.ofMinutes(5)));
```

typed creator 的参数顺序固定为：

```text
config, d1, d2, d3, d4, d5
```

同一 `ComponentHandle` 可以重新配置：

```java
cache.reconfigureAsync(new CacheConfig(20_000, Duration.ofMinutes(10)))
        .toCompletableFuture()
        .get(30, TimeUnit.SECONDS);
```

reconfigure 会先归一化新配置，再关闭旧 Bean 和旧输出，最后创建新一代 Bean。依赖集合是静态 descriptor 的一部分，不能随某次配置值动态增减；需要不同依赖图时应替换整个 Factory。

配置为 `null`、raw 配置类型不匹配、normalizer 抛错、返回 `null` 或返回错误类型时，事务以 `INVALID_CONFIG` 拒绝，不会进入 Activation。

## Initializer 与输出

initializer 在 cleanup 登记之后、输出提交之前执行：

```java
BeanDefinition<NoConfig, HttpClient> factory =
        Beans.component("http-client")
                .with(Beans.required(HTTP_CONFIG))
                .create(HttpClient::new)
                .initializer(HttpClient::warmUp)
                .provide(HTTP_CLIENT)
                .build();
```

initializer 抛出任何异常都会回滚本次 Activation，并执行已登记的 Bean cleanup。

单输出可以直接发布 Bean 本身：

```java
.provide(USER_SERVICE)
```

多输出可以从同一个 Bean 映射多个 Capability：

```java
BeanDefinition<NoConfig, InfrastructureModule> factory =
        Beans.component("infrastructure")
                .with(Beans.required(DATABASE))
                .create(InfrastructureModule::new)
                .provide(INFRASTRUCTURE)
                .provideAs(USER_REPOSITORY, module -> module.users())
                .provideAs(ORDER_REPOSITORY, module -> module.orders())
                .build();
```

输出契约：

- `provide(KEY)` 要求 Bean 可赋值给 `KEY.type()`；不匹配会在 Activation 中失败。
- `provideAs(KEY, mapper)` 由 mapper 产生输出值，mapper 不能为 `null`。
- creator 返回 `null`、任一输出 mapper 返回 `null` 或输出类型不匹配，都会使本次 Activation 失败。
- 同一定义中重复输出 Capability 名称会在构建时抛 `IllegalArgumentException`。
- 多个输出先全部解析并暂存，再统一提交；任一输出失败，其他输出也不可见。
- 零输出定义是合法的，适合只产生副作用或只登记 lifecycle 的 Component。

## 生命周期

默认策略是 AUTO：

```text
AsyncCloseable -> LifecycleScope.manageAsync
AutoCloseable  -> LifecycleScope.manage
其他对象       -> 不登记 cleanup
```

`AsyncCloseable` 优先于 `AutoCloseable`。它表示 Knotra 会等待 `closeAsync()` 返回的 stage 完成；业务对象自己仍必须实现“拒绝新工作并排空已接受工作”的契约。

自定义同步清理：

```java
BeanDefinition<NoConfig, LegacyClient> factory =
        Beans.component("legacy-client")
                .with(Beans.required(LEGACY_CONFIG))
                .create(LegacyClient::new)
                .destroyWith(LegacyClient::shutdown)
                .provide(LEGACY_CLIENT)
                .build();
```

自定义异步清理：

```java
BeanDefinition<NoConfig, AsyncClient> factory =
        Beans.component("async-client")
                .with(Beans.required(CLIENT_CONFIG))
                .create(AsyncClient::new)
                .destroyAsyncWith(AsyncClient::drainAndClose)
                .provide(ASYNC_CLIENT)
                .build();
```

自定义清理失败时组件保持 `FAILED`，随后 `retryAsync()` 只重试失败的 lifecycle entry。同步 disposer 和异步 disposer 都应幂等且可重试；异步 disposer 返回异常完成的 stage 也表示可重试。

生命周期策略后调用覆盖前调用：

```java
.destroyWith(Client::shutdown)
.unmanaged()
```

最终生效的是 `UNMANAGED`。DSL 允许这种链式覆盖；注解模式则没有等价链式语义。

### UNMANAGED 与所有权

```java
BeanDefinition<NoConfig, PooledExecutor> factory =
        Beans.component("worker")
                .create(PooledExecutor::new)
                .unmanaged()
                .build();
```

`unmanaged()` 表示 Knotra 不为本次 Activation 创建的 Bean 登记任何 cleanup。它只适合创建方已经把对象交给另一个可负责关闭的资源，或该对象确实没有需要执行的清理动作。

使用边界：

- 不建议 creator 返回跨 Activation 共享的 singleton。
- UNMANAGED 不改变“每次 Activation 新建 Bean”的设计意图。
- Activation 失败或关闭时，Knotra 不会调用该 Bean 的任何 close 或 destroy 方法。
- 宿主通过 `runtime.provide(KEY, value)` 发布的对象仍由宿主拥有；撤销 registration 不会自动关闭 value。
- 借入依赖不应该在 Bean cleanup 中关闭；只关闭 Bean 自己创建的资源。

`BeanLifecycles` 是公开工具类，提供与 DSL 相同的 `autoManage`、`manageSync` 和 `manageAsync` 语义，供框架集成或生成代码复用。

## Dynamic 依赖

默认依赖是 PINNED。provider 的 registration 变化时，Knotra 会关闭旧 Activation，并用新的固定依赖重建 consumer。

DYNAMIC 依赖适用于无状态调用或显式路由。consumer ACTIVE 后 provider 替换不重建 consumer，每次调用解析当前已提交 provider。

### 方法级 Proxy

```java
static final CapabilityKey<PaymentGateway> PAYMENT =
        CapabilityKey.of("app.payment", PaymentGateway.class);

BeanDefinition<NoConfig, CheckoutService> factory =
        Beans.component("checkout")
                .with(Beans.dynamicRequired(PAYMENT))
                .create(CheckoutService::new)
                .provide(CHECKOUT_SERVICE)
                .build();
```

`CheckoutService` 构造器收到的是 `PaymentGateway` proxy。每个 proxy 方法独立执行：

```text
原子获取 consumer/provider 调用租约
-> 固定到一个 provider
-> 执行一次方法
-> 同步返回后释放租约
```

如果方法返回 `CompletionStage`，租约会保持到 stage 完成。provider 替换或清理会等待旧租约归零；STARTING、stale 或未提交 registration 不会被调用。

连续两个 proxy 方法可能在中间发生 provider 替换，不能用于事务或多方法一致性问题。

### 显式 DynamicCapability

多个方法必须落在同一个 provider 时，注入 `DynamicCapability<T>`：

```java
BeanDefinition<NoConfig, PaymentService> factory =
        Beans.component("payment-service")
                .with(Beans.dynamicCapabilityRequired(PAYMENT))
                .create(PaymentService::new)
                .provide(PAYMENT_SERVICE)
                .build();
```

一次 `call` 固定一个 provider：

```java
payment.call(gateway -> {
    gateway.begin();
    gateway.charge(order);
    gateway.commit();
    return null;
});
```

异步版本：

```java
CompletionStage<Receipt> receipt =
        payment.callAsync(gateway -> gateway.chargeAsync(order));
```

`callAsync` 在执行 callback 前获取租约。callback 抛错时返回异常完成的 stage 并立即释放租约；callback 正常返回 stage 时，租约保持到该 stage 完成，组合 stage 抛错也会释放租约。

### 失败语义

- `CapabilityUnavailableException`：当前没有已提交且可用的 provider。`dynamicRequired` 首次启动需要 provider；ACTIVE 后 provider 消失时 consumer 不重启，后续调用得到该异常，provider 回来后继续使用。STARTING 期间 required provider 消失时，本次 Activation 回滚到 `WAITING`，不会误提交。
- `DynamicCapabilityClosedException`：consumer 或 dynamic capability 已关闭，之后不再接受新调用。
- `available()` 只是 advisory 快照。返回 `true` 不保证下一次调用一定成功；真正调用仍在准入 gate 内原子获取租约。调用代码应处理 unavailable 或 closed，不应把 `available()` 当作锁。

其他约束：

- `Beans.dynamicRequired/dynamicOptional` 注入 JDK proxy，因此 contract 必须是 Java interface；`dynamicCapabilityRequired/dynamicCapabilityOptional` 只注入显式调用入口，`call/callAsync` 本身不要求 contract 是 interface。
- `proxy(Class)` 只接受与 CapabilityKey 完全相同的 interface，不接受子接口。
- Proxy 的 `equals/hashCode/toString` 表达 proxy 自身身份，不转发给 provider。
- `call` 或 `callAsync` 回调返回的 provider、连接或内部句柄如果长期逃逸，Core 无法追踪或排空。
- DYNAMIC 边不进入固定 BindingSet 身份，但参与依赖图和环检测。

## BeanDefinition 与 Expert API

`build()` 返回不可变 `BeanDefinition<C, T>`，它同时实现 `ComponentFactory<C>`：

```java
definition.componentId();       // 显式稳定组件 ID
definition.factoryId();         // 与 componentId 相同
definition.configType();        // NoConfig.class 或显式 Class<C>
definition.dependencies();      // 不可变，按声明顺序
definition.outputNames();       // 不可变，按声明顺序
definition.outputKeys();        // 不可变，按声明顺序
definition.descriptor();        // 由依赖生成的静态 descriptor
definition.create();            // 每次调用返回新的无状态外壳
```

`componentId` 同时用于 descriptor 和 `factoryId`。它不像 mount ID 那样由调用点自动生成，而是定义的一部分；Loader、PF4J export 和诊断应使用这个稳定 ID。

Expert 入口允许集成层直接使用 `ActivationContext`：

```java
BeanDefinition<ServiceConfig, ExpertService> factory =
        BeanDefinition.<ServiceConfig, ExpertService>expert(
                "expert-service",
                ServiceConfig.class,
                List.of(
                        Beans.required(STORAGE),
                        Beans.optional(METRICS)),
                (context, config) -> new ExpertService(
                        config,
                        context.require(STORAGE),
                        context.find(METRICS)))
                .provide(EXPERT_SERVICE)
                .build();
```

Expert creator 运行时的每个 `require/find/subscribe` 仍必须与依赖列表声明一致；未声明 key、pinned 依赖调用 `subscribe` 或 dynamic 依赖调用 `require/find` 都会被 Core 拒绝。普通业务装配优先使用定长 creator API。

## 编译期 Factory

注解模式适合希望把 wiring 固化在业务源文件旁的项目。它引入编译期耦合；注解为 `SOURCE` retention，运行时不存在注解对象。

最小示例：

```java
@KnotraBean(id = "order-service")
@KnotraOutput(
        name = "app.order-service",
        contract = OrderService.class)
public final class DefaultOrderService implements OrderService {

    private final UserRepository users;
    private final Optional<Metrics> metrics;

    @KnotraConstructor
    DefaultOrderService(
            @KnotraRequire(
                    name = "app.users",
                    contract = UserRepository.class)
            UserRepository users,
            @KnotraOptional(
                    name = "app.metrics",
                    contract = Metrics.class)
            Optional<Metrics> metrics) {
        this.users = users;
        this.metrics = metrics;
    }

    @KnotraInit
    void start() {
        // cleanup 已登记，输出尚未提交。
    }

    @KnotraDestroy
    public void stop() {
        // 幂等清理，失败可被 Knotra retry。
    }
}
```

生成类与业务类位于同一个 package，名称固定为：

```text
DefaultOrderService_KnotraFactory
```

生成类是 public final、有无参构造函数，并实现 `ComponentFactory<C>`。直接实例化和挂载：

```java
DefaultOrderService_KnotraFactory factory =
        new DefaultOrderService_KnotraFactory();

ComponentHandle<NoConfig> handle =
        runtime.mount("order-service", factory);
```

需要读取定义元数据时：

```java
BeanDefinition<NoConfig, DefaultOrderService> definition =
        new DefaultOrderService_KnotraFactory().definition();
```

生成代码特性：

- 直接调用业务 constructor 和方法，不使用反射。
- CapabilityKey 使用编译期 class literal。
- 不嵌入时间戳，重复编译生成源稳定。
- 每个生成的 Factory 实例持有一个不可变 `BeanDefinition`。
- `create()` 返回的 Component 外壳不保存激活期状态。
- `@KnotraConfig` 参数传给 constructor；`Require/Optional/Dynamic` 按声明顺序传入。
- `@KnotraOutput` 使用 `bean -> bean` 映射，因此业务类必须可赋值给输出 contract。

### 注解参考

| 注解 | 位置 | 语义 |
|---|---|---|
| `@KnotraBean` | 类 | 声明 `id`、`config`、`lifecycle`、`outputs` |
| `@KnotraConstructor` | 构造器 | 选择唯一构造器 |
| `@KnotraRequire` | 构造参数 | Required pinned 依赖 |
| `@KnotraOptional` | 构造参数 | Optional pinned 依赖，参数必须是 `Optional<T>` |
| `@KnotraDynamic` | 构造参数 | Dynamic 依赖，`required` 默认 `true` |
| `@KnotraConfig` | 构造参数 | 接收归一化配置 |
| `@KnotraOutput` | 类，可重复 | 声明输出 Capability |
| `@KnotraInit` | 实例方法 | Cleanup 登记后、输出提交前初始化 |
| `@KnotraDestroy` | 实例方法 | 同步或异步 Bean 清理 |
| `@KnotraNormalizeConfig` | 静态方法 | 配置归一化 |

`@KnotraBean.lifecycle` 只支持：

- `AUTO`：无 `@KnotraDestroy` 时按 `AsyncCloseable/AutoCloseable` 推断；有 `@KnotraDestroy` 时使用该方法。
- `UNMANAGED`：生成 `.unmanaged()`，不为 Bean 登记清理；不能同时声明 `@KnotraDestroy`。

### 结构与诊断

Processor 在编译期拒绝以下形状，并输出稳定错误信息：

| 类别 | 规则 |
|---|---|
| 类形状 | 必须是 top-level class；不能是 interface、enum、record、abstract、private 或泛型类 |
| 稳定 ID | `id` 必须非空 |
| 构造器 | 必须有且只有一个 `@KnotraConstructor`；不能 private 或声明类型参数 |
| 参数标注 | 每个构造参数恰好标注 `Require/Optional/Dynamic/Config` 之一 |
| Required | contract 和参数类型必须精确一致，且不能 primitive、void、generic 或 parameterized |
| Optional | 参数必须精确为 `Optional<contract>`，contract 不能 generic 或 parameterized |
| Dynamic | contract 必须是 interface，参数必须是同一精确 interface；不能 generic 或 parameterized |
| Config | typed bean 必须恰好一个 `@KnotraConfig`；NoConfig bean 必须没有；类型必须精确匹配且非 primitive、generic 或 parameterized |
| 输出 | 名称非空；contract 非 primitive、void、generic 或 parameterized；业务类可赋值给它；同一输出名称不能重复 |
| Capability 名称 | 依赖和输出之间不能重复名称 |
| Init | 最多一个；非 private、零参数实例方法 |
| Destroy | 最多一个；非 private、零参数实例方法；`async=true` 必须返回 `CompletionStage<Void>`；不能与 UNMANAGED 组合 |
| Normalizer | 最多一个；typed bean 专用；非 private static，一个 config 参数，返回类型可赋值给 config 类型 |
| 可访问性 | config、依赖 contract、输出 contract 必须能被同 package 的生成类访问；private nested 类型拒绝 |
| 生成类冲突 | 完全限定 `Bean_KnotraFactory` 已存在或已被计划生成时拒绝 |

由于 CapabilityKey 的运行时 token 是精确 `Class<T>`，processor 不做泛型擦除猜测。需要 `List<String>`、`Optional<Integer>` 这类参数化 Capability 时，应拆出精确非泛型 contract，或改用手写 DSL 在边界自行处理。

## Spring Child Context

一个 Knotra Component 对应一个 Spring child context；不要为每个普通 Spring Bean 挂一个 Knotra 组件。child context 默认没有宿主 parent context，所有外部 Capability 必须显式声明。

### 最小 noConfig

```java
@Configuration
class OrderSpringConfig {
    @Bean
    OrderService orderService(UserRepository users) {
        return new OrderService(users);
    }
}
```

```java
ComponentFactory<NoConfig> factory =
        SpringModules.noConfig("order-spring")
                .annotatedClasses(OrderSpringConfig.class)
                .required("users", USER_REPOSITORY)
                .expose(ORDER_SERVICE, "orderService")
                .build();

ComponentHandle<NoConfig> spring =
        runtime.mount("order-spring", factory);
```

每次 Activation 都创建新的 `AnnotationConfigApplicationContext`。pinned required 或 optional provider 变化、配置变化时，整个 child context 关闭并重建；dynamic provider 变化时 context 不重建。

### 类型化配置

```java
public record PluginConfig(String endpoint, int poolSize) {}

ComponentFactory<PluginConfig> factory =
        SpringModules.typed("plugin-spring", PluginConfig.class)
                .annotatedClasses(PluginSpringConfig.class)
                .configBeanName("pluginConfig")
                .configNormalizer(config -> new PluginConfig(
                        config.endpoint().trim(),
                        Math.max(1, config.poolSize())))
                .required("repository", REPOSITORY)
                .expose(PLUGIN_SERVICE, "pluginService")
                .build();

ComponentHandle<PluginConfig> plugin = runtime.mount(
        "plugin-spring",
        factory,
        new PluginConfig(" https://example", 0));
```

Typed module 的配置默认注册为名为 `knotraConfig` 的 external singleton，可用 `configBeanName(...)` 改名。normalizer 在创建 context 前执行；返回 `null` 或抛错会以 `INVALID_CONFIG` 拒绝。

### 依赖注入形态

| Builder 方法 | Spring 中注册的形状 | 语义 |
|---|---|---|
| `required(name, key)` / `pinned(...)` | provider 存在时注册 `T` | Required pinned，变化重建 context |
| `optional(name, key)` | 存在时注册 `T`，缺失时不注册 bean | Optional pinned，出现、消失或替换重建 context |
| `optionalAsOptional(name, key)` | 总是注册 `Optional<T>` | Optional pinned，同样重建 context |
| `dynamic(name, key)` / `dynamicRequired(...)` | 注册稳定方法级 proxy | Dynamic required，provider 变化不重建 context |
| `dynamicOptional(name, key)` | 注册稳定方法级 proxy | Dynamic optional，provider 变化不重建 context |

`optional` 在 provider 存在时以 `T` 注册，缺失时完全没有该 bean。因此直接作为 Spring 必需构造参数或 `@Bean` 方法参数注入，会在缺失代 refresh 失败；需要使用 `ObjectProvider<T>`、`@Nullable`、`@Autowired(required = false)` 等 Spring 可选注入形式，或让该配置在缺失代注册其他 fallback。多个 assignable bean 时仍遵循 Spring by-type 候选解析，可能产生歧义。

`optionalAsOptional` 总是注册 `Optional<T>`，适合业务构造器明确接收 `Optional<T>`。external singleton 无法向 Spring 暴露可靠的自定义泛型 metadata，因此应通过声明的 bean name 或 qualifier 注入，不要依赖 `Optional<T>` 的类型参数完成 by-type 解析。

示例：

```java
@Bean
OrderService orderService(
        @Qualifier("metrics") Optional<Metrics> metrics) {
    return new OrderService(metrics);
}
```

### 输出

按 bean name 查找是推荐方式：

```java
.expose(ORDER_SERVICE, "orderService")
```

也可以按类型查找：

```java
.expose(ORDER_SERVICE)
```

By-type 查找使用 Spring bean factory 的候选解析，候选集合也包含 Knotra 注册的 config、借入依赖和 dynamic proxy 等 external singleton。多个 assignable bean 可能失败或选中非预期对象；Knotra 在查找后只检查结果是否可赋值给 Capability contract，子类实例也合法。需要确保输出来自模块内部 Bean 时，应使用稳定 bean name。

多输出按声明顺序全部解析后统一提交：

```java
ComponentFactory<NoConfig> factory =
        SpringModules.noConfig("module")
                .annotatedClasses(ModuleConfig.class)
                .expose(FIRST, "first")
                .expose(SECOND, "second")
                .build();
```

按 bean name 输出时，builder 会拒绝使用 config 或依赖占用的名称，通常用于发布 Spring 自己创建并拥有的内部 Bean；这类 Bean 随 child context 销毁。By-type 输出没有这层来源限制，因此必须自行确认解析到的对象及其所有权。

### Customizer、ClassLoader 与清理

`annotatedClasses(...)` 可重复调用追加配置类；`customizer(...)` 也可重复调用。启动顺序为：

```text
创建 context
-> 登记 Knotra cleanup
-> 设置 Spring ClassLoader 和 TCCL
-> register annotated classes
-> 注册 config external singleton
-> 注册依赖 external singleton 或 proxy
-> 依次执行 customizer
-> refresh
-> 解析并暂存输出
-> 恢复原 TCCL
-> Core 原子提交输出
```

ClassLoader 规则：

- 显式 `classLoader(loader)` 时始终使用该 loader。
- 未显式指定且有 annotated class 时，使用第一个 annotated class 的 loader。
- 未显式指定且没有 annotated class 时，使用 `knotra-spring` 实现类的 loader；plugin 中只靠 customizer 装配的模块应显式调用 `classLoader(pluginLoader)`，不要依赖该 fallback。
- 未显式指定且多个 annotated classes 来自不同 loader 时，`build()` 直接拒绝。
- start、refresh、customizer、closer hook 和物理 cleanup 期间，TCCL 临时切换为选定 loader，结束后恢复。

`customizer` 可注册额外 bean definition 或设置 Spring 属性。customizer 抛错时本次 Activation 失败，Knotra 会清理 context；如果 refresh 尚未完成，则销毁 Spring 已创建的早期 singleton。

### External Singleton 所有权

配置、required/optional 依赖和 dynamic proxy 都通过 Spring external singleton registry 注册。Spring 不会对它们执行：

- `InitializingBean` 初始化回调；
- `DisposableBean.destroy()`；
- `@PreDestroy`；
- 推断的 `close()`。

这些对象仍由 Knotra provider 或宿主拥有。Spring context 关闭只销毁 Spring 自己创建的内部 Bean。

### Closer 与重试

默认清理是一个不透明的 Spring context lifecycle entry。Spring 自己可能记录并吞掉内部 Bean 的 destroy 异常，因此这些异常不能可靠推动 Knotra retry。

需要把关闭失败反馈给 Knotra 时：

```java
SpringContextCloser closer = context ->
        drainExternalQueues().thenApply(ignored -> null);

ComponentFactory<NoConfig> factory =
        SpringModules.noConfig("module")
                .annotatedClasses(ModuleConfig.class)
                .closer(closer)
                .build();
```

清理顺序：

```text
执行 closer hook
-> hook 正常完成：物理关闭 context
-> hook 抛错或异常完成：保留 context，lifecycle entry FAILED
-> retryAsync() 重新执行同一个 hook
```

Hook 必须幂等，且不能假设异常后没有做过任何部分工作。Hook 成功后 Knotra 总是执行物理清理：active context 调 `close()`；refresh 未完成的 context 调 `destroySingletons()`。当前实现把 hook 返回 null 与正常完成等价，也会立即进入物理清理；实现方应优先返回明确的已完成 stage，避免依赖 null 约定。

Builder 还会拒绝重复依赖 bean name、重复依赖 Capability、重复输出 Capability、输出 bean name 占用依赖 name，以及依赖或输出 name 占用 config bean name。

## SpringDynamicBridge

宿主 Spring singleton 需要长期持有某个动态 Capability 的稳定 interface 时，使用 `SpringDynamicBridge`。bridge 是一个 dynamic OPTIONAL Knotra 组件，source 缺失时自身仍可 ACTIVE，并向 bridge key 发布稳定 proxy。

挂载到 root：

```java
SpringDynamicBridge<PaymentGateway> bridge =
        SpringDynamicBridge.mount(
                runtime,
                "payment-bridge",
                PAYMENT_GATEWAY,
                SPRING_PAYMENT_GATEWAY);
```

挂载到指定 Context：

```java
SpringDynamicBridge<PaymentGateway> bridge =
        SpringDynamicBridge.mount(
                runtime,
                workspace,
                "payment-bridge",
                PAYMENT_GATEWAY,
                SPRING_PAYMENT_GATEWAY);
```

约束：

- source 和 bridge CapabilityKey 的类型必须完全相同。
- Capability 类型必须是 Java interface；不接受 class 或子接口。
- mount ID 非空；bridge component 和 factory ID 由 mount ID 派生。
- source 缺失时 bridge 仍可 ACTIVE。
- `mount(...)` 是同步入口，内部最多等待 bridge Component settle 30 秒；未达到 `ACTIVE`、等待失败或超时时会先尝试 dispose provisional bridge，再抛出启动失败异常。

宿主 Spring 注入稳定 proxy：

```java
PaymentGateway gateway = bridge.proxy();
String receipt = gateway.charge(order);
```

多方法固定 provider：

```java
bridge.withCurrent(payment -> {
    payment.begin();
    payment.charge(order);
    payment.commit();
    return null;
});
```

异步版本：

```java
CompletionStage<Receipt> receipt =
        bridge.withCurrentAsync(payment -> payment.chargeAsync(order));
```

`available()` 是 advisory：

- source 缺失或 bridge 已关闭时返回 `false`；
- 返回 `true` 不锁住 provider；
- proxy 调用时 source 缺失抛 `CapabilityUnavailableException`；
- bridge 关闭后 proxy 调用抛 `DynamicCapabilityClosedException`；
- bridge 关闭后调用 `proxy()`、`withCurrent` 或 `withCurrentAsync` 抛 `IllegalStateException`。

`closeAsync()` 会依次：

1. 标记 bridge 关闭，拒绝新的 bridge API 和 proxy 调用；
2. 推进 bridge Component 清理：正常路径 dispose，若上一次清理已失败则 retry；
3. 等待已接受的动态调用或异步 stage 租约归零；
4. 成功后撤销 bridge capability，并让 stage 正常完成。

同一轮关闭未完成时，重复 `closeAsync()` 返回同一个 future，不会启动多条并行关闭链。清理失败时 stage 异常完成；下一次 `closeAsync()` 会继续 retry，直到 bridge Component 达到 `DISPOSED`。同步 `close()` 等待 `closeAsync()` 完成。

bridge 不拥有 source provider。宿主仍按原方式管理 Spring singleton 的生命周期；从 provider 内部逃逸出来的连接、session 或回调句柄也不由 bridge 自动排空。

## 选择边界

| 场景 | 推荐方式 |
|---|---|
| 普通业务对象图 | 一个 `knotra-beans` Component 内构造一组 POJO |
| 独立配置、观测和替换边界 | 单独 Capability + pinned dependency |
| 可缺 metrics 或 features | `optional` / `optionalAsOptional` |
| 配置变化需要重建资源 | typed config + normalizer + reconfigure |
| 一个 Bean 发布多个外部视图 | 多输出 `provide` / `provideAs` |
| 无状态单方法动态路由 | Dynamic proxy |
| 多方法事务、session 或一致性边界 | `DynamicCapability.call` / `callAsync` |
| 希望编译期固定 wiring | `knotra-beans-processor` |
| 一组 Spring Bean 作为动态模块 | `SpringModules` child context |
| 宿主 singleton 持有动态 Knotra interface | `SpringDynamicBridge` |

不要用 dynamic proxy 绕过状态边界。事务、长连接 session、流式结果、返回对象逃逸和多步骤业务操作都需要显式 `call`、`withCurrent`，或业务自己的 lease/close 契约。
