# Knotra Beans 与 Spring 集成指南

> 面向 Java 与 Spring 开发者，介绍如何使用 Knotra 管理普通 POJO 对象图以及 Spring 模块的动态生命周期。

---

## 架构定位与职责划分

Spring 容器是静态单例模型的代表：容器在应用启动时构建完整的 Bean 依赖图，运行期间不再轻易变动。当业务需要在线热替换某个实现、动态加载外部插件或局部重建故障模块时，原生 Spring 无法提供在途请求排空和依赖代际隔离的保证。

Knotra 与 Spring 采用分层协作架构：

- **Spring**：负责模块内部的 Bean 装配、AOP 增强、事务管理以及生态集成。
- **Knotra**：作为外层动态调度器，负责管理 Spring 子容器或纯 POJO 组件的挂载、热重载、依赖代际传播与安全排空。

```mermaid
graph TB
    subgraph knotra_runtime ["Knotra 运行时"]
        direction TB
        K["Knotra 事务与生命周期调度"]

        subgraph mode_pojo ["纯 POJO 模式"]
            P["POJO 业务类: 零框架侵入"]
        end

        subgraph mode_spring ["Spring 子容器模式"]
            SC["Spring Child Context: 独立子容器"]
            SC --> B1["Service"]
            SC --> B2["Repository"]
        end

        subgraph mode_bridge ["宿主动态桥模式"]
            SDB["SpringDynamicBridge 动态代理"]
        end
    end

    HostSpring["宿主 Spring Boot 单例"] -->|注入代理| SDB
    SDB -->|动态路由| SC
```

---

## 核心概念对照

| Knotra 概念 | Spring 对应物 / 映射 | 机制与职责 |
|---|---|---|
| **`CapabilityKey<T>`** | 类型化 Bean 标识 | 由名称与 Java `Class<T>` 唯一定义的服务契约，跨模块共享。 |
| **`ComponentHandle<C>`** | 稳定挂载点句柄 | 逻辑挂载点，热替换期间句柄身份保持不变。 |
| **`Activation`** | 容器运行代际（代际实例） | 某一次实际运行的 Bean 实例或 Spring 容器；依赖更新时旧代际销毁、新代际启动。 |
| **`PINNED` 依赖** | 静态注入（类似普通 `@Autowired`） | 启动时固化依赖；当被依赖方替换时，消费方整机重建以加载新依赖。 |
| **`DYNAMIC` 依赖** | 动态代理注入 | 注入方法级代理，每次调用解析最新实现，底层替换时消费方不重启。 |
| **`External Singleton`** | 外部借入单例 | 宿主借给 Spring 子容器使用的对象；子容器销毁时不会触发其释放逻辑。 |
| **`LifecycleScope`** | 资源作用域（LIFO） | 托管该代际创建的所有底层资源，销毁时按后进先出严格逆序释放。 |

---

## Maven 依赖配置

在多模块工程的 `pom.xml` 中先引入 BOM：

```xml
<properties>
  <knotra.version>0.1.0-SNAPSHOT</knotra.version>
</properties>

<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>io.knotra</groupId>
      <artifactId>knotra-bom</artifactId>
      <version>${knotra.version}</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
```

普通 POJO 装配只需引入 `knotra-starter`：

```xml
<dependencies>
  <dependency>
    <groupId>io.knotra</groupId>
    <artifactId>knotra-starter</artifactId>
  </dependency>
</dependencies>
```

需要 Spring 子容器与宿主桥接时，改用 `knotra-spring-starter`（已传递包含普通 Beans 能力，无需同时引入 `knotra-starter`）：

```xml
<dependencies>
  <dependency>
    <groupId>io.knotra</groupId>
    <artifactId>knotra-spring-starter</artifactId>
  </dependency>
</dependencies>
```

---

## 纯 POJO 业务组件装配（`knotra-beans`）

无需引入 Spring 容器即可实现类型安全的依赖注入与动态热重载。

### 编写业务类

业务类保持标准的 Java 对象结构，无需实现 Knotra 专有接口：

```java
public interface Greeting {
    String greet(String name);
}

public final class GreetingBean implements Greeting, AutoCloseable {
    @Override
    public String greet(String name) {
        return "Hello, " + name;
    }

    @Override
    public void close() {
        // 只负责释放该 Bean 自身持有的资源
    }
}
```

### 声明定义与挂载

通过 `Beans.component` DSL 声明依赖、构造方式以及对外暴露的能力：

```java
import io.knotra.*;
import io.knotra.beans.*;

static final CapabilityKey<Greeting> GREETING =
        CapabilityKey.of("app.greeting", Greeting.class);

BeanDefinition<NoConfig, GreetingBean> factory = Beans.component("greeting")
        .create(GreetingBean::new)
        .provide(GREETING)
        .build();

try (KnotraRuntime runtime = KnotraRuntime.create()) {
    ComponentHandle<NoConfig> handle = Beans.mount(runtime, factory);
    handle.requireActive();
}
```

### 依赖注入：Required 与 Optional

```java
static final CapabilityKey<UserRepository> USERS =
        CapabilityKey.of("app.users", UserRepository.class);
static final CapabilityKey<Metrics> METRICS =
        CapabilityKey.of("app.metrics", Metrics.class);
static final CapabilityKey<UserService> USER_SERVICE =
        CapabilityKey.of("app.user-service", UserService.class);

BeanDefinition<NoConfig, UserService> userDef = Beans.component("user-service")
        .with(
                Beans.required(USERS),    // 必需依赖：缺失时组件保持 WAITING
                Beans.optional(METRICS)   // 可选依赖：构造函数接收 Optional<Metrics>
        )
        .create((users, metrics) -> new UserService(users, metrics))
        .provide(USER_SERVICE)
        .build();
```

| 依赖声明语法 | 构造函数参数类型 | 提供方替换时的运行时行为 |
|---|---|---|
| `Beans.required(KEY)` | `T` | 默认 PINNED 模式：自动销毁旧实例，重新执行构造函数重建消费方。 |
| `Beans.optional(KEY)` | `Optional<T>` | 默认 PINNED 模式：注入最新 Optional，自动触发消费方重建。 |
| `Beans.dynamicProxyRequired(KEY)` | `T` (动态代理) | DYNAMIC 模式：消费方不重启，每次方法调用穿透到最新提供方。 |
| `Beans.dynamicRequired(KEY)` | `DynamicCapability<T>` | DYNAMIC 模式：消费方不重启，通过代码块显式获取调用租约。 |

---

### 动态依赖：调用时不重启消费方

适用于无状态网关或高频请求路由：

```mermaid
sequenceDiagram
    participant OrderService as 消费方 (OrderService)
    participant Proxy as 方法级 Proxy
    participant OldPay as 旧支付渠道 (V1)
    participant NewPay as 新支付渠道 (V2)

    OrderService->>Proxy: pay(100)
    Proxy->>OldPay: 转发调用 V1
    OldPay-->>OrderService: 支付成功

    Note over Proxy: 运行时热替换为 V2 渠道

    OrderService->>Proxy: pay(200)
    Proxy->>NewPay: 自动切换转发至 V2
    NewPay-->>OrderService: 支付成功 (OrderService 无需重启)
```

```java
static final CapabilityKey<PaymentGateway> PAYMENT =
        CapabilityKey.of("app.payment", PaymentGateway.class);

BeanDefinition<NoConfig, CheckoutService> checkoutDef = Beans.component("checkout")
        .with(Beans.dynamicProxyRequired(PAYMENT))
        .create(CheckoutService::new)
        .build();
```

---

## 编译期注解驱动（`knotra-beans-processor`）

对于构造参数较多或偏好注解声明的项目，可以使用编译期注解处理器在 `javac` 阶段生成无反射的工厂类。

### 配置编译器插件

```xml
<build>
  <plugins>
    <plugin>
      <groupId>org.apache.maven.plugins</groupId>
      <artifactId>maven-compiler-plugin</artifactId>
      <configuration>
        <annotationProcessorPaths>
          <path>
            <groupId>io.knotra</groupId>
            <artifactId>knotra-beans-processor</artifactId>
            <version>${knotra.version}</version>
          </path>
        </annotationProcessorPaths>
      </configuration>
    </plugin>
  </plugins>
</build>
```

### 标注业务类

```java
package com.example.service;

import io.knotra.beans.annotation.*;
import java.util.Optional;

@KnotraBean(id = "order-service")
@KnotraOutput(name = "app.order-service", contract = OrderService.class)
public final class DefaultOrderService implements OrderService {

    private final UserRepository users;
    private final Optional<Metrics> metrics;
    private final PaymentGateway payment;

    @KnotraConstructor
    DefaultOrderService(
            @KnotraRequire("app.users") UserRepository users,
            @KnotraOptional("app.metrics") Optional<Metrics> metrics,
            @KnotraDynamicProxy("app.payment") PaymentGateway payment) {
        this.users = users;
        this.metrics = metrics;
        this.payment = payment;
    }

    @KnotraInit
    void init() {
        // 初始化逻辑：此时资源清理已登记，输出尚未对外发布
    }

    @KnotraDestroy
    public void close() {
        // 幂等清理逻辑
    }
}
```

编译期处理器会在同包下生成 `DefaultOrderService_KnotraFactory.java`，直接调用即可完成挂载：

```java
DefaultOrderService_KnotraFactory factory = new DefaultOrderService_KnotraFactory();
ComponentHandle<NoConfig> handle = Beans.mount(runtime, factory.definition());
handle.requireActive();
```

---

## Spring 子容器动态插拔（`SpringModules`）

将一组包含多个 `@Service`、`@Repository` 的 Spring 配置作为独立模块在运行时动态加载与卸载。

### 架构机制

```mermaid
graph TB
    subgraph knotra_host ["Knotra 宿主"]
        KR["Knotra 运行时"]
        UP["宿主提供的 UserRepository 依赖"]
    end

    subgraph spring_child ["Spring Child Context 模块代际"]
        direction TB
        SCC["AnnotationConfigApplicationContext"]
        SCC -->|注册外部单例| EB["UserRepository Bean 引用"]
        SCC -->|容器内部创建| PS["PaymentService Bean"]
        EB -.-> PS
    end

    KR -->|生命周期控制| SCC
    UP -->|借入使用| EB
    PS -->|expose 发布服务| KP["PAYMENT_SERVICE 供外部调用"]
```

### 装配步骤

#### 编写子模块的 Spring 配置类

```java
package com.example.plugin;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PluginSpringConfig {

    @Bean
    public PaymentService paymentService(UserRepository users) {
        return new WechatPaymentService(users);
    }
}
```

#### 定义并挂载子容器组件

```java
import io.knotra.spring.SpringModules;

ComponentFactory<NoConfig> pluginFactory = SpringModules.noConfig("payment-plugin")
        .annotatedClasses(PluginSpringConfig.class)
        .required("users", USER_REPOSITORY_KEY)
        .expose(PAYMENT_SERVICE_KEY, "paymentService")
        .build();

ComponentHandle<NoConfig> pluginHandle = runtime.mount("payment-plugin", pluginFactory);
pluginHandle.requireActive();
```

运行时机制：
- Knotra 为该组件独立创建 `AnnotationConfigApplicationContext`。
- 将外部借入的 `UserRepository` 注册为 Spring 单例（External Singleton）。
- 执行 `refresh()` 启动容器，并将内部创建的 `paymentService` 发布为 Knotra 能力。
- 当外部依赖 `USER_REPOSITORY_KEY` 替换时，Knotra 会优雅关闭当前子容器并创建新代际。

---

## Spring 宿主动态桥（`SpringDynamicBridge`）

### 在宿主单例中调用动态插件

在 Spring Boot 宿主应用中，Controller 与 Service 属于静态单例。通过 `SpringDynamicBridge` 可以向 Spring 宿主注入一个永久有效的动态代理：

```java
@Configuration
public class HostSpringConfiguration {

    @Bean
    public SpringDynamicBridge<PaymentGateway> paymentBridge(KnotraRuntime runtime) {
        return SpringDynamicBridge.mount(
                runtime,
                "payment-bridge",
                DYNAMIC_PAYMENT_KEY,      // 插件实际发布的契约 Key
                SPRING_PAYMENT_KEY        // 暴露给 Spring 宿主的 Key
        );
    }

    @Bean
    public PaymentGateway paymentGateway(SpringDynamicBridge<PaymentGateway> bridge) {
        return bridge.proxy();
    }
}
```

在宿主业务代码中正常注入使用：

```java
@Service
public class OrderController {

    @Autowired
    private PaymentGateway paymentGateway;

    public void checkout(Order order) {
        // 底层插件热替换时，调用自动路由至最新可用的提供方
        paymentGateway.charge(order);
    }
}
```

---

## 常见场景选型决策

```mermaid
graph TD
    start_decision["装配方式选择"] --> q_spring{"是否使用 Spring 容器？"}

    q_spring -->|"否 (纯 POJO)"| q_args{"构造器参数数量？"}
    q_args -->|"0~5 个"| res_pojo["knotra-beans DSL<br/>(Beans.component)"]
    q_args -->|"较多 / 注解偏好"| res_proc["knotra-beans-processor<br/>(@KnotraBean)"]

    q_spring -->|"是 (Spring 模块)"| q_role{"在架构中的角色？"}
    q_role -->|"独立动态业务模块"| res_child["SpringModules 子容器<br/>(SpringModules.noConfig/typed)"]
    q_role -->|"宿主单例调用插件"| res_bridge["SpringDynamicBridge<br/>(bridge.proxy())"]
```

---

## 边界与核心避坑原则

- **避免子容器扫描宿主 Bean**：子容器配置类应保持独立，外部依赖必须通过 `.required("name", KEY)` 显式借入。
- **外部单例所有权隔离**：借入的 External Singleton 由宿主或外层提供方拥有，Spring 子容器销毁时不会触发它们的 `@PreDestroy` 或 `close()`。
- **多方法调用需使用显式租约**：动态代理仅保证单方法调用的原子路由。涉及多方法组合（如 `begin() -> process() -> commit()`）时，必须使用显式租约代码块：
  ```java
  bridge.withCurrent(gateway -> {
      gateway.begin();
      gateway.charge(order);
      gateway.commit();
      return null;
  });
  ```
