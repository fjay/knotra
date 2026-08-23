# Beans 与 Spring 集成指南

在实际业务开发中，开发者通常不需要直接编写底层 SPI，而是使用熟悉的 **POJO** 或 **Spring 容器**。

Knotra 提供了两个核心集成模块：
* **`knotra-beans`**：面向纯 Java 的 Fluent DSL 与编译期注解处理器，将普通 POJO 接入 Knotra 的 Activation 生命周期。
* **`knotra-spring`**：将独立的 Spring 子容器包装为 Knotra 挂载点，支持子容器的隔离刷新、依赖注入以及宿主单例与动态插件的桥接。

---

## 概念与 Spring 术语对照

| Spring 概念 | Knotra 对应概念 | 详细说明 |
|---|---|---|
| Service 接口 | `CapabilityKey<T>` / `Class<T>` | 服务的类型化身份契约。 |
| Singleton Bean 实例 | `Publication<T>` 槽位 | 长期稳定的服务槽位，支持原地升级替换。 |
| `@Bean` / Component 定义 | `BeanDefinition<T>` | 无配置的普通组件定义，描述依赖与构造方式。 |
| 带 `@ConfigurationProperties` 的 Bean | `ConfiguredBeanDefinition<C, T>` | 显式声明配置类型契约的组件定义。 |
| Bean 在 ApplicationContext 中的注册 | `MountHandle` | 稳定的逻辑挂载点句柄，支持重试、状态查询与释放。 |
| 热重载 / 刷新配置 | `ConfiguredMountHandle.reconfigureAsync(C)` | 动态更新配置并重新激活组件。 |

---

## Maven 依赖配置

```xml
<dependencies>
  <!-- 基础 Starter（包含 Core 与 Beans DSL） -->
  <dependency>
    <groupId>io.knotra</groupId>
    <artifactId>knotra-starter</artifactId>
  </dependency>

  <!-- 编译期注解处理器（可选，自动生成工厂代码） -->
  <dependency>
    <groupId>io.knotra</groupId>
    <artifactId>knotra-beans-processor</artifactId>
    <scope>provided</scope>
  </dependency>

  <!-- Spring 集成支持（可选，需要挂载 Spring 子容器或宿主桥接时引入） -->
  <dependency>
    <groupId>io.knotra</groupId>
    <artifactId>knotra-spring</artifactId>
  </dependency>
</dependencies>
```

---

## POJO Fluent DSL

### 1. 无配置 Bean 的声明与挂载

假设有如下业务接口与实现：

```java
public interface Greeting {
    String greet(String name);
}

public interface RenderedGreeting {
    String render(String name);
}

public final class GreetingRenderer implements RenderedGreeting {
    private final Greeting greeting;

    public GreetingRenderer(Greeting greeting) {
        this.greeting = greeting;
    }

    @Override
    public String render(String name) {
        return greeting.greet(name);
    }
}
```

使用 `Beans` DSL 进行声明、绑定与挂载：

```java
try (KnotraRuntime runtime = KnotraRuntime.create()) {
    // 1. 发布 Greeting 实现
    runtime.publish(Greeting.class, new ChineseGreeting())
            .awaitSettled(Duration.ofSeconds(10));

    // 2. 声明 Bean 定义
    BeanDefinition<GreetingRenderer> definition = Beans
            .component("greeting-renderer")
            .with(Beans.dynamic(Greeting.class)) // 动态依赖：提供方替换不触发重建
            .create(GreetingRenderer::new)       // 构造器推导
            .provideAs(RenderedGreeting.class)   // 对外暴露的能力接口
            .build();

    // 3. 挂载到运行时
    MountHandle handle = definition.mount(runtime);
    handle.requireActive(Duration.ofSeconds(10));

    // 4. 调用服务
    String result = runtime.require(RenderedGreeting.class).render("Knotra");
}
```

`BeanDefinition<T>` 是不可变且线程安全的，构建一次后可跨多个 Runtime 或多次重复挂载。

---

### 2. 六种依赖注入模式速查

| DSL 表达式 | 注入类型 | 语义与生命周期行为 | 适用场景 |
|---|---|---|---|
| `Beans.dynamic(T.class)` | `T` (接口代理) | **动态代理**：提供方热替换时，消费方实例不重启，调用自动路由到新版本；每个方法独立租约。 | **默认推荐**。无状态调用、策略热插拔。 |
| `Beans.dynamicOptional(T.class)` | `T` (接口代理) | **可选动态代理**：首次启动时不要求提供方必须存在；调用时若无提供方则抛错。 | 可选扩展点、插件化钩子。 |
| `Beans.dynamicCapability(T.class)` | `DynamicCapability<T>` | **显式租约**：通过 `call` 或 `callAsync` 在单次业务操作中锁定同一代提供方。 | 多方法调用一致性要求、异步计算链路。 |
| `Beans.dynamicCapabilityOptional(T.class)` | `DynamicCapability<T>` | **可选显式租约**：首次启动时不强制要求提供方就绪。 | 包含复杂一致性要求的可选插件。 |
| `Beans.fixed(T.class)` | `T` (真实实例) | **固定代际绑定**：启动时固定绑定当前提供方代际；提供方发生热替换时，**消费方会自动销毁并重建**。 | 有状态组件、批量结算 Job、底层连接池。 |
| `Beans.fixedOptional(T.class)` | `Optional<T>` | **可选固定代际**：提供方出现或消失都会触发消费方销毁并重建。 | 根据配置项启停的后台工作线程。 |

---

### 3. 多依赖与输出映射

DSL 支持 0 到 5 个显式依赖的组合注入：

```java
BeanDefinition<CheckoutService> definition = Beans
        .component("checkout")
        .with(Beans.fixed(Pricing.class))
        .with(Beans.dynamic(PaymentGateway.class), Beans.fixedOptional(UserProfile.class))
        .create((pricing, gateway, profile) -> new CheckoutService(pricing, gateway, profile))
        .provideAs(Checkout.class)
        .build();
```

如果 Bean 的内部实现类与对外暴露的接口不一致，或者需要做适配转换：

```java
// 直接暴露实现的接口
.provideAs(OrderService.class)

// 自定义输出映射转换
.provideAs(OrderService.class, DefaultOrderService::publicFacade)
```

---

### 4. 确定性生命周期管理

`knotra-beans` 默认会自动检测并执行对象的关闭逻辑：
1. 实现 `AsyncCloseable`：异步关闭。
2. 实现 `AutoCloseable` 或 `Closeable`：同步关闭。
3. 未实现上述接口：不自动执行销毁动作。

也可以通过 DSL 显式指定初始化与销毁逻辑：

```java
BeanDefinition<HttpClient> definition = Beans
        .component("http-client")
        .create(HttpClient::new)
        .initializer(HttpClient::warmup)               // 启动后、输出发布前执行
        .destroyAsyncWith(HttpClient::shutdownAsync)   // 异步销毁，等待 CompletionStage 完成
        .build();
```

* `initializer(Initializer<T>)`：初始化钩子。
* `destroyWith(Disposer<T>)`：同步释放钩子。
* `destroyAsyncWith(AsyncDisposer<T>)`：异步释放钩子。
* `unmanaged()`：明确声明该 Bean 不由 Knotra 托管销毁。

---

### 5. 配置型 Bean 与热重配置

当组件的配置属于宿主契约且支持在线调整时，使用 `ConfiguredBeanDefinition`：

```java
public record CacheConfig(int maxSize, Duration ttl) {
    public CacheConfig {
        maxSize = maxSize <= 0 ? 1000 : maxSize;
    }
}

ConfiguredBeanDefinition<CacheConfig, CacheService> definition = Beans
        .component("cache-service", CacheConfig.class)
        .create((CacheConfig config) -> new CacheService(config))
        .provideAs(Cache.class)
        .normalizeConfig(config -> new CacheConfig(config.maxSize(), config.ttl()))
        .build();

// 挂载初始配置
ConfiguredMountHandle<CacheConfig> handle =
        definition.mount(runtime, new CacheConfig(500, Duration.ofMinutes(10)));
handle.requireActive(Duration.ofSeconds(10));

// 运行中动态热重配置
handle.reconfigureAsync(new CacheConfig(2000, Duration.ofMinutes(30)))
        .toCompletableFuture()
        .get(10, TimeUnit.SECONDS);
```

重配置会触发生成新的 Activation：旧 Bean 实例按 LIFO 逆序释放，新 Bean 使用新配置完成初始化。配置校验失败会被安全拒绝，不会破坏当前正在运行的实例。

---

## 编译期注解处理器（knotra-beans-processor）

当构造函数依赖较多（超过 5 个）或团队更倾向于注解风格时，可使用编译期注解处理器。它在编译期间生成强类型的 `_KnotraFactory`，**不包含任何运行时反射扫描**：

```java
@KnotraBean(
        id = "checkout-service",
        outputs = @KnotraOutput(
                name = "checkout.public",
                contract = Checkout.class))
public final class CheckoutService implements Checkout {

    @KnotraConstructor
    CheckoutService(
            @KnotraFixed("pricing") Pricing pricing,
            @KnotraFixedOptional("profile") Optional<UserProfile> profile,
            @KnotraDynamicProxy("gateway") PaymentGateway gateway) {
        // ...
    }

    @KnotraInit
    void init() {
        // 初始化逻辑
    }

    @KnotraDestroy
    void destroy() {
        // 销毁逻辑
    }
}
```

使用生成的工厂进行挂载：

```java
BeanDefinition<CheckoutService> definition =
        new CheckoutService_KnotraFactory().definition();

MountHandle handle = definition.mount(runtime);
handle.requireActive(Duration.ofSeconds(10));
```

---

## Spring 子容器集成（knotra-spring）

`knotra-spring` 允许将一个完整的 Spring `AnnotationConfigApplicationContext` 作为独立的子容器挂载到 Knotra 中。

### 1. 挂载无配置 Spring 模块

```java
MountFactory springModule = SpringModules.noConfig("order-module")
        .annotatedClasses(OrderModuleSpringConfig.class)
        // 将 Knotra 中的能力注入到 Spring 子容器的 Bean 中
        .required("pricing", Pricing.class)
        .dynamic("gateway", PaymentGateway.class)
        // 将 Spring 子容器内部的 Bean 导出给 Knotra
        .expose(OrderService.class)
        .build();

try (KnotraRuntime runtime = KnotraRuntime.create()) {
    MountHandle handle = runtime.mount("order-module", springModule);
    handle.requireActive(Duration.ofSeconds(10));

    OrderService orderService = runtime.require(OrderService.class);
}
```

### 2. 挂载类型化配置的 Spring 模块

```java
var configuredSpring = SpringModules.typed("order-module", OrderProperties.class)
        .annotatedClasses(OrderModuleSpringConfig.class)
        .configBeanName("orderProperties")
        .expose(OrderService.class)
        .build();

ConfiguredMountHandle<OrderProperties> handle = runtime.mount(
        "order-module", configuredSpring, new OrderProperties("prod"));
handle.requireActive(Duration.ofSeconds(10));

// 热重载 Spring 子容器配置
handle.reconfigureAsync(new OrderProperties("peak-load"))
        .toCompletableFuture()
        .get(15, TimeUnit.SECONDS);
```

Spring 子容器在重配置或重建时会触发容器的 `close()` 与新实例的 `refresh()`。Knotra 确保子容器内的 Bean 得到完整且安全的生命周期回收。

---

## Spring 宿主动态桥（SpringDynamicBridge）

如果宿主应用本身是一个传统的 Spring Boot 单例环境，宿主中的单例 Bean 需要调用可能会随时热替换的 Knotra 插件能力，使用 `SpringDynamicBridge`：

```java
@Configuration
public class HostAppConfig {

    @Bean(destroyMethod = "close")
    public SpringDynamicBridge<PaymentGateway> paymentBridge(KnotraRuntime runtime) {
        return SpringDynamicBridge.mount(
                runtime,
                "payment-bridge",
                CapabilityKey.of(PaymentGateway.class),
                CapabilityKey.of("host.payment-gateway", PaymentGateway.class));
    }

    @Bean
    public PaymentGateway paymentGateway(SpringDynamicBridge<PaymentGateway> bridge) {
        // 返回一个对宿主完全透明的动态代理 Bean
        return bridge.proxy();
    }
}
```

宿主 Spring 单例 Bean 直接注入 `PaymentGateway` 即可正常使用。当底层插件升级或热替换时，宿主单例无需重启，调用始终透明路由。

---

## 场景选型总结

| 业务场景 | 推荐方案 |
|---|---|
| 普通 Java POJO，构造清晰简洁 | `knotra-beans` Fluent DSL |
| 依赖参数较多、倾向于注解驱动 | `knotra-beans-processor` 注解处理器 |
| 需要使用 MyBatis、Spring Data 等完整 Spring 生态且需插件隔离 | `SpringModules` 子容器挂载 |
| 宿主 Spring Boot 单例调用动态插拔能力 | `SpringDynamicBridge` 宿主桥接 |
| 批量计费、有状态任务（需锁定某代不可变） | `Beans.fixed(...)` |
| 营销策略、计价引擎、风控规则热插拔 | `Beans.dynamic(...)` |
