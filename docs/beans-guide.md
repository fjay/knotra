# Beans 与声明式装配

`knotra-beans` 是 Knotra 面向纯 Java 对象的装配模块。它提供了类型安全的 Fluent DSL 与编译期注解处理器，将普通 POJO 接入 Knotra 的 Activation 生命周期，并支持声明式依赖管理与动态代理注入。

## 基础模型

Knotra 的 Beans 模块主要由以下核心抽象组成：

```java
// 1. 无配置组件定义：描述组件依赖、构造方式与输出能力
BeanDefinition<CheckoutService> definition = Beans
        .component("checkout")
        .with(pricingDep, gatewayDep)
        .create(deps -> new CheckoutService(deps.get(pricingDep), deps.get(gatewayDep)))
        .provideAs(Checkout.class)
        .build();

// 2. 挂载到运行时并获取挂载点句柄
MountHandle handle = definition.mount(runtime);
handle.requireActive(Duration.ofSeconds(10));
```

核心概念说明：

| 概念 | 说明 |
|---|---|
| `BeanDefinition<T>` | 无配置的组件定义，不可变且线程安全，支持多次重复挂载 |
| `ConfiguredBeanDefinition<C, T>` | 带类型化配置的组件定义，支持在线热重配置 |
| `MountHandle` | 挂载到运行时树上的稳定逻辑句柄，用于管理生命周期与状态断言 |
| `ConfiguredMountHandle<C>` | 配置型挂载点句柄，提供 `reconfigureAsync(newConfig)` 能力 |

## 六种依赖注入模式

针对不同的组件状态与业务场景，`Beans` 提供了六种类型安全的依赖注入模式：

| DSL 表达式 | 注入类型 | 语义与生命周期行为 | 适用场景 |
|---|---|---|---|
| `Beans.dynamic(T.class)` | `T` (接口代理) | **动态代理**：提供方升级时，消费方实例不重启，调用自动路由到新版本；每个方法调用自动获取/释放短期租约。 | **默认推荐**。无状态策略计算、服务热插拔。 |
| `Beans.dynamicOptional(T.class)` | `T` (接口代理) | **可选动态代理**：启动时不强制要求提供方必须就绪；调用时若无提供方则抛错。 | 可选扩展点、插件化业务钩子。 |
| `Beans.dynamicCapability(T.class)` | `DynamicCapability<T>` | **显式租约**：通过 `call` 或 `callAsync` 在单次业务操作中跨多个方法锁定同一代提供方。 | 多步骤业务计算一致性要求、异步流式链路。 |
| `Beans.dynamicCapabilityOptional(T.class)` | `DynamicCapability<T>` | **可选显式租约**：启动时不强制要求提供方就绪。 | 包含复杂一致性要求的可选扩展能力。 |
| `Beans.fixed(T.class)` | `T` (真实实例) | **固定代际绑定**：启动时绑定当前提供方的具体代际；提供方发生升级替换时，**消费方将自动销毁并重建**。 | 有状态组件、批量跑批 Job、底层连接池。 |
| `Beans.fixedOptional(T.class)` | `Optional<T>` | **可选固定代际**：提供方出现或消失时，均会触发消费方安全销毁并重建。 | 依据配置启停的后台工作线程。 |

### 1. 动态接口代理（Beans.dynamic）

```java
var pricingDep = Beans.dynamic(PricingStrategy.class);

BeanDefinition<OrderService> definition = Beans
        .component("order-service")
        .with(pricingDep)
        .create(deps -> new OrderService(deps.get(pricingDep)))
        .provideAs(OrderService.class)
        .build();
```

当 `PricingStrategy` 的 `Publication` 发生 `update` 时：
- `OrderService` 实例继续保持在线，无需重新创建。
- 后续调用 `orderService.calculate(...)` 时，自动透明执行新版本逻辑。

### 2. 显式一致性租约（Beans.dynamicCapability）

当一个业务流程包含多个方法调用，且必须在同一版本策略下执行时：

```java
var pricingCap = Beans.dynamicCapability(PricingStrategy.class);

public class CheckoutWorkflow {
    private final DynamicCapability<PricingStrategy> pricing;

    public CheckoutWorkflow(DynamicCapability<PricingStrategy> pricing) {
        this.pricing = pricing;
    }

    public Receipt process(Order order) {
        // 在 call 执行期间锁定当前提供方代际，即使后台升级也不会在此次调用中漂移
        return pricing.call(strategy -> {
            int base = strategy.calculateBasePrice(order);
            int discount = strategy.calculateDiscount(order);
            return new Receipt(order.id(), base - discount);
        });
    }
}
```

### 3. 固定代际绑定（Beans.fixed）

```java
var datasourceDep = Beans.fixed(DataSource.class);

BeanDefinition<ConnectionPoolManager> definition = Beans
        .component("pool-manager")
        .with(datasourceDep)
        .create(deps -> new ConnectionPoolManager(deps.get(datasourceDep)))
        .build();
```

当底层 `DataSource` 重新发布新实例时，`ConnectionPoolManager` 挂载点会自动触发 LIFO 逆序销毁旧实例，并使用新 `DataSource` 重新构造。

## 多依赖与输出映射

### 声明多个依赖项

`with(...)` 支持传入任意数量的依赖句柄：

```java
var pricing = Beans.dynamic(Pricing.class);
var payment = Beans.dynamic(PaymentGateway.class);
var profile = Beans.fixedOptional(UserProfile.class);

BeanDefinition<CheckoutService> definition = Beans
        .component("checkout")
        .with(pricing, payment, profile)
        .create(deps -> new CheckoutService(
                deps.get(pricing),
                deps.get(payment),
                deps.get(profile)))
        .provideAs(Checkout.class)
        .build();
```

### 能力输出与适配转换

组件构造完成后，可对外暴露服务能力：

```java
// 方式 1：直接暴露实现的接口
.provideAs(OrderService.class)

// 方式 2：使用自定义 CapabilityKey
.provideAs(CapabilityKey.of("order.primary", OrderService.class))

// 方式 3：提供适配器方法转换
.provideAs(OrderService.class, DefaultOrderServiceImpl::getPublicFacade)
```

## 确定性生命周期管理

`knotra-beans` 默认会自动识别并托管组件的关闭逻辑：

1. **实现 `AsyncCloseable`**：异步销毁，等待返回的 `CompletionStage` 完成。
2. **实现 `AutoCloseable` 或 `Closeable`**：同步调用 `close()`。
3. **未实现关闭接口**：不执行自动销毁。

### 显式配置生命周期钩子

```java
BeanDefinition<HttpClient> definition = Beans
        .component("http-client")
        .create(HttpClient::new)
        .initializer(HttpClient::warmup)               // 启动后、对外暴露能力前执行
        .destroyWith(HttpClient::close)                // 同步销毁钩子
        .destroyAsyncWith(HttpClient::shutdownAsync)   // 异步销毁钩子
        .build();
```

若对象由外部环境自行管理生命周期，可通过 `.unmanaged()` 显式声明不由 Knotra 托管销毁。

## 配置型 Bean 与热重配置

当组件依赖外部配置且支持运行时动态调整时，使用 `ConfiguredBeanDefinition`：

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
```

### 挂载与在线热重配置

```java
// 1. 挂载初始配置
ConfiguredMountHandle<CacheConfig> handle =
        definition.mount(runtime, new CacheConfig(500, Duration.ofMinutes(10)));
handle.requireActive(Duration.ofSeconds(10));

// 2. 运行期间发起热重配置
handle.reconfigureAsync(new CacheConfig(2000, Duration.ofMinutes(30)))
        .toCompletableFuture()
        .get(10, TimeUnit.SECONDS);
```

重配置执行流程：
- 校验并规整新配置（`normalizeConfig`）。
- 若配置合法，创建新 Activation 并按 LIFO 逆序销毁旧实例。
- 整个过程在途请求受到租约保护，若新配置激活失败则保留错误诊断并允许重试。

## 编译期注解处理器 (knotra-beans-processor)

对于依赖项较多或偏好注解风格的工程，可引入 `knotra-beans-processor`。该处理器在 Java 编译期生成强类型的工厂代码，**完全不依赖运行时反射**：

### 引入依赖

```xml
<dependency>
  <groupId>io.knotra</groupId>
  <artifactId>knotra-beans-processor</artifactId>
  <scope>provided</scope>
</dependency>
```

### 注解使用示例

```java
@KnotraBean(
        id = "checkout-service",
        outputs = @KnotraOutput(
                name = "checkout.public",
                contract = Checkout.class))
public final class CheckoutService implements Checkout {

    @KnotraConstructor
    CheckoutService(
            @KnotraDynamicProxy("gateway") PaymentGateway gateway,
            @KnotraFixed("pricing") Pricing pricing,
            @KnotraFixedOptional("profile") Optional<UserProfile> profile) {
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

### 使用生成的工厂挂载

```java
BeanDefinition<CheckoutService> definition =
        new CheckoutService_KnotraFactory().definition();

MountHandle handle = definition.mount(runtime);
handle.requireActive(Duration.ofSeconds(10));
```

## 下一步

- 将独立 Spring 子容器或 Spring Boot 宿主单例接入 Knotra：[Spring 集成](spring-guide.md)
- 插件工程结构、PF4J 隔离与声明式调和：[插件工程](plugin-guide.md)
- 线程模型、超时预算与停机诊断：[生产实践与排障](production-practice.md)
