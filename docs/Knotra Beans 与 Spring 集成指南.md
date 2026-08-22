# Knotra Beans 与 Spring 集成指南

Beans 负责把普通 Java 对象接入 Knotra 的 Activation 生命周期；Spring 集成负责在需要 Bean 容器、依赖注入和已有 Spring 生态时，把子容器变成一个受 Knotra 管理的挂载点。

## 概念对照

| 概念 | 类型 | 说明 |
|---|---|---|
| 能力合约 | `CapabilityKey<T>` / `Class<T>` | 类型化服务身份；单槽位接口可用 Class 默认 key |
| 发布槽位 | `Publication<T>` | 长期稳定，可重复 update |
| 注册代际 | `Registration<T>` | Advanced API 中某一次已提交值 |
| Bean 定义 | `BeanDefinition<T>` | 无公开配置的普通 POJO 定义 |
| 配置型定义 | `ConfiguredBeanDefinition<C,T>` | 配置类型是公开契约 |
| 普通挂载 | `MountHandle` | 查询状态、等待、重试、释放 |
| 配置挂载 | `ConfiguredMountHandle<C>` | 额外支持 `reconfigureAsync(C)` |

普通 Bean 使用方看不到配置占位类型；只有配置真正属于宿主契约时才写 `ConfiguredBeanDefinition<C,T>`。

## Maven 依赖

先按 README 从源码执行 `mvn clean install`。普通应用使用 BOM 与 Starter；需要 Spring 或注解处理器时增加对应模块：

```xml
<dependencies>
  <dependency>
    <groupId>io.knotra</groupId>
    <artifactId>knotra-starter</artifactId>
  </dependency>

  <dependency>
    <groupId>io.knotra</groupId>
    <artifactId>knotra-beans-processor</artifactId>
    <scope>provided</scope>
  </dependency>

  <dependency>
    <groupId>io.knotra</groupId>
    <artifactId>knotra-spring</artifactId>
  </dependency>
</dependencies>
```

## POJO DSL

### 无配置 Bean

```java
interface Greeting {
    String greet(String name);
}

interface RenderedGreeting {
    String render(String name);
}

final class GreetingRenderer implements RenderedGreeting {
    private final Greeting greeting;

    GreetingRenderer(Greeting greeting) {
        this.greeting = greeting;
    }

    @Override
    public String render(String name) {
        return greeting.greet(name);
    }
}
```

声明依赖、构造器、输出和挂载：

```java
try (KnotraRuntime runtime = KnotraRuntime.create()) {
    runtime.publish(Greeting.class, new ChineseGreeting())
            .awaitSettled(Duration.ofSeconds(10));

    BeanDefinition<GreetingRenderer> definition = Beans
            .component("greeting-renderer")
            .with(Beans.dynamic(Greeting.class))
            .create(GreetingRenderer::new)
            .provideAs(RenderedGreeting.class, renderer -> renderer)
            .build();

    MountHandle handle = definition.mount(runtime);
    handle.requireActive(Duration.ofSeconds(10));

    String value = runtime.root().view()
            .require(RenderedGreeting.class)
            .render("Knotra");
}
```

`BeanDefinition<T>` 不可变，可以构建一次、在不同 Runtime 或不同 mountId 下重复挂载。

### 多依赖与构造器推导

```java
BeanDefinition<CheckoutService> definition = Beans
        .component("checkout")
        .with(Beans.fixed(Pricing.class))
        .with(Beans.fixed(Inventory.class), Beans.fixedOptional(UserProfile.class))
        .create((pricing, inventory, profile) ->
                new CheckoutService(pricing, inventory, profile))
        .provideAs(Checkout.class)
        .build();
```

DSL 支持 0 到 5 个显式依赖。更多构造参数、复杂泛型或强一致生成需求使用注解处理器；它不受手写 DSL arity 限制。

### 输出映射

`provide(Class<T>)` 要求 Bean 本身实现合约；`provideAs(Class<P>)` 允许类型转换导出，`provideAs(Class<P>, mapper)` 允许把内部实现映射成公开接口：

```java
BeanDefinition<DefaultOrderService> definition = Beans
        .component("order-service")
        .with(Beans.fixed(OrderRepository.class))
        .create(DefaultOrderService::new)
        .provideAs(OrderService.class, DefaultOrderService::publicFacade)
        .build();

```

多个输出在同一 Activation 中原子提交。任一输出为 null 或映射失败，整次启动失败，其他输出不会提前可见。

### 生命周期

默认按 Bean 类型自动选择释放：

1. `AsyncCloseable`
2. `AutoCloseable`
3. 不自动关闭

也可以显式声明：

```java
BeanDefinition<HttpClient> definition = Beans
        .component("http-client")
        .create(HttpClient::new)
        .destroyAsyncWith(client -> client.shutdown())
        .build();
```

可用选项：

- `initializer(Initializer<T>)`：构造后、输出发布前执行。
- `destroyWith(Disposer<T>)`：同步释放。
- `destroyAsyncWith(AsyncDisposer<T>)`：异步释放，settlement 等待返回的 CompletionStage。
- `unmanaged()`：Knotra 不关闭该 Bean。

显式 disposer 会替代自动 close 推断。释放失败让挂载进入 FAILED，可通过 `retryAsync()` 重试，不会伪造成功。

### 配置型 Bean

```java
record RendererConfig(String prefix) {
    RendererConfig {
        prefix = prefix == null || prefix.isBlank() ? "Hello" : prefix;
    }
}

ConfiguredBeanDefinition<RendererConfig, GreetingRenderer> definition = Beans
        .component("greeting-renderer", RendererConfig.class)
        .with(Beans.dynamic(Greeting.class))
        .create((config, greeting) -> new GreetingRenderer(greeting, config.prefix()))
        .provideAs(RenderedGreeting.class, renderer -> renderer)
        .normalizeConfig(config -> new RendererConfig(config.prefix().trim()))
        .build();

try (KnotraRuntime runtime = KnotraRuntime.create()) {
    ConfiguredMountHandle<RendererConfig> handle =
            definition.mount(runtime, new RendererConfig("Hello"));

    handle.requireActive(Duration.ofSeconds(10));
    handle.reconfigureAsync(new RendererConfig("Bonjour"))
            .toCompletableFuture()
            .get(10, TimeUnit.SECONDS);
    handle.requireActive(Duration.ofSeconds(10));
}
```

重配置会产生新的 Activation；旧 Bean 按生命周期释放，新 Bean 使用归一化后的配置启动。配置归一化失败以 `INVALID_CONFIG` 拒绝，不影响当前激活。

## 依赖模式

| DSL | 注入值 | 语义 |
|---|---|---|
| `fixed` | `T` | 启动固定一代；提供方变化触发消费方重建 |
| `fixedOptional` | `Optional<T>` | 提供方出现或消失触发重建 |
| `dynamic` | `T` 接口代理 | 提供方替换不重建消费方；每个方法独立租约 |
| `dynamicOptional` | `T` 接口代理 | 首次启动也不要求提供方存在 |
| `dynamicCapability` | `DynamicCapability<T>` | 显式租约，可 `call` / `callAsync` 固定 provider |
| `dynamicCapabilityOptional` | `DynamicCapability<T>` | 显式租约，首次启动可不满足 |


默认推荐：

```java
.with(Beans.dynamic(PaymentGateway.class))
```

它面向“像普通接口一样调用，但底层可替换”的常见业务场景。只有多次调用必须落在同一个 provider 上时才使用：

```java
.with(Beans.dynamicCapability(Pricing.class))
```

示例：

```java
final class PriceView {
    private final DynamicCapability<Pricing> pricing;

    PriceView(DynamicCapability<Pricing> pricing) {
        this.pricing = pricing;
    }

    Quote quote(String itemId) {
        return pricing.call(current -> new Quote(
                current.price(itemId),
                current.promotion(itemId)));
    }
}
```

`dynamic` 与 `dynamicCapability` 的 required 都只约束首次激活。Bean ACTIVE 后提供方消失不会自动停用；后续调用失败，直到新提供方出现。

## 注解处理器

注解处理器为 `@KnotraBean` 生成 `<Bean>_KnotraFactory`，并返回新的 `BeanDefinition<T>` 或 `ConfiguredBeanDefinition<C,T>`。它不是运行时反射扫描器，生成源码可在编译产物中审查。

```java
@KnotraBean(
        id = "checkout-service",
        outputs = @KnotraOutput(
                name = "checkout.public",
                contract = Checkout.class))
public final class CheckoutService implements Checkout {

    @KnotraConstructor
    CheckoutService(
            @KnotraFixed("checkout.pricing") Pricing pricing,
            @KnotraOptional("checkout.profile") Optional<UserProfile> profile,
            @KnotraDynamicProxy("checkout.gateway") PaymentGateway gateway) {
        ...
    }

    @KnotraInit
    void start() {
        ...
    }

    @KnotraDestroy
    void stop() {
        ...
    }
}
```

使用生成工厂：

```java
BeanDefinition<CheckoutService> definition =
        new CheckoutService_KnotraFactory().definition();

MountHandle handle = definition.mount(runtime);
```

常用注解：

- `@KnotraBean(id, outputs, config, lifecycle)`
- `@KnotraConstructor`
- `@KnotraFixed(name)`
- `@KnotraOptional(name)`

- `@KnotraDynamicProxy(name)`
- `@KnotraConfig`
- `@KnotraOutput(name, contract)`
- `@KnotraInit`
- `@KnotraDestroy(async)`
- `@KnotraNormalizeConfig`

`@KnotraDynamicProxy` 在生成代码中使用 `Beans.dynamic` 或 `Beans.dynamicOptional`。配置型 Bean 的构造器必须恰好有一个 `@KnotraConfig` 参数，并生成 `ConfiguredBeanDefinition<C,T>`。

## Spring 子容器

### 无公开配置的 Spring 模块

`SpringModules.noConfig(...)` 返回的 builder 最终产出可直接给 Simple mount 使用的 factory；用户代码不需要写配置占位类型：

```java
MountFactory factory = SpringModules.noConfig("order-spring")
        .annotatedClasses(OrderConfig.class)
        .required("pricing", Pricing.class)
        .dynamic("gateway", PaymentGateway.class)
        .expose(OrderService.class)
        .build();

try (KnotraRuntime runtime = KnotraRuntime.create()) {
    MountHandle handle = runtime.mount("order-spring", factory);
    handle.requireActive(Duration.ofSeconds(10));
}
```

### 类型化配置的 Spring 模块

```java
var factory = SpringModules.typed("order-spring", OrderConfig.class)
        .annotatedClasses(OrderJavaConfig.class)
        .configBeanName("orderConfig")
        .configNormalizer(config -> config.normalized())
        .required("pricing", Pricing.class)
        .expose(OrderService.class)
        .build();

try (KnotraRuntime runtime = KnotraRuntime.create()) {
    ConfiguredMountHandle<OrderConfig> handle = runtime.mount(
            "order-spring", factory, new OrderConfig("standard"));
    handle.requireActive(Duration.ofSeconds(10));

    handle.reconfigureAsync(new OrderConfig("peak"))
            .toCompletableFuture()
            .get(10, TimeUnit.SECONDS);
}
```

Spring 子容器每次 Activation 重新 refresh。固定依赖的提供方替换会让子容器重建；动态代理依赖替换不会触发重建。

依赖注入形式：

- `required(beanName, Class<T>)`：注册 `T`。
- `optional(beanName, Class<T>)`：能力存在才注册 `T`。
- `optionalAsOptional(beanName, Class<T>)`：始终注册 `Optional<T>`。
- `dynamic(beanName, Class<T>)`：注册接口代理。
- `dynamicCapability(beanName, Class<T>)`：注册 `DynamicCapability<T>`。

`optionalAsOptional` 的手动 singleton 缺少泛型元数据，Spring 按类型注入可能不可靠，应按 bean name 或 qualifier 注入。

### 输出与关闭

`expose(type)` 通过 Spring by-type 查找；多个可赋值候选时更稳妥使用 `expose(type, beanName)`。

Spring 自己的 shutdown 可能记录并吞掉 Bean destroy 异常。要让清理失败驱动 Knotra 重试，使用 `closer(...)` 注册可观测关闭钩子：

```java
MountFactory factory = SpringModules.noConfig("order-spring")
        .annotatedClasses(OrderConfig.class)
        .expose(OrderService.class)
        .closer(context -> Closeables.closeQuietly(context))
        .build();
```

宿主持有的外部配置对象和外部依赖不会交给 Spring destroy；Knotra 只释放子容器创建并拥有的对象。

## Spring 宿主动态桥

宿主单例需要调用动态能力时，`SpringDynamicBridge` 让宿主对象不必为每次提供方替换重启：

```java
try (KnotraRuntime runtime = KnotraRuntime.create();
     SpringDynamicBridge<PaymentGateway> bridge = SpringDynamicBridge.mount(
             runtime,
             "payment-bridge",
             CapabilityKey.of("payment.gateway", PaymentGateway.class),
             CapabilityKey.of("payment.host-bridge", PaymentGateway.class))) {

    PaymentGateway gateway = bridge.proxy();
    ChargeResult result = gateway.charge(request);
}
```

- `proxy()`：每个接口方法独立选择当前 provider 并持有方法级租约。
- `withCurrent(callback)`：回调期间固定一个 provider。
- `withCurrentAsync(callback)`：租约延续到异步 stage 完成。
- `available()`：advisory 可用性检查。

多个方法必须一致观察同一个提供方时：

```java
Quote quote = bridge.withCurrent(current -> new Quote(
        current.price(order),
        current.promotion(order)));
```

## 选型

| 场景 | 选择 |
|---|---|
| 普通 POJO、构造器清晰 | Beans DSL |
| 构造参数多、团队偏好注解 | Beans processor |
| 需要 Spring 生态和子容器隔离 | `SpringModules` |
| 宿主 Spring 单例调用动态能力 | `SpringDynamicBridge` |
| 多个结构变更必须原子提交 | `runtime.advanced().transact(...)` |
| 直接实现生命周期或插件工厂 | Core SPI / PF4J SPI |

保持边界：

- 业务对象不保存 Runtime 句柄。
- 构造器只接收依赖和配置，不做 I/O；复杂启动放 initializer 或组件 start。
- 跨 Activation 必须释放的资源交给 AUTO lifecycle、Beans disposer 或 Spring closer。
- 固定依赖适合需要整体重建的一致性场景；动态代理适合无状态调用。
- 生产等待必须带 Duration 或 timeout。
