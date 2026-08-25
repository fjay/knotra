# Knotra

**应用不重启，组件可替换。**

Knotra 是面向 Java 21+ 的 JVM 动态组件运行时。应用在运行期间可以按需热替换业务算法、平滑升级插件、局部重启子系统或按租约/上下文切换底层服务；同时由底层运行时确定性地维护依赖代际绑定、在途流量排空（Drain）与资源逆序释放（LIFO）。

![Java](https://img.shields.io/badge/Java-21%2B-orange)
![Maven](https://img.shields.io/badge/Maven-3.9%2B-blue)
![Version](https://img.shields.io/badge/version-0.1.0--SNAPSHOT-blue)

> **项目状态**：`0.1.0-SNAPSHOT`。运行与构建需要 Java 21+ 与 Maven 3.9+。

---

# 背景

在传统的 JVM 开发体系中，当业务逻辑需要动态扩展或插件化热插拔时，常常面临四大核心困境：

1. **在途调用被粗暴中断**：直接替换对象或卸载 ClassLoader 时，正在执行业务逻辑的工作线程容易出现 `ClassNotFoundException`、空指针或状态撕裂。
2. **依赖版本难以感知与收敛**：底层基础服务或插件升级后，上层依赖它的组件无法自动且安全地感知并同步更新。
3. **类加载器泄漏导致 Metaspace OOM**：旧版本插件虽然从逻辑上卸载，但因静态字段、未关闭的线程池或未清理的生命周期钩子导致 ClassLoader 无法被 GC 回收。
4. **资源清理无序与死锁**：组件销毁顺序混乱，下游数据源已被关闭，上层组件仍在尝试刷盘。

虽然业界存在 Spring 动态配置、PF4J、OSGi 等方案，但在以下维度仍有明显局限：

| 维度 | Spring 动态配置 / 策略模式 | 传统 PF4J / 自定义 ClassLoader | OSGi 规范体系 | Knotra 动态运行时 |
|---|---|---|---|---|
| **热替换粒度** | 依赖配置中心刷新 Bean 内部字段，无法热卸载/替换类 | 插件级粗粒度加载，缺乏组件间微观依赖管理 | 模块（Bundle）级，概念与配置极其繁琐 | **细粒度槽位（Publication）与挂载点（Mount）** |
| **在途流量保护** | 无原生保护，并发请求可能读到半更新状态 | 强行卸载易引发在途请求异常 | 依赖生命周期钩子，排空逻辑需自行实现 | **原生非阻塞排空（Drain Lease）**：新老流量平滑交接 |
| **依赖感知与收敛** | 需手写监听器重新注入或刷新 Context | 无法自动感知跨插件细粒度依赖变化 | 依赖解析复杂，状态转换容易卡死 | **代际依赖跟踪**：提供方更新后自动透明路由或原子重建 |
| **ClassLoader 回收** | 不涉及类卸载 | 极易发生类加载器引用泄漏导致 OOM | 机制沉重，学习曲线陡峭 | **纯数据快照与有界诊断**：框架不持久持有类引用，保证彻底 GC |
| **上手门槛** | 低 | 中 | 极高 | **低**：提供与 Spring / POJO 高度贴合的 Fluent DSL |

我们希望有一种优雅、通用且低侵入的方式，让 JVM 内部的动态热替换如同现代微服务发布一样平滑可控，这就是 Knotra 设计的目的。

---

# 设计

## 设计理念

现实世界中的许多经典系统为我们提供了灵感：

* **电视机与电视频道**：电视机的频道（如 CCTV-1）是长期存在的稳定槽位；发布者可以随时向频道推送新一期节目，观众始终认准该频道，内容无缝演进。
* **墙上的多功能插座**：插座的位置与身份长期固定；插在插座上的电器无论如何更换或重启，插座的身份始终不变。
* **前台总机电话**：外界拨打总机电话，总机透明地将通话路由到当前值班的最新业务员，无需外界关心人员轮换。

映射到 Knotra 运行时：

- **服务契约（Capability）**：定义“提供什么能力”，不绑定具体实现类。
- **发布槽位（Publication）**：长期存在的服务槽位，负责接收 `update` 原地升级。
- **逻辑挂载点（MountHandle）**：组件附加在运行时树上的稳定逻辑位置。
- **激活代（Activation）**：挂载点在某一确定代际下的具体启动实例与配置。
- **代际收敛（Settlement）**：单次发布/事务引起的依赖传播、在途排空与拓扑收敛。
- **动态代理注入（`Beans.dynamic`）**：消费方自动透明路由至最新提供方，提供方升级时消费方无需重启。
- **固定代际绑定（`Beans.fixed`）**：消费方绑定特定代际，提供方升级时消费方自动安全重建。

使用这种模式，我们可以得到：

- 职责清晰单一、易于测试的组件
- 零停机、秒级平滑生效的业务策略
- 在途流量无损排空与严格 LIFO 资源逆序释放
- 彻底无残留的插件 ClassLoader 垃圾回收

## 设计目标

- **轻量细粒度契约**：使用强类型契约解耦服务提供方与消费方。
- **声明式 Fluent DSL**：与 Java POJO 及 Spring 生态无缝贴合，提供声明式依赖编排。
- **多种依赖注入模式**：支持动态透明代理、显式一致性租约、固定代际绑定与可选依赖。
- **原生双重租约与排空**：方法级租约与跨方法一致性租约，支持在途流量非阻塞排空（Drain）。
- **确定性资源逆序释放**：托管资源的生命周期，在组件卸载或重建时严格按 LIFO 逆序销毁。
- **完整插件工程化**：基于 PF4J 提供插件隔离加载、版本平滑升级、声明式调和与 ClassLoader 彻底 GC 回收。
- **清晰三层 API 划分**：面向业务的 Simple API、面向架构师的 Advanced API 与面向插件作者的 SPI。
- **纯数据快照与全链路诊断**：无对象引用的快照（`RuntimeSnapshot`）与挂起操作诊断（`pendingOperations`）。

## 核心概念对照

| 核心概念 | 官方语义 | 直觉比喻 | 业务理解 |
|---|---|---|---|
| **`Capability`** | 类型化命名服务契约 | **电视频道协议** | 定义服务能力，不绑定具体实现。 |
| **`Publication<T>`** | 稳定发布槽位 | **电视机频道** | 长期存在的稳定槽位，支持随时推送新版本。 |
| **注册代际（registration generation）** | 内核已提交的具体能力代际 | **当前播放的具体节目** | `Publication.update` 推进代际；旧代际随排空退场。 |
| **`MountHandle`** | 稳定的逻辑挂载点 | **墙上的多功能插座** | 业务逻辑挂载的位置，多次重启或重配置身份不变。 |
| **`Activation`** | 一次运行时的激活尝试 | **通电运转的电器** | 每次启动产生的具体实例，捕获当时的依赖与配置。 |
| **`Settlement`** | 单次操作的传播与排空收敛 | **变更平滑生效并稳定** | 描述单次变更引起的依赖传播、排空与子树初始化全部完成。 |
| **`Beans.dynamic()`** | 动态接口代理注入 | **前台总机电话** | 自动路由到最新实现；提供方升级时消费方无需重启。 |
| **`Beans.fixed()`** | 固定代际依赖注入 | **签署固定版本合同** | 绑定提供方特定代际；提供方升级时消费方安全重建。 |

---

# 文档导航

- [快速开始](docs/quick-start.md)：从依赖引入、契约定义、组件装配到平滑热替换的完整路径
- [Beans 装配](docs/beans-guide.md)：Beans DSL、6 种依赖注入模式、生命周期管理与编译期注解处理器
- [Spring 集成](docs/spring-guide.md)：Spring 子容器隔离挂载与 Spring Boot 宿主单例动态桥
- [插件工程](docs/plugin-guide.md)：三层工程结构、受控工厂导出、声明式调和与 ClassLoader 防泄漏红线
- [运行时内核](docs/runtime-kernel.md)：领域模型、API 三层体系、高级结构事务、Context 树与事件总线
- [生产实践与排障](docs/production-practice.md)：执行边界、超时预算、优雅停机、挂起诊断、测试规范与 FAQ
- [实战案例](docs/case-sample.md)：动态营销折扣引擎、一致性租约、批量结算与多租户灰度隔离

---

# 极速上手

## 1. 安装与依赖引入

当前仓库未发布到公开中心仓库，请先从源码构建安装到本地 Maven 仓库：

```bash
git clone <repository-url>
cd knotra
mvn clean install
```

在应用的 `pom.xml` 中引入 BOM 与 Starter 模块：

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

## 2. 定义业务契约与初始实现

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

## 3. 发布、装配与平滑热替换

以下为核心流程摘要（完整可执行代码见 [QuickStartExample.java](knotra-docs-examples/src/test/java/io/knotra/docs/QuickStartExample.java)）：

```java
try (KnotraRuntime runtime = KnotraRuntime.create()) {
    // 1. 发布 v1 版本实现到根上下文
    PublicationChange<Greeting> firstChange =
            runtime.publish(Greeting.class, new ConstantGreeting("v1"));
    Publication<Greeting> greeting = firstChange.publication();
    firstChange.awaitSettled(Duration.ofSeconds(10));

    // 2. 挂载动态依赖 Greeting 的渲染组件
    var greetingDep = Beans.dynamic(Greeting.class);
    MountHandle renderer = Beans
            .component("greeting-renderer")
            .with(greetingDep)
            .create(deps -> new GreetingRenderer(deps.get(greetingDep)))
            .provideAs(RenderedGreeting.class)
            .mount(runtime);
    renderer.requireActive(Duration.ofSeconds(10));

    // 3. 第一次调用：执行 v1 逻辑
    String first = runtime.require(RenderedGreeting.class).render("Knotra");

    // 4. 热替换为 v2 实现（渲染组件保持在线，无需重启）
    SettlementReport report = greeting.update(new ConstantGreeting("v2"))
            .awaitSettled(Duration.ofSeconds(10));

    if (report.hasFailedMounts()) {
        throw new IllegalStateException(report.failedMounts().toString());
    }

    // 5. 第二次调用：透明路由到 v2 逻辑
    String second = runtime.require(RenderedGreeting.class).render("Knotra");
}
```

控制台输出：

```text
v1: Hello, Knotra
v2: Hello, Knotra
renderer instances: 1
```

**关键语义说明**：
- `runtime.publish(...)`：发布服务能力并返回单次变更句柄 `PublicationChange<T>`；其 `publication()` 返回长期稳定的发布槽位 `Publication<T>`。
- `Beans.dynamic(Class<T>)`：为消费方注入透明接口代理。当提供方从 v1 升级到 v2 时，消费方 Bean 实例不需要销毁重建，后续调用自动路由到新实现。
- `Beans.component(...).mount(runtime)`：声明式完成 Bean 的依赖绑定与挂载，返回挂载点句柄 `MountHandle`。
- `greeting.update(...)`：原地升级槽位实现，返回对应的变更观察句柄，通过 `awaitSettled(timeout)` 等待本次变更在整个运行时的状态收敛。
- `renderer.requireActive(Duration.ofSeconds(10))`：显式断言挂载点处于 ACTIVE 活跃状态，非活跃时抛出带有精确诊断信息的 `MountNotActiveException`。
- **生产停机建议**：入门示例使用 `try-with-resources` 进行资源收尾，其 `close()` 方法为无界阻塞等待；生产环境中建议调用 `runtime.closeAsync()` 并配合带超时预算的 `get(timeout)`，详见 [线程模型与生产实践](docs/production-practice.md)。

---

# API 三层分层体系

Knotra 将功能划分为清晰的三层，避免高级概念干扰日常业务开发：

| 层次 | 目标使用者 | 核心类与入口 | 设计边界与原则 |
|---|---|---|---|
| **Simple API** | 日常业务开发 | `KnotraRuntime`、`Publication`、`Beans`、`MountHandle` | 零事务概念、无原始代际操纵、无底层配置占位类型，开箱即用。 |
| **Advanced API** | 平台与框架架构师 | `runtime.advanced()` | 支持多操作结构事务、多租户 Context 树与全量纯数据快照。注册代际是内核概念；发布走 `Publication`，事务内暂存走 `tx.provide`。 |
| **SPI** | 插件与扩展开发者 | `Component`、`ComponentFactory`、`ActivationContext` | 直接实现底层生命周期，管理原生资源并遵循 ClassLoader 隔离规则。 |

---

# 模块分工

| 模块名 | 职责与定位 | 核心特性 |
|---|---|---|
| `knotra-bom` | 版本依赖集中管理 BOM | 统一定义组件依赖版本 |
| `knotra-starter` | 普通应用快速接入 Starter | 聚合 Core 与 Beans 模块 |
| `knotra-core` | 运行时内核 | Publication、Mount、事务、生命周期与快照 |
| `knotra-beans` | POJO 声明式装配 Fluent DSL | 6 种依赖模式、生命周期钩子、配置型 Bean |
| `knotra-beans-processor` | 编译期注解处理器 | 编译期生成工厂代码，零运行时反射 |
| `knotra-events` | 进程内类型化事件总线 | 5 种分发模式（Sync/Parallel/Serial/Bail/Waterfall）、在途排空 |
| `knotra-spring` | Spring 容器集成适配器 | 独立子容器隔离挂载、宿主单例动态桥接 |
| `knotra-spring-starter` | Spring 应用快速接入 Starter | 聚合 Starter 与 Spring 适配 |
| `knotra-pf4j-spi` | 插件受控工厂 SPI | 插件导出组件工厂契约 |
| `knotra-pf4j` | PF4J 插件动态加载与隔离 | 插件加载、在途排空、ClassLoader 隔离与回收 |
| `knotra-loader` | 声明式期望树调和器 | 期望组件拓扑树 Diff 与原子收敛 |
| `knotra-pf4j-loader` | PF4J 插件目录桥接 | 插件目录解析至 Loader 期望树 |
| `knotra-pf4j-starter` | 插件化应用一站式 Starter | 聚合 PF4J 与 Loader 模块 |
| `knotra-integration-tests` | 跨模块全链路集成测试 | 相关全模块 |
| `knotra-docs-examples` | 文档示例代码与权威规范守卫测试 | Starter、JUnit |

---

# 完整验证

在项目根目录下执行：

```bash
mvn clean verify
```

构建过程会执行全模块单元测试、PF4J 动态插件构建与卸载验证、Spring 子容器热重载、并发排空、动态代理租约以及 ClassLoader GC 回收断言。
