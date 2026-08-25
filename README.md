# Knotra

**应用不重启，组件可替换。**

Knotra 是面向 Java 21+ 的 JVM 动态组件运行时。应用在运行期间可以按需热替换业务算法、平滑升级插件、局部重启子系统或按租约/上下文切换底层服务；同时由底层运行时确定性地维护依赖代际绑定、在途流量排空（Drain）与资源逆序释放（LIFO）。

![Java](https://img.shields.io/badge/Java-21%2B-orange)
![Maven](https://img.shields.io/badge/Maven-3.9%2B-blue)
![Version](https://img.shields.io/badge/version-0.1.0--SNAPSHOT-blue)

# 背景

在传统的 JVM 开发体系中，当业务逻辑需要动态扩展或插件化热插拔时，常常面临四大核心困境：

- **在途调用被粗暴中断**：直接替换对象或卸载 ClassLoader 时，正在执行业务逻辑的工作线程容易出现异常或状态撕裂。
- **依赖版本难以感知与收敛**：底层服务升级后，上层依赖它的组件无法自动且安全地感知并同步更新。
- **类加载器泄漏导致 Metaspace OOM**：旧版本插件虽然从逻辑上卸载，但因静态字段或未清理钩子导致 ClassLoader 无法被 GC 回收。
- **资源清理无序与死锁**：组件销毁顺序混乱，下游数据源已被关闭，上层组件仍在尝试刷盘。

我们希望有一种简单通用的方式，让 JVM 内部的热替换如同现代微服务发布一样平滑可控，这就是 Knotra 设计的目的。

# 设计

## 设计理念

现实世界中的许多经典系统为我们提供了灵感：

- **电视频道（Publication）**：电视机频道是长期存在的稳定槽位；发布者推送新节目，观众认准频道，内容无缝演进。
- **多功能插座（MountHandle）**：插座位置长期固定；插在插座上的电器（Activation）无论如何更换重启，插座身份不变。
- **前台总机（Dynamic Proxy）**：外界呼叫总机，总机透明路由到当前最新业务员，无需外界关心人员轮换。

使用这种模式，我们可以得到：

- 职责清晰单一、易于测试的组件
- 零停机、秒级平滑生效的业务策略
- 在途流量无损排空（Drain）与严格 LIFO 资源逆序释放
- 彻底无残留的插件 ClassLoader 垃圾回收

## 设计目标

- **轻量细粒度契约**：使用强类型契约解耦服务提供方与消费方
- **声明式 Fluent DSL**：与 Java POJO 及 Spring 生态无缝贴合，提供声明式依赖编排
- **多样化依赖模式**：支持动态透明代理、显式一致性租约、固定代际绑定与可选依赖
- **原生双重租约与排空**：方法级租约与跨方法一致性租约，支持在途流量非阻塞排空（Drain）
- **确定性资源逆序释放**：托管资源生命周期，在组件卸载或重建时严格按 LIFO 逆序销毁
- **完整插件工程化**：基于 PF4J 提供插件隔离加载、版本平滑升级与 ClassLoader 彻底 GC 回收
- **清晰三层 API 划分**：面向业务的 Simple API、面向架构师的 Advanced API 与面向插件作者的 SPI
- **纯数据快照与诊断**：无对象引用的快照（`RuntimeSnapshot`）与挂起操作诊断（`pendingOperations`）

## 极速体验

当前项目正在积极演进中，使用前请先从源码构建安装到本地仓库：

```bash
mvn clean install
```

引入 Starter 依赖：

```xml
<dependency>
  <groupId>io.knotra</groupId>
  <artifactId>knotra-starter</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

极简代码体验：

```java
try (KnotraRuntime runtime = KnotraRuntime.create()) {
    // 1. 发布初始 v1 实现
    Publication<Greeting> greeting = runtime.publish(Greeting.class, new ConstantGreeting("v1")).publication();

    // 2. 挂载动态依赖 Greeting 的渲染组件
    var greetingDep = Beans.dynamic(Greeting.class);
    Beans.component("renderer")
            .with(greetingDep)
            .create(deps -> new GreetingRenderer(deps.get(greetingDep)))
            .provideAs(RenderedGreeting.class)
            .mount(runtime);

    // 3. 热替换为 v2 实现（消费方零重启，调用自动路由至 v2）
    greeting.update(new ConstantGreeting("v2")).awaitSettled(Duration.ofSeconds(10));
}
```

> **生产停机提示**：示例中的 `try-with-resources` 会执行无界阻塞等待；生产环境中建议调用 `runtime.closeAsync()` 并使用有界超时等待，详见 [线程模型与生产实践](docs/production-practice.md)。

## 文档导航

- [快速开始](docs/quick-start.md)：从依赖引入、契约定义、组件装配到平滑热替换的完整路径
- [Beans 装配](docs/beans-guide.md)：Beans DSL、6 种依赖注入模式、生命周期管理与编译期注解处理器
- [Spring 集成](docs/spring-guide.md)：Spring 子容器隔离挂载与 Spring Boot 宿主单例动态桥
- [插件工程](docs/plugin-guide.md)：三层工程结构、受控工厂导出、声明式调和与 ClassLoader 防泄漏红线
- [运行时内核](docs/runtime-kernel.md)：领域模型、API 三层体系、高级结构事务、Context 树与模块分工
- [生产实践与排障](docs/production-practice.md)：执行边界、超时预算、优雅停机、挂起诊断、测试规范与 FAQ
- [实战案例](docs/case-sample.md)：动态营销折扣引擎、多渠道支付网关、数据批处理与事件驱动流水线
