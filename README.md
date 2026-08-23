# Knotra

**应用不重启，组件可替换。**

Knotra 是面向 Java 21+ 的 JVM 动态组件运行时。应用在运行期间可以按需热替换业务算法、平滑升级插件、局部重启子系统或按租约/上下文切换底层服务；同时由底层运行时确定性地维护依赖代际绑定、在途流量排空（Drain）与资源逆序释放（LIFO）。

![Java](https://img.shields.io/badge/Java-21%2B-orange)
![Maven](https://img.shields.io/badge/Maven-3.9%2B-blue)
![Version](https://img.shields.io/badge/version-0.1.0--SNAPSHOT-blue)

> **项目状态**：`0.1.0-SNAPSHOT`。运行与构建需要 Java 21+ 与 Maven 3.9+。

---

## 为什么需要 Knotra

在传统 JVM 开发中，实现业务热插拔或插件化通常会遭遇四大难题：

1. **在途调用被粗暴中断**：直接替换对象或卸载 ClassLoader 时，正在执行的业务线程容易出现 `ClassNotFoundException` 或状态撕裂。
2. **依赖版本不一致**：底层组件升级后，上层依赖它的组件无法自动且安全地感知并同步更新。
3. **类加载器泄漏导致 Metaspace OOM**：旧插件虽然卸载，但因静态字段、线程池或未清理的生命周期钩子导致 ClassLoader 无法被 GC 回收。
4. **资源清理无序与死锁**：组件销毁顺序混乱，下游数据源已关闭，上层仍在刷盘。

Knotra 通过**稳定的发布槽位**、**代际一致性**、**非阻塞在途排空**以及**确定性 LIFO 资源释放**，让 JVM 内部的动态热替换如同现代微服务发布一样平滑可控。

---

## 与既有方案的对比

| 维度 | Spring 动态配置 / 策略模式 | 传统 PF4J / 自定义 ClassLoader | OSGi 规范体系 | Knotra 动态运行时 |
|---|---|---|---|---|
| **热替换粒度** | 依赖配置中心刷新 Bean 内部字段，无法热卸载/替换类 | 插件级粗粒度加载，缺乏组件间微观依赖管理 | 模块（Bundle）级，概念与配置极其繁琐 | **细粒度槽位（Publication）与挂载点（Mount）** |
| **在途流量保护** | 无原生保护，并发请求可能读到半更新状态 | 强行卸载易引发在途请求异常 | 依赖框架生命周期钩子，排空逻辑需自行实现 | **原生非阻塞排空（Drain Lease）**：新请求走新版本，老请求平滑处理完毕后释放 |
| **依赖感知与收敛** | 需手写监听器重新注入或刷新 Context | 无法自动感知跨插件细粒度依赖变化 | 依赖解析复杂，状态转换容易卡死 | **代际依赖跟踪**：提供方更新后，消费方按配置选择透明路由或原子重建 |
| **ClassLoader 回收** | 不涉及类卸载 | 极易发生类加载器引用泄漏导致 OOM | 机制沉重，学习曲线陡峭 | **纯数据快照与有界诊断**：框架层不持久持有插件类引用，保证彻底 GC |
| **上手门槛** | 低 | 中 | 极高 | **低**：提供与 Spring / POJO 高度贴合的 Fluent DSL |

---

## 核心概念通俗对照

为了兼顾严谨性与直观理解，Knotra 的核心抽象可以类比为日常生活中的常见事物：

| 核心词汇 | 官方语义 | 直觉比喻 | 业务理解 |
|---|---|---|---|
| **`Capability`** | 类型化命名服务契约 | **电视频道协议** | 定义“提供什么能力”（如 `Greeting.class` 或 `"payment.primary"`），不绑定具体类实现。 |
| **`Publication<T>`** | 稳定发布槽位 | **电视机频道（如 CCTV-1）** | 长期存在的稳定槽位。业务方认准这个槽位，发布者可以随时向槽位推送新版本内容。 |
| **注册代际（registration generation）** | 内核概念：槽位内某一代已提交的具体能力（非公共 API） | **频道当前播放的具体节目（如第 1 集）** | `Publication.update` 推进代际；旧代际不可变并随在途调用排空退场。 |
| **`MountHandle`** | 稳定的逻辑挂载点 | **墙上的多功能插座** | 业务逻辑挂载的位置。无论插座上的电器如何重启或重配置，插座的身份始终不变。 |
| **`Activation`** | 一次运行时的激活尝试 | **插在插座上正在通电运转的电器** | 每次启动、重启或重配置都会产生新的 Activation，捕获当时的依赖和配置；失败可重试。 |
| **`Settlement`** | 单次操作的传播与排空收敛 | **变更平滑生效并稳定** | 描述单次变更（如 publish/update/transaction）引起的依赖传播、在途排空与子树初始化全部完成。 |
| **`Beans.dynamic()`** | 动态接口代理注入 | **前台总机电话** | 消费方调用方法时自动路由到当前槽位最新实现；提供方升级时，消费方实例无需重启。 |
| **`Beans.fixed()`** | 固定代际依赖注入 | **签署固定版本的业务合同** | 消费方在启动时绑定提供方的某一特定代际；提供方升级时，消费方将随之安全重建。 |

---

## 角色导读路径

不同角色的开发者可按需阅读对应的文档章节：

* **业务应用开发者（初级入门）**：
  1. 阅读本文的 [极速上手](#极速上手)。
  2. 阅读 [Beans 与 Spring 集成指南](docs/01-初级入门/01-Beans与Spring集成指南.md)。
  3. 阅读 [实战案例：动态营销折扣引擎](docs/01-初级入门/02-实战案例-动态营销折扣引擎.md)。
* **插件与中间件开发者（中级进阶）**：
  1. 阅读 [插件工程化手册](docs/02-中级进阶/01-插件工程化手册.md)。
  2. 阅读 [线程模型与生产实践](docs/02-中级进阶/02-线程模型与生产实践.md)。
* **架构师与平台开发者（高级架构）**：
  1. 阅读 [API 与内核架构指南](docs/03-高级架构/01-API与内核架构指南.md)。
  2. 阅读 [测试与质量保证指南](docs/03-高级架构/02-测试与质量保证指南.md)。
  3. 阅读 [FAQ 与故障排查指南](docs/03-高级架构/03-FAQ与故障排查指南.md)。

---

## 极速上手

### 1. 安装与依赖引入

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

### 2. 定义业务契约与初始实现

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

### 3. 发布、装配与平滑热替换

以下为核心流程摘要（完整可执行代码见 [QuickStartExample.java](knotra-docs-examples/src/test/java/io/knotra/docs/QuickStartExample.java)）：

```java
try (KnotraRuntime runtime = KnotraRuntime.create()) {
    // 1. 发布 v1 版本实现到根上下文
    PublicationChange<Greeting> firstChange =
            runtime.publish(Greeting.class, new ConstantGreeting("v1"));
    Publication<Greeting> greeting = firstChange.publication();
    firstChange.awaitSettled(Duration.ofSeconds(10));

    // 2. 挂载动态依赖 Greeting 的渲染组件
    MountHandle renderer = Beans
            .component("greeting-renderer")
            .with(Beans.dynamic(Greeting.class))
            .create((Greeting current) -> new GreetingRenderer(current))
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
- **生产停机建议**：入门示例使用 `try-with-resources` 进行资源收尾，其 `close()` 方法为无界阻塞等待；生产环境中建议调用 `runtime.closeAsync()` 并配合带超时预算的 `get(timeout)`，详见 [线程模型与生产实践](docs/02-中级进阶/02-线程模型与生产实践.md)。

---

## API 三层分层体系

Knotra 将功能划分为清晰的三层，避免高级概念干扰日常业务开发：

| 层次 | 目标使用者 | 核心类与入口 | 设计边界与原则 |
|---|---|---|---|
| **Simple API** | 日常业务开发 | `KnotraRuntime`、`Publication`、`Beans`、`MountHandle` | 零事务概念、无原始代际操纵、无底层配置占位类型，开箱即用。 |
| **Advanced API** | 平台与框架架构师 | `runtime.advanced()` | 支持多操作结构事务、多租户 Context 树与全量纯数据快照。注册代际是内核概念；发布走 `Publication`，事务内暂存走 `tx.provide`。 |
| **SPI** | 插件与扩展开发者 | `Component`、`ComponentFactory`、`ActivationContext` | 直接实现底层生命周期，管理原生资源并遵循 ClassLoader 隔离规则。 |

---

## 模块分工

| 模块名 | 职责与定位 | 依赖关系 |
|---|---|---|
| `knotra-bom` | 版本依赖集中管理 BOM | 无 |
| `knotra-starter` | 普通应用快速接入 Starter | Core、Beans |
| `knotra-core` | Publication、Mount、事务、生命周期与快照内核 | 无外部第三方运行时依赖 |
| `knotra-beans` | POJO 构造器注入与生命周期适配 Fluent DSL | Core |
| `knotra-beans-processor` | 编译期注解处理器（自动生成工厂代码） | Core、Beans |
| `knotra-events` | 具备在途排空能力的进程内类型化事件总线 | Core |
| `knotra-spring` | Spring 子容器挂载适配器与宿主动态代理桥 | Core、Spring Context |
| `knotra-spring-starter` | Spring 应用快速聚合依赖 | Starter、Spring |
| `knotra-pf4j-spi` | 插件导出的受控工厂 SPI | Core、PF4J provided |
| `knotra-pf4j` | PF4J 插件 jar 加载、在途排空与类加载器隔离保护 | Core、SPI、PF4J、ASM |
| `knotra-loader` | 声明式期望组件树的比对与收敛协调器 | Core |
| `knotra-pf4j-loader` | PF4J 插件目录到 Loader 期望树的解析桥接 | Loader、PF4J |
| `knotra-pf4j-starter` | 插件化应用一站式依赖 | PF4J Loader |
| `knotra-integration-tests` | 跨模块全链路集成测试 | 相关全模块 |
| `knotra-docs-examples` | 文档示例代码与权威规范守卫测试 | Starter、JUnit |

---

## 完整验证

在项目根目录下执行：

```bash
mvn clean verify
```

构建过程会执行全模块单元测试、PF4J 动态插件构建与卸载验证、Spring 子容器热重载、并发排空、动态代理租约以及 ClassLoader GC 回收断言。
