# Spring 容器集成

`knotra-spring` 模块提供了与 Spring 框架的双向集成能力：
1. **子容器隔离挂载**：将独立的 Spring `AnnotationConfigApplicationContext` 作为 Knotra 挂载点，支持依赖双向注入、配置热重载与完整的子容器生命周期隔离。
2. **宿主动态桥接（SpringDynamicBridge）**：在传统 Spring Boot 单例宿主中，将动态插拔的 Knotra 服务透明桥接为标准 Spring Bean，实现宿主单例零重启调用热更插件。

## 引入依赖

```xml
<dependency>
  <groupId>io.knotra</groupId>
  <artifactId>knotra-spring</artifactId>
</dependency>
```

## 概念与 Spring 术语对照

| Spring 概念 | Knotra 对应概念 | 详细说明 |
|---|---|---|
| Service 接口 | `CapabilityKey<T>` / `Class<T>` | 服务的类型化契约身份。 |
| Singleton Bean 实例 | `Publication<T>` 槽位 | 长期稳定的服务槽位，支持原地升级替换。 |
| `@Bean` / Component 定义 | `BeanDefinition<T>` | 无配置的普通组件定义，描述依赖与构造方式。 |
| 带 `@ConfigurationProperties` 的 Bean | `ConfiguredBeanDefinition<C, T>` | 显式声明配置类型契约的组件定义。 |
| Bean 在 ApplicationContext 中的注册 | `MountHandle` | 稳定的逻辑挂载点句柄，支持重试、状态查询与释放。 |
| 热重载 / 刷新配置 | `ConfiguredMountHandle.reconfigureAsync(C)` | 动态更新配置并重新激活组件。 |

## 挂载无配置 Spring 模块

将一个包含 `@Configuration` 配置类的 Spring 模块打包并挂载到 Knotra 中：

```java
@Configuration
public class OrderModuleSpringConfig {

    @Bean
    public OrderService orderService(Pricing pricing, PaymentGateway gateway) {
        return new DefaultOrderService(pricing, gateway);
    }
}
```

使用 `SpringModules.noConfig` 声明并挂载模块：

```java
MountFactory springModule = SpringModules.noConfig("order-module")
        .annotatedClasses(OrderModuleSpringConfig.class)
        // 1. 将 Knotra 运行时中的能力注入到 Spring 子容器中
        .required("pricing", Pricing.class)
        .dynamic("gateway", PaymentGateway.class)
        // 2. 将 Spring 子容器内部的 Bean 导出为 Knotra Capability
        .expose(OrderService.class)
        .build();

try (KnotraRuntime runtime = KnotraRuntime.create()) {
    // 挂载 Spring 模块
    MountHandle handle = runtime.mount("order-module", springModule);
    handle.requireActive(Duration.ofSeconds(10));

    // 从 Knotra 消费由 Spring 导出的能力
    OrderService orderService = runtime.require(OrderService.class);
}
```

说明：
- `.required(...)`：将 Knotra 中固定代际的能力以单例形式注册进 Spring 子容器。
- `.dynamic(...)`：将 Knotra 中的能力以透明动态代理形式注册进 Spring 子容器。
- `.expose(...)`：在 Spring 子容器完成 `refresh()` 后，提取指定 Bean 并向 Knotra 暴露。

## 挂载配置型 Spring 模块

当 Spring 模块依赖特定配置类且支持在线热重载时，使用 `SpringModules.typed`：

```java
public record OrderProperties(String env, int poolSize) {}

@Configuration
public class ConfiguredOrderSpringConfig {
    @Autowired
    private OrderProperties orderProperties;

    @Bean
    public OrderService orderService() {
        return new ConfiguredOrderService(orderProperties);
    }
}
```

声明与热重载操作：

```java
var configuredSpring = SpringModules.typed("order-module", OrderProperties.class)
        .annotatedClasses(ConfiguredOrderSpringConfig.class)
        .configBeanName("orderProperties")
        .expose(OrderService.class)
        .build();

// 1. 挂载初始配置
ConfiguredMountHandle<OrderProperties> handle = runtime.mount(
        "order-module",
        configuredSpring,
        new OrderProperties("prod", 10));
handle.requireActive(Duration.ofSeconds(10));

// 2. 运行期间热重载 Spring 子容器配置
handle.reconfigureAsync(new OrderProperties("peak-load", 50))
        .toCompletableFuture()
        .get(15, TimeUnit.SECONDS);
```

重载时，Knotra 会安全触发旧 Spring 子容器的 `close()`，并使用新配置实例化全新的 ApplicationContext 执行 `refresh()`，完成平滑过渡。

## Spring 宿主动态桥 (SpringDynamicBridge)

在微服务架构中，宿主通常是一个庞大的 Spring Boot 应用。如果宿主内的某个单例 Bean 需要调用可能会随时热更新的 Knotra 插件能力，使用 `SpringDynamicBridge`：

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

### 业务使用

宿主业务类直接注入普通的 Spring 接口：

```java
@Service
public class CheckoutBusinessService {

    @Autowired
    private PaymentGateway paymentGateway;

    public void checkout(Order order) {
        // 直接调用；底层插件热升级时，CheckoutBusinessService 无需重启，调用自动路由到新实现
        paymentGateway.pay(order.amount());
    }
}
```

## 生命周期与隔离性

1. **容器生命周期隔离**：每个 Spring 子容器拥有独立的 BeanFactory 与 ClassLoader，子容器的刷新与关闭完全独立于宿主。
2. **销毁安全性**：当子容器被释放或重建时，Knotra 确保调用 ApplicationContext 的 `close()`，依次触发 `@PreDestroy` 钩子与 `DisposableBean` 清理。
3. **类加载器防污染**：子容器内部扫描的类与第三方库不会泄露到宿主环境中。

## 场景选型速查

| 业务场景 | 推荐方案 |
|---|---|
| 普通 Java POJO，结构清晰轻量 | `knotra-beans` Fluent DSL |
| 依赖参数较多、倾向于注解驱动 | `knotra-beans-processor` 编译期注解处理器 |
| 需要使用 MyBatis、Spring Data 等 Spring 完整生态且需插件隔离 | `SpringModules` 子容器挂载 |
| 宿主 Spring Boot 单例调用动态热插拔插件 | `SpringDynamicBridge` 宿主动态桥 |
| 批量跑批、计费任务（需锁定固定代际） | `Beans.fixed(...)` / `SpringModules.required(...)` |
| 营销策略、计价引擎、风控规则热插拔 | `Beans.dynamic(...)` / `SpringModules.dynamic(...)` |

## 下一步

- 插件三层工程、PF4J 隔离加载与声明式调和：[插件工程](plugin-guide.md)
- 运行时内核模型、结构事务与 Context 树：[运行时内核](runtime-kernel.md)
- 线程模型、超时预算与停机诊断：[生产实践与排障](production-practice.md)
