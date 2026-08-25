# 快速开始

## 引入依赖

当前仓库版本为 `0.1.0-SNAPSHOT`。使用前请先从源码构建安装到本地 Maven 仓库：

```bash
git clone <repository-url>
cd knotra
mvn clean install
```

在业务项目的 `pom.xml` 中引入 BOM 与 Starter：

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>io.knotra</groupId>
      <artifactId>knotra-bom</artifactId>
      <version>0.1.0-SNAPSHOT</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>

<dependencies>
  <dependency>
    <groupId>io.knotra</groupId>
    <artifactId>knotra-starter</artifactId>
  </dependency>
</dependencies>
```

> Knotra 运行与构建需要 **Java 21+** 与 **Maven 3.9+**。

## 定义业务契约与实现

定义服务契约接口与业务实现类（遵循纯 POJO 规范）：

```java
// 1. 底层服务契约
public interface Greeting {
    String greet(String name);
}

// 2. 上层渲染服务契约
public interface RenderedGreeting {
    String render(String name);
}

// 3. 初始 v1 实现
public record ConstantGreeting(String version) implements Greeting {
    @Override
    public String greet(String name) {
        return version + ": Hello, " + name;
    }
}

// 4. 依赖 Greeting 的消费方组件
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

## 声明与挂载组件

使用 `Beans` Fluent DSL 声明组件定义并挂载到运行时：

```java
// 1. 声明动态依赖：提供方升级时，消费方实例无需重建
var greetingDep = Beans.dynamic(Greeting.class);

// 2. 构建组件定义
BeanDefinition<GreetingRenderer> definition = Beans
        .component("greeting-renderer")
        .with(greetingDep)
        .create(deps -> new GreetingRenderer(deps.get(greetingDep)))
        .provideAs(RenderedGreeting.class)
        .build();
```

`BeanDefinition<T>` 是不可变且线程安全的，构建后可多次挂载或跨运行时复用。

## 启动运行时与热替换

完整运行与秒级热替换流程如下：

```java
try (KnotraRuntime runtime = KnotraRuntime.create()) {
    // 1. 发布 v1 版本实现到根上下文
    PublicationChange<Greeting> firstChange =
            runtime.publish(Greeting.class, new ConstantGreeting("v1"));
    Publication<Greeting> greeting = firstChange.publication();
    firstChange.awaitSettled(Duration.ofSeconds(10));

    // 2. 挂载渲染组件
    MountHandle renderer = definition.mount(runtime);
    renderer.requireActive(Duration.ofSeconds(10));

    // 3. 第一次调用：执行 v1 逻辑
    String first = runtime.require(RenderedGreeting.class).render("Knotra");
    System.out.println(first); // 输出: v1: Hello, Knotra

    // 4. 原地热替换为 v2 实现（渲染组件保持在线，无需重启）
    SettlementReport report = greeting.update(new ConstantGreeting("v2"))
            .awaitSettled(Duration.ofSeconds(10));

    if (report.hasFailedMounts()) {
        throw new IllegalStateException("升级失败: " + report.failedMounts());
    }

    // 5. 第二次调用：透明路由到 v2 逻辑
    String second = runtime.require(RenderedGreeting.class).render("Knotra");
    System.out.println(second); // 输出: v2: Hello, Knotra
}
```

关键语义说明：

- `runtime.publish(...)`：发布服务能力并返回单次变更观察句柄 `PublicationChange<T>`；其 `publication()` 返回长期稳定的发布槽位 `Publication<T>`。
- `Beans.dynamic(Class<T>)`：为消费方注入透明接口代理。当提供方从 v1 升级到 v2 时，消费方 Bean 实例不需要销毁重建，后续调用自动路由到新版本。
- `greeting.update(...)`：原地升级槽位实现，通过 `awaitSettled(timeout)` 等待本次变更在整个运行时的状态收敛。
- `renderer.requireActive(Duration.ofSeconds(10))`：显式断言挂载点处于 ACTIVE 活跃状态。

## 状态断言与结果检查

Knotra 提供了完善的状态与报告检查机制：

```java
// 1. 断言挂载点活跃（超时或失败时抛出 MountNotActiveException）
renderer.requireActive(Duration.ofSeconds(10));

// 2. 异步等待挂载点自身状态过渡
ComponentState state = renderer.whenSettled()
        .toCompletableFuture()
        .get(10, TimeUnit.SECONDS);

// 3. 检查变更收敛报告
SettlementReport report = change.awaitSettled(Duration.ofSeconds(10));
if (report.hasFailedMounts()) {
    // 处理失败的下游挂载点
    System.err.println("失败挂载点列表: " + report.failedMounts());
}
```

挂载点状态流转说明：

- `WAITING`：缺少依赖，等待依赖提供方就绪
- `STARTING`：正在执行实例化与初始化
- `ACTIVE`：已激活并对外正常暴露服务能力
- `STOPPING`：正在进行在途流量排空与资源逆序释放
- `FAILED`：激活或清理失败，可通过 `retryAsync()` 重新尝试
- `DISPOSED`：挂载点已终态释放

## 依赖模式速选

根据业务组件是否包含内部状态，选择合适的依赖注入模式：

```java
// 模式 A：动态代理（默认推荐，无状态策略热插拔）
// 提供方升级时，消费方实例不重启，调用自动路由到最新实现
var strategy = Beans.dynamic(DiscountStrategy.class);

// 模式 B：固定代际（有状态组件、批量结算 Job）
// 提供方升级时，消费方会自动进行安全销毁与重建
var jobDep = Beans.fixed(PricingEngine.class);
```

完整依赖模式请参考 [Beans 装配](beans-guide.md)。

## 生产优雅停机建议

入门示例中使用 `try-with-resources` 进行资源收尾，其 `close()` 方法为无界阻塞等待。

在生产环境应用停机钩子（Shutdown Hook）中，**坚决禁止无界等待**，推荐使用带超时预算的异步关闭：

```java
// 生产环境带超时停机
runtime.closeAsync()
        .toCompletableFuture()
        .get(30, TimeUnit.SECONDS);
```

若在停机过程中发生超时，可通过 `pendingOperations()` 采样当前挂起操作快照进行精准诊断，详见 [生产实践与排障](production-practice.md)。

## 下一步

- 6 种依赖注入模式、配置型 Bean 与注解处理器：[Beans 装配](beans-guide.md)
- Spring 子容器挂载与 Spring Boot 宿主单例桥接：[Spring 集成](spring-guide.md)
- 插件三层工程、PF4J 隔离加载与声明式调和：[插件工程](plugin-guide.md)
- 运行时内核模型、结构事务与 Context 树：[运行时内核](runtime-kernel.md)
- 执行边界、停机诊断、测试骨架与排障手册：[生产实践与排障](production-practice.md)
- 电商营销折扣引擎与一致性租约实战：[实战案例](case-sample.md)
