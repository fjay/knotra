# Knotra 插件工程化手册

本手册面向插件作者和宿主平台作者。目标是在热升级、局部重载和卸载时保持四条边界：

1. 共享合约只在 contract 模块中定义。
2. 插件只导出受控 factory，不直接把实例泄漏给宿主。
3. 加载插件不等于挂载组件。
4. 卸载前 drain 在途调用，卸载后插件 ClassLoader 可回收。

## 模块结构

推荐三模块结构：

```text
platform-contract/
  src/main/java/com/example/contract/
    PaymentGateway.java
    PaymentConfig.java

payment-plugin/
  pom.xml
  src/main/java/com/example/payment/
    PaymentPluginProvider.java
    DefaultGatewayFactory.java
    TieredGatewayFactory.java

host-app/
  pom.xml
  src/main/java/com/example/host/
```

插件依赖规则：

- `platform-contract`、`knotra-core`、`knotra-pf4j-spi`：`provided` 或不打进插件。
- PF4J 插件库：按插件运行方式决定，通常由宿主提供。
- 第三方业务库：打进插件 fat JAR，或放入独立父 loader 并显式管理依赖闭包。
- 不把 Knotra 宿主实现、Spring 宿主模块或另一个插件的内部类型打进本插件。

Maven 示例：

```xml
<dependencies>
  <dependency>
    <groupId>com.example</groupId>
    <artifactId>platform-contract</artifactId>
    <version>${contract.version}</version>
    <scope>provided</scope>
  </dependency>

  <dependency>
    <groupId>io.knotra</groupId>
    <artifactId>knotra-core</artifactId>
    <scope>provided</scope>
  </dependency>

  <dependency>
    <groupId>io.knotra</groupId>
    <artifactId>knotra-pf4j-spi</artifactId>
    <scope>provided</scope>
  </dependency>
</dependencies>
```

## 共享合约

合约接口必须由宿主和插件使用同一个 ClassLoader 加载，或由双方共同 parent 提供：

```java
package com.example.contract;

public interface PaymentGateway {
    ChargeResult charge(ChargeRequest request);
}
```

配置 token 也必须是共享类型：

```java
package com.example.contract;

public record PaymentConfig(String channel, int timeoutMillis) {
    public PaymentConfig {
        if (channel == null || channel.isBlank()) {
            throw new IllegalArgumentException("channel must not be blank");
        }
        if (timeoutMillis <= 0) {
            throw new IllegalArgumentException("timeoutMillis must be positive");
        }
    }
}
```

不要把插件私有 DTO、注解、异常或 `Optional<PluginType>` 暴露到合约上。合约方法参数和返回值应全部来自 contract 模块、JDK 或宿主共享类型。

## 导出受控工厂

插件实现 `RuntimeComponentProvider`，返回 `ExportedComponentFactory`：

```java
@Extension
public final class PaymentPluginProvider implements RuntimeComponentProvider {

    @Override
    public Collection<ExportedComponentFactory<?>> factories() {
        return List.of(
                ExportedComponentFactory.noConfig(new ParentGatewayFactory()),
                ExportedComponentFactory.of(
                        PaymentConfig.class,
                        new ConfiguredGatewayFactory()));
    }
}
```

无公开配置的工厂：

```java
public final class ParentGatewayFactory implements ComponentFactory<NoConfig> {
    @Override
    public String factoryId() {
        return "payment.parent";
    }

    @Override
    public Component<NoConfig> create() {
        return new ParentGatewayComponent();
    }
}
```

类型化配置工厂：

```java
public final class ConfiguredGatewayFactory
        implements ComponentFactory<PaymentConfig> {

    @Override
    public String factoryId() {
        return "payment.configured";
    }

    @Override
    public PaymentConfig normalizeConfig(PaymentConfig config) {
        return new PaymentConfig(
                config.channel().trim(),
                Math.min(config.timeoutMillis(), 5_000));
    }

    @Override
    public Component<PaymentConfig> create() {
        return new ConfiguredGatewayComponent();
    }
}
```

组件 start 只在该次 Activation 中持有依赖：

```java
final class ConfiguredGatewayComponent implements Component<PaymentConfig> {

    private final ComponentDescriptor descriptor = ComponentDescriptor.named(
            "payment.configured",
            CapabilityRequirement.dynamicOptional(PaymentRoutes.class));

    @Override
    public ComponentDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public void start(ActivationContext context, PaymentConfig config) {
        DynamicCapability<PaymentRoutes> routes = context.subscribe(PaymentRoutes.class);
        PaymentGateway gateway = new DynamicPaymentGateway(config, routes);

        context.provide(PaymentGateway.class, gateway);
        context.lifecycle().onClose("payment-gateway", gateway::flush);
    }
}
```

工厂外壳可以复用，但不能保存 `gateway`、`routes` 或 `context`。这些对象只属于一次 Activation。

## 宿主加载

宿主声明哪些 Java package 是共享合约：

```java
try (KnotraRuntime runtime = KnotraRuntime.create();
     Pf4jArtifactAdapter adapter = Pf4jArtifactAdapter.create(
             pluginsRoot,
             runtime,
             Set.of("com.example.contract"))) {

    ArtifactSnapshot artifact = adapter.loadArtifactAsync(pluginJar)
            .toCompletableFuture()
            .get(30, TimeUnit.SECONDS);

    assertEquals(ArtifactState.ACTIVE, artifact.state());
}
```

加载成功后，`adapter.factories()` 只提供稳定元数据和可执行 factory view，Runtime 中没有任何隐式挂载：

```java
assertTrue(adapter.factories().find("payment.parent").isPresent());
assertTrue(runtime.advanced().snapshot().mounts().isEmpty());
```

## 挂载工厂

无公开配置：

```java
ArtifactFactoryHandle.NoConfig factory = adapter.factories()
        .resolveNoConfig("payment.parent")
        .orElseThrow();

MountHandle parent = factory.mount(runtime.root(), "payment-parent");
parent.requireActive(Duration.ofSeconds(10));
```

类型化配置：

```java
ArtifactFactoryHandle.Configured<PaymentConfig> factory = adapter.factories()
        .resolve("payment.configured", PaymentConfig.class)
        .orElseThrow();

PaymentConfig config = factory.decodeConfig(
        Map.of("channel", "primary", "timeoutMillis", 2000));

ConfiguredMountHandle<PaymentConfig> handle =
        factory.mount(runtime.root(), "payment-primary", config);
handle.requireActive(Duration.ofSeconds(10));
```

根 factory view 不提供 mount 方法，避免调用方向 plain factory 传配置占位值，或向 configured factory 传错类型。同一个 factory 可以在不同 Context 下挂载多个稳定 handle。

## Loader 声明树

宿主可以用 Loader 管理期望树：

```java
ComponentFactoryResolver resolver = Pf4jFactoryResolver.of(adapter);

try (KnotraLoader loader = KnotraLoader.owned(runtime, resolver)) {
    ComponentTree desired = ComponentTree.of(
            ComponentEntry.configured(
                    "payment.primary",
                    FactoryRef.of("payment.configured", "1.4.0"),
                    Map.of("channel", "primary", "timeoutMillis", 2000)),
            ComponentEntry.of(
                    "payment.backup",
                    FactoryRef.of("payment.parent", "1.4.0")));

    ReconcileResult result = loader.reconcileAsync(desired)
            .toCompletableFuture()
            .get(30, TimeUnit.SECONDS);

    result.requireConverged();
}
```

`Pf4jFactoryResolver` 把 no-config factory 解析为 `FactoryKind.PLAIN`，把带共享配置 token 的 factory 解析为 `FactoryKind.CONFIGURED`。配置 raw 值在 resolver 边界 decode，之后才进入 Core 的类型化 mount。

版本替换时，新的 artifact 会产生新的 factory fingerprint。Loader 对比 `FactoryIdentity` 后选择重配置或整挂载替换。

## 升级与排空

升级流程：

1. 加载新版本 artifact，factory catalog 同时可见。
2. 期望树把 factory ref 指向新版本。
3. Loader 替换受影响挂载。
4. 旧 Activation 停止承接新调用，等待在途调用和异步租约。
5. 旧 artifact ownership 清空后卸载。
6. 确认旧 loader 可回收。

```java
adapter.loadArtifactAsync(newPluginJar)
        .toCompletableFuture()
        .get(30, TimeUnit.SECONDS);

ReconcileResult result = loader.reconcileAsync(desiredWithNewVersion)
        .toCompletableFuture()
        .get(60, TimeUnit.SECONDS);
result.requireConverged();

adapter.unloadArtifactAsync(oldArtifactId)
        .toCompletableFuture()
        .get(60, TimeUnit.SECONDS);
```

如果 drain 失败，artifact 状态和诊断保留；调用 `retryDrainAsync(oldArtifactId)`，不要假设卸载已经完成。

## 卸载与 GC

安全卸载后检查：

```java
assertEquals(ArtifactState.UNLOADED,
        adapter.artifact(artifactId).orElseThrow().state());
assertTrue(adapter.ownership(artifactId).isEmpty());
assertTrue(runtime.advanced().snapshot().mounts().stream()
        .noneMatch(mount -> mount.factoryId().equals("payment.configured")));
```

插件工程禁令：

- 静态字段保存合约实例、路由表、线程、连接或异常。
- 启动未取消的周期线程。
- 把插件 `Class`、`ClassLoader` 或异常对象写入宿主全局注册表。
- 日志 Appender、JMX、注册中心客户端未注销。
- 使用 `ThreadLocal` 保存插件对象且线程池复用时不清理。
- 合约方法返回插件私有实现类型。

`FailureInfo`、artifact snapshot、runtime snapshot 和 loader snapshot 都是有界纯数据，不会保存插件 loader。`PublicationChange` 可以持有共享合约 `Class`，但不得持有插件私有 `Class`。

## 版本与兼容

0.1.0 阶段不承诺插件二进制兼容。建议：

- contract 模块独立版本，并用语义化版本约束。
- 插件 manifest 声明精确依赖版本。
- 宿主升级 contract 前先加载兼容插件验证。
- factoryId 保持稳定，不把类名变化泄漏给期望树。
- 配置 decoder 拒绝未知或非法字段，不静默忽略。
- 升级演练必须包含在途调用、失败启动回滚和卸载 GC。
