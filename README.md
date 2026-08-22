# Knotra

**应用不重启，组件可替换。**

Knotra 是面向 Java 21+ 的 JVM 动态组件运行时。应用启动后仍可以热替换业务实现、升级插件、局部重启或按上下文切换服务；底层同时维护依赖绑定代际、在途调用排空和确定性资源释放。

![Java](https://img.shields.io/badge/Java-21%2B-orange)
![Maven](https://img.shields.io/badge/Maven-3.9%2B-blue)
![Version](https://img.shields.io/badge/version-0.1.0--SNAPSHOT-blue)

> **项目状态**：`0.1.0-SNAPSHOT`。需要 Java 21+ 与 Maven 3.9+。

---

## API 分层

Knotra 把日常业务、结构控制和框架扩展分成三层：

| 层次 | 使用者 | 典型入口 | 边界 |
|---|---|---|---|
| Simple API | 业务应用 | `KnotraRuntime`、`Publication`、`Beans`、`MountHandle` | 不出现事务、raw registration、配置占位类型或组件 SPI |
| Advanced API | 平台和应用框架作者 | `runtime.advanced()` | 事务、具体代际 `Registration`、Context 变更和完整快照 |
| SPI | Knotra 扩展与插件作者 | `Component`、`ActivationContext` | 直接实现生命周期，遵守资源与 ClassLoader 规则 |

普通业务代码建议只依赖第一层。需要一次提交多个结构变更时，再进入 [API 与集成指南](<docs/Knotra API 与集成指南.md>) 的 Advanced 章节。

---

## 为什么需要动态组件运行时

把新字节码加载进 JVM 并不难，难的是替换发生时保持一致：

- **初始化隔离**：新组件完全启动并验证前不可见；失败则回滚本批变更。
- **在途调用排空**：旧组件停止承接新流量，已有调用和异步任务完成后才释放。
- **依赖代际一致**：固定依赖的消费方绑定启动时代际；提供方替换后按原子计划重建。
- **确定性资源清理**：Activation 登记的资源按 LIFO 释放，失败状态可重试。

---

## 极速上手

### 安装 Knotra

当前仓库没有配置公开 SNAPSHOT 仓库。先从源码安装到本机 Maven 仓库：

```bash
git clone <repository-url>
cd knotra
mvn clean install
```

然后在应用 POM 中导入 BOM 并引入 Starter：

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

贡献者验证使用完整 reactor：`mvn clean verify`。它会执行各模块测试、插件 fixture 构建和文档示例，不需要先部署任何构件。

### 定义业务契约

```java
public interface Greeting {
    String greet(String name);
}

public interface RenderedGreeting {
    String render(String name);
}

public record ConstantGreeting(String version) implements Greeting {
    @Override
    public String greet(String name) {
        return version + ": Hello, " + name;
    }
}
```

`Greeting.class` 会生成默认能力名 `io.knotra.docs.QuickStartExample$Greeting`。同一类型需要多个槽位时，才显式使用 `CapabilityKey.of("payment.primary", PaymentGateway.class)`。

### 发布与装配

下面是 README 的唯一 Quick Start 真源摘要；完整可执行代码见 [QuickStartExample.java](knotra-docs-examples/src/test/java/io/knotra/docs/QuickStartExample.java)。

```java
try (KnotraRuntime runtime = KnotraRuntime.create()) {
    PublicationChange<Greeting> firstChange =
            runtime.publish(Greeting.class, new ConstantGreeting("v1"));
    Publication<Greeting> greeting = firstChange.publication();

    SettlementReport firstReport = firstChange.awaitSettled(Duration.ofSeconds(10));

    BeanDefinition<GreetingRenderer> definition = Beans
            .component("greeting-renderer")
            .with(Beans.dynamic(Greeting.class))
            .create((Greeting current) -> new GreetingRenderer(current))
            .provideAs(RenderedGreeting.class, renderer -> renderer)
            .build();

    MountHandle renderer = definition.mount(runtime);
    renderer.requireActive(Duration.ofSeconds(10));

    String first = runtime.root().view()
            .require(RenderedGreeting.class)
            .render("Knotra");

    PublicationChange<Greeting> secondChange =
            greeting.update(new ConstantGreeting("v2"));
    SettlementReport report = secondChange.awaitSettled(Duration.ofSeconds(10));

    if (report.hasFailedMounts()) {
        throw new IllegalStateException(report.failedMounts().toString());
    }
    if (report.allActive()) {
        // 非空影响集里的所有挂载都是 ACTIVE。
    }

    renderer.requireActive(Duration.ofSeconds(10));
    String second = runtime.root().view()
            .require(RenderedGreeting.class)
            .render("Knotra");
}
```

执行结果：

```text
v1: Hello, Knotra
v2: Hello, Knotra
renderer instances: 1
```

这段代码的关键语义：

- `runtime.publish(...)` 返回这一次发布的 `PublicationChange<T>`，不要把它当成长期句柄保存。
- `change.publication()` 返回稳定发布槽位 `Publication<T>`，后续 `update(...)` 都从它发起。
- `Beans.dynamic(Class<T>)` 注入动态代理。提供方从 v1 换到 v2 时，消费方 Bean 不重建，仍是同一个实例。
- `awaitSettled(Duration)` 表示本次发布操作的传播和 drain 已收敛，并递归等待本次操作触发的 owned children；不表示所有下游一定成功。
- `hasFailedMounts()` 为 `false` 只表示本次影响集中没有 FAILED。
- `allActive()` 只有在影响集非空且全部 ACTIVE 时才为 `true`。空影响集返回 `false`；本例的动态代理消费方不需要重建，因此通常不在影响集中。
- `renderer.requireActive(Duration.ofSeconds(10))` 才是在等待“这个具体挂载当前 ACTIVE”，失败会抛出含诊断的 `MountNotActiveException`。
- 示例用 try-with-resources 保持简洁；它调用的 `close()` 会无界等待停机收敛。生产代码应等待 `runtime.closeAsync()` 并使用有界 `get(timeout)`，见[线程模型与生产实践](<docs/Knotra 线程模型与生产实践.md>)。

---

## 核心词汇

| 词汇 | 含义 |
|---|---|
| `Capability` | 类型化命名值，由 `CapabilityKey<T>` 或 `Class<T>` 默认 key 标识 |
| `Publication<T>` | 一个 Context 内的稳定发布槽位 |
| `Registration<T>` | 一个已提交的能力代际；替换会产生新的 Registration |
| `MountHandle` | 稳定逻辑挂载点；生命周期方法返回 `ComponentState` |
| `ConfiguredMountHandle<C>` | 公开配置契约的挂载点，支持 `reconfigureAsync(C)` |
| `Activation` | 挂载点的一次启动尝试及其固定绑定集 |
| `SettlementReport` | 单次发布、注册或事务操作的传播与排空结果 |
| `FailureInfo` | 有界纯文本失败详情，不持有异常对象或 ClassLoader |

设计决策见 [ADR 0001](docs/adr/0001-publication-registration-mount-activation.md)。

---

## 文档导航

- [API 与集成指南](<docs/Knotra API 与集成指南.md>)：Simple/Advanced/SPI 分层、事务、上下文、事件、PF4J 与 Loader。
- [Beans 与 Spring 集成指南](<docs/Knotra Beans 与 Spring 集成指南.md>)：POJO DSL、注解处理器、Spring 子容器与动态桥。
- [实战案例：动态营销折扣引擎](<docs/Knotra 实战案例：动态营销折扣引擎.md>)：折扣策略热替换与平滑切换。
- [插件工程化手册](<docs/Knotra 插件工程化手册.md>)：共享契约、PF4J 导出、Loader 声明树与卸载防护。
- [线程模型与生产实践](<docs/Knotra 线程模型与生产实践.md>)：生命周期执行边界、TCCL、优雅停机与监控。
- [测试指南](<docs/Knotra 测试指南.md>)：异步测试、失败清理、诊断和 ClassLoader GC。
- [FAQ 与排障指南](<docs/Knotra FAQ 与排障指南.md>)：状态机、settlement、诊断码与常见问题。

---

## 模块分工

| 模块名 | 职责与定位 | 编译依赖 |
|---|---|---|
| `knotra-bom` | 版本统一 BOM | 无 |
| `knotra-starter` | 普通应用聚合依赖 | Core、Beans |
| `knotra-core` | Publication、Registration、Mount、事务、生命周期与快照 | 无外部运行时依赖 |
| `knotra-beans` | POJO 构造器注入与生命周期适配 DSL | Core |
| `knotra-beans-processor` | 编译期注解处理器 | Core、Beans |
| `knotra-events` | 类型化、可排空的进程内事件总线 | Core |
| `knotra-spring` | Spring 子容器与宿主动态代理桥 | Core、Spring Context |
| `knotra-spring-starter` | Spring 应用聚合依赖 | Starter、Spring |
| `knotra-pf4j-spi` | 插件导出的受控工厂 SPI | Core、PF4J provided |
| `knotra-pf4j` | PF4J artifact 加载、排空与类加载器防护 | Core、SPI、PF4J、ASM |
| `knotra-loader` | 声明式期望树对比与收敛 | Core |
| `knotra-pf4j-loader` | PF4J catalog 到 Loader 的解析桥 | Loader、PF4J |
| `knotra-pf4j-starter` | 插件化应用聚合依赖 | PF4J Loader |
| `knotra-integration-tests` | 跨模块真实链路验证 | 相关模块 |
| `knotra-docs-examples` | test-only canonical 示例与文档守卫 | Starter、JUnit |

---

## 构建与验证

```bash
mvn clean verify
```

测试覆盖真实 PF4J 插件构建与卸载、Spring 子容器重载、并发排空、动态代理租约、诊断保留和 ClassLoader 回收。测试数量随模块演进变化，以命令结果为准。
