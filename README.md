# Knotra

**应用不重启，组件可替换。** Knotra 是一个 JVM 动态组件运行时：应用启动后仍可以替换实现、升级插件、局部重启或按 Context 切换服务，同时保持依赖绑定、在途任务与资源清理的一致性。

![Java](https://img.shields.io/badge/Java-21%2B-orange)
![Maven](https://img.shields.io/badge/Maven-3.9%2B-blue)
![Version](https://img.shields.io/badge/version-0.1.0--SNAPSHOT-blue)
![Tests](https://img.shields.io/badge/tests-308%20passing-brightgreen)

> **项目状态**：`0.1.0-SNAPSHOT`，尚未发布到 Maven Central。项目仍在开发中，API 可能随版本调整；本文只描述当前实现。

## 为什么需要它

把一个 JAR 加载进 JVM 很容易，困难的是加载之后的运行语义：

- 新组件初始化完成前不能被其他组件看见。
- 旧组件仍有在途任务时，必须先排空再关闭。
- 组件启动期间依赖被替换，这次启动必须作废并按新依赖重试。
- 插件卸载清理失败时要保留现场并允许重试，不能伪造成功。
- 一组结构修改必须整体提交或整体拒绝。

Knotra 把 Context、Capability、Activation、依赖代际和资源所有权作为一等运行时结构管理，而不是把这些责任留给普通注册表或 `start()/stop()` 回调。

## 快速开始

要求 Java 21+、Maven 3.9+。当前版本需在同一个 Maven reactor 内引用，或先执行 `mvn install`：

```xml
<dependency>
    <groupId>io.knotra</groupId>
    <artifactId>knotra-core</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
<dependency>
    <groupId>io.knotra</groupId>
    <artifactId>knotra-beans</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

业务对象保持普通 Java 类，不依赖 Knotra：

```java
final class Greeter {
    private final Greeting greeting;

    Greeter(Greeting greeting) {
        this.greeting = greeting;
    }

    String greet(String name) {
        return greeting.greet(name);
    }
}
```

装配层声明 Capability 和构造器依赖：

```java
import io.knotra.*;
import io.knotra.beans.*;

interface Greeting {
    String greet(String name);
}

CapabilityKey<Greeting> GREETING =
        CapabilityKey.of("app.greeting", Greeting.class);

BeanDefinition<NoConfig, Greeter> greeterFactory =
        Beans.component("greeter")
                .with(Beans.required(GREETING))
                .create(Greeter::new)
                .initializer(greeter ->
                        System.out.println(greeter.greet("world")))
                .build();
```

发布 v1、挂载 consumer，再原子替换为 v2：

```java
try (KnotraRuntime runtime = KnotraRuntime.create()) {
    RegistrationHandle v1 = runtime.provide(
            GREETING, name -> "v1: hello " + name);

    ComponentHandle<NoConfig> greeter =
            runtime.mount("greeter", greeterFactory);
    greeter.whenSettled().toCompletableFuture().join();

    runtime.transact(tx -> {
        tx.revoke(v1);
        return tx.provide(
                runtime.root(),
                GREETING,
                name -> "v2: bonjour " + name);
    }).settlement().toCompletableFuture().join();
}
```

输出：

```text
v1: hello world
v2: bonjour world
```

替换 provider 后没有手工重启代码。Runtime 先关闭旧 `Greeter` Activation，再以固定的新 BindingSet 创建新的 POJO。业务类不依赖 Knotra；Capability、mount 和生命周期只出现在装配层。

## 一分钟理解模型

| 概念 | 含义 |
|---|---|
| `Context` | Capability 可见范围。子 Context 能看到父级注册，也可以用本地注册遮蔽父级 |
| `CapabilityKey<T>` | 名称与 JVM 合约类型组成的服务身份；依赖按 registration 代际跟踪 |
| `ComponentFactory<C>` / `Component<C>` | 挂载时创建组件壳；声明依赖并在每次 Activation 中启动 |
| `BeanDefinition<C,T>` | POJO 构造、输出和清理策略生成的类型化 ComponentFactory |
| `ComponentHandle<C>` | 跨多次 Activation 保持稳定的逻辑挂载点 |
| `Activation` | 组件按固定 BindingSet 运行的一代实例；启动失败或 stale 会回滚 |
| `LifecycleScope` | Activation 拥有的资源树，确定性 LIFO 清理，失败条目可重试 |
| `DynamicCapability<T>` | 每次调用获取当前 committed provider 租约，consumer 不随 provider 换代重启 |

依赖绑定默认是 **PINNED**：Activation 启动时固定 BindingSet，provider 变化时按依赖传播重建消费方。这是普通组件的默认语义，不需要注解。

## 默认路径与高级路径

普通业务装配优先使用 `knotra-beans`。其他模块按需引入：

| 需求 | 选择 | 说明 |
|---|---|---|
| POJO 构造器注入、typed config、自动生命周期 | `knotra-beans` | 业务类零 Knotra 依赖，装配层显式 wiring |
| 编译期生成无反射 Factory | `knotra-beans-processor` | 稳定 `*_KnotraFactory`，SOURCE 注解 |
| 无状态服务随 provider 换代即时切换 | Core `DynamicCapability` | 显式动态绑定，带调用租约与排空语义 |
| 团队已使用 Spring 装配 | `knotra-spring` | 每 Activation 一个 child context，或宿主 singleton 动态 bridge |
| 组件间类型化事件 | `knotra-events` | 五种分发模式，订阅与总线可排空 |
| 从 JAR 加载插件并安全卸载 | `knotra-pf4j` + `knotra-pf4j-spi` | 受控 catalog、drain、ClassLoader 防护 |
| 声明式期望树收敛 | `knotra-loader` | 对比 desired tree 与运行时状态，原子 reconcile |
| PF4J catalog 接入 Loader | `knotra-pf4j-loader` | 官方 bridge，负责 decoder、版本与 fingerprint |

各模块完整契约见 [API 与集成指南](<docs/Knotra API 与集成指南.md>)。

## 模块

| 模块 | 职责 | 编译依赖 |
|---|---|---|
| `knotra-core` | Context、Capability、事务、Activation、动态调用租约、LifecycleScope、Snapshot | 无运行时依赖 |
| `knotra-beans` | POJO 构造器注入、输出与生命周期适配 | Core |
| `knotra-beans-processor` | SOURCE 注解到无反射 Bean Factory 的编译期生成 | Beans、Core |
| `knotra-events` | 类型化、可排空的 EventBus | Core |
| `knotra-spring` | Activation-owned Spring child context 与 dynamic bridge | Core、Spring Context |
| `knotra-pf4j-spi` | 插件导出的共享 factory/decoder SPI | Core、PF4J provided |
| `knotra-pf4j` | artifact 加载、目录、受控挂载、drain、ClassLoader 防护 | Core、SPI、PF4J、ASM |
| `knotra-loader` | desired component tree reconcile | Core |
| `knotra-pf4j-loader` | PF4J catalog 到 Loader resolver 的官方桥接 | Loader、PF4J |
| `knotra-integration-tests` | 真实插件 JAR 与跨模块验证，仅测试 | 全部模块 |

## 文档

- [Beans 与 Spring 集成指南](<docs/Knotra Beans 与 Spring 集成指南.md>)：从普通 POJO 开始，再进入编译期 Factory、dynamic 依赖和 Spring bridge。
- [API 与集成指南](<docs/Knotra API 与集成指南.md>)：Core、Events、PF4J 与 Loader 的公开接口和失败语义。
- [实战案例：动态物流路由系统](<docs/Knotra 实战案例：动态物流路由系统.md>)：插件升级、旧任务排空和 ClassLoader 回收。
- [插件工程化手册](<docs/Knotra 插件工程化手册.md>)：从 Maven 工程到可加载 artifact。
- [线程模型与生产实践](<docs/Knotra 线程模型与生产实践.md>)：回调线程、阻塞边界、关闭顺序和监控。
- [测试指南](<docs/Knotra 测试指南.md>)：依赖替换、清理失败、drain 竞态和 GC 测试。
- [FAQ 与排障指南](<docs/Knotra FAQ 与排障指南.md>)：按状态与诊断码排查。

## 非目标

- Core 不提供通用 DI、组件扫描或 AOP；`knotra-beans` 只做显式 wiring，`knotra-spring` 也不是 Spring 替代品。
- 不做分布式协调或跨 JVM 热替换。
- 不提供插件权限沙箱、签名校验或资源配额，只应加载可信代码。
- Loader 不监听文件系统，期望状态由调用方显式提交。
- 不规定 YAML、JSON 或配置中心格式；raw decode 属于集成边界。

## 构建与验证

```bash
mvn clean verify
```

当前 reactor 包含 308 项测试：Core 113、Beans 24、Beans Processor 25、Events 44、Spring 14、PF4J 37、Loader 36、跨模块集成 15。集成测试会构建真实 PF4J fixture JAR，并验证官方 Loader bridge、nested tree、配置拒绝、ownership、drain 竞态、动态调用租约、Spring refresh rollback、编译期 Factory、并发关闭和 ClassLoader GC。
