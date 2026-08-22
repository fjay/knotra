# Knotra 插件工程化手册

本文描述当前 `0.1.0-SNAPSHOT` 的 PF4J 插件工程做法：如何从一个空 Maven 工程做出可加载、可挂载、可排空和可卸载的插件 JAR，以及如何使用 Beans、编译期生成器和 Loader bridge 减少手写适配代码。

阅读顺序是刻意安排的：先完成最小可加载插件，再引入类型化配置、注解生成器和插件间依赖，最后查阅 ClassLoader、所有权、版本策略和故障参考。Core 的 Activation、事务和排空算法不在本文展开，见 [Knotra API 与集成指南](<Knotra API 与集成指南.md>) 与 [Knotra 线程模型与生产实践](<Knotra 线程模型与生产实践.md>)。

## 最小可加载插件

一个最小插件需要四个部分：

1. 宿主与插件共享的 contract module，包含跨边界的接口和配置类型；
2. 一个 `public` 且可无参实例化的 `RuntimeComponentProvider`；
3. provider 导出一个非 null 的 `ExportedComponentFactory`，工厂 ID 非 blank；
4. `META-INF/extensions.idx` 与有效的 PF4J 插件描述符。

推荐工程结构：

```text
greeting-app/
├── greeting-contract/
│   ├── pom.xml
│   └── src/main/java/com/example/contract/
│       ├── Greeting.java
│       └── GreetingConfig.java
└── greeting-plugin/
    ├── pom.xml
    └── src/main/java/com/example/plugin/
        ├── GreetingBean.java
        ├── GreetingFactory.java
        └── GreetingProvider.java
```

contract module 是普通 Maven JAR。宿主以普通编译依赖使用它；插件编译时也使用它，但必须标记为 `provided`，不能把它打进插件 JAR。插件中的 `com.example.plugin` 是私有实现包，不跨 Knotra 边界发布类型。

### 1. 共享合约

```java
package com.example.contract;

public interface Greeting {
    String greet(String name);
}
```

Capability 名称是稳定字符串，宿主和插件可以各自声明同一个 `CapabilityKey`。如果希望在合约模块中集中声明常量，该模块可以依赖 `knotra-core`，但宿主必须把这个合约 JAR 放在自己的运行时 classpath 上；插件侧仍使用 `provided`。

```java
public static final CapabilityKey<Greeting> GREETING =
        CapabilityKey.of("app.greeting", Greeting.class);
```

不要把插件私有接口、私有嵌套接口、插件私有 record 或插件私有异常作为 Capability contract、config token 或 decoder 接口。它们无法以宿主 ClassLoader 的同一个 `Class` 身份解析，会在发现或激活前被拒绝。

### 2. 插件 POM 与打包边界

插件 POM 的关键是：Knotra API、Beans、PF4J SPI、PF4J 和共享合约全部使用 `provided`。

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                             https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>com.example</groupId>
    <artifactId>greeting-plugin</artifactId>
    <version>1.0.0</version>
    <packaging>jar</packaging>

    <properties>
        <maven.compiler.release>21</maven.compiler.release>
        <knotra.version>0.1.0-SNAPSHOT</knotra.version>
    </properties>

    <dependencies>
        <dependency>
            <groupId>io.knotra</groupId>
            <artifactId>knotra-core</artifactId>
            <version>${knotra.version}</version>
            <scope>provided</scope>
        </dependency>
        <dependency>
            <groupId>io.knotra</groupId>
            <artifactId>knotra-beans</artifactId>
            <version>${knotra.version}</version>
            <scope>provided</scope>
        </dependency>
        <dependency>
            <groupId>io.knotra</groupId>
            <artifactId>knotra-pf4j-spi</artifactId>
            <version>${knotra.version}</version>
            <scope>provided</scope>
        </dependency>
        <dependency>
            <groupId>org.pf4j</groupId>
            <artifactId>pf4j</artifactId>
            <version>3.13.0</version>
            <scope>provided</scope>
        </dependency>
        <dependency>
            <groupId>com.example</groupId>
            <artifactId>greeting-contract</artifactId>
            <version>1.0.0</version>
            <scope>provided</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-jar-plugin</artifactId>
                <version>3.4.2</version>
                <configuration>
                    <archive>
                        <manifestEntries>
                            <Plugin-Id>greeting-plugin</Plugin-Id>
                            <Plugin-Version>${project.version}</Plugin-Version>
                        </manifestEntries>
                    </archive>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

`provided` 是独立插件工程的正确打包要求，也是最佳实践：这些类型的运行时身份必须来自宿主，插件 JAR 只包含插件自己的实现类和资源。不要依赖 adapter 对 JAR 做统一的重复包扫描；它强制共享包的加载委派并校验跨边界类型身份，但包内容治理仍应由构建和发布流程保证。若使用 Shade 或自定义打包，必须在构建中显式排除这些依赖。

### 3. NoConfig Beans 工厂

业务类保持普通 POJO：

```java
package com.example.plugin;

import com.example.contract.Greeting;

public final class GreetingBean implements Greeting {
    @Override
    public String greet(String name) {
        return "hello " + name;
    }
}
```

装配层用 `knotra-beans` 生成 `ComponentFactory`：

```java
package com.example.plugin;

import com.example.contract.Greeting;
import io.knotra.CapabilityKey;
import io.knotra.NoConfig;
import io.knotra.beans.BeanDefinition;
import io.knotra.beans.Beans;

public final class GreetingFactory {
    public static final CapabilityKey<Greeting> GREETING =
            CapabilityKey.of("app.greeting", Greeting.class);

    public static BeanDefinition<NoConfig, Greeting> definition() {
        return Beans.component("greeting")
                .create(GreetingBean::new)
                .provide(GREETING)
                .build();
    }
}
```

`BeanDefinition` 是不可变的 `ComponentFactory`。它的 `factoryId()` 等于显式声明的 component ID；每次 Activation 会重新执行 creator，构造新的 `GreetingBean`。不要把 Bean、依赖、`ActivationContext` 或上一次 Activation 的输出缓存在工厂字段里。

provider 负责把工厂导出给宿主：

```java
package com.example.plugin;

import io.knotra.pf4j.spi.ExportedComponentFactory;
import io.knotra.pf4j.spi.RuntimeComponentProvider;
import org.pf4j.Extension;

import java.util.Collection;
import java.util.List;

@Extension
public final class GreetingProvider implements RuntimeComponentProvider {
    @Override
    public Collection<ExportedComponentFactory<?>> factories() {
        return List.of(ExportedComponentFactory.noConfig(
                GreetingFactory.definition()));
    }
}
```

`ExportedComponentFactory.noConfig(...)` 使用 `NoConfig.class` 作为配置 token，并把 null 归一化为 `NoConfig.INSTANCE`。加载和启动插件不会隐式挂载组件；宿主必须显式 resolve factory 并 mount。

### 4. extensions.idx

PF4J 通过 `META-INF/extensions.idx` 发现 `RuntimeComponentProvider`。推荐让 `@Extension` 的注解处理器在编译时生成索引；`provided` 依赖同样在编译和注解处理 classpath 上。构建后必须确认索引存在。

也可以手动维护 `src/main/resources/META-INF/extensions.idx`：

```text
com.example.plugin.GreetingProvider
```

格式为每行一个全限定类名，空行忽略，`#` 开头是注释。provider 类必须 `public` 且可无参实例化。索引缺失、类不在索引中或发现结果为空时，目标插件加载失败。

### 5. 插件描述符

推荐使用 MANIFEST 条目：

```text
Plugin-Id: greeting-plugin
Plugin-Version: 1.0.0
```

也可以在 JAR 根目录放 `plugin.properties`：

```properties
plugin.id=greeting-plugin
plugin.version=1.0.0
```

适配器按 `plugin.properties` 优先、`MANIFEST.MF` 其次的顺序读取描述符。选一种作为主来源，不要让两个来源表达不同事实。常用字段如下：

| MANIFEST | properties | 必填 | 说明 |
|---|---|---|---|
| `Plugin-Id` | `plugin.id` | 是 | 运行时 artifactId，与 JAR 文件名和 Maven artifactId 无关 |
| `Plugin-Version` | `plugin.version` | 是 | artifact 版本，可用于 Loader 精确引用 |
| `Plugin-Class` | `plugin.class` | 否 | 自定义 PF4J 插件生命周期类 |
| `Plugin-Dependencies` | `plugin.dependencies` | 否 | 插件间依赖 |
| `Plugin-Description` | `plugin.description` | 否 | 描述 |
| `Plugin-Provider` | `plugin.provider` | 否 | 提供方 |
| `Plugin-Requires` | `plugin.requires` | 否 | PF4J 版本要求 |
| `Plugin-License` | `plugin.license` | 否 | 许可证 |

### 6. 构建、加载与挂载

```bash
cd greeting-plugin
mvn package
jar tf target/greeting-plugin-1.0.0.jar
```

预期内容：

```text
com/example/plugin/GreetingBean.class
com/example/plugin/GreetingFactory.class
com/example/plugin/GreetingProvider.class
META-INF/MANIFEST.MF
META-INF/extensions.idx
```

不应包含：

```text
com/example/contract/**
io/knotra/**
org/pf4j/**
```

宿主加载、解析并挂载：

```java
CapabilityKey<Greeting> GREETING =
        CapabilityKey.of("app.greeting", Greeting.class);

try (KnotraRuntime runtime = KnotraRuntime.create();
     Pf4jArtifactAdapter adapter = Pf4jArtifactAdapter.create(
             Path.of("plugins"), runtime, Set.of("com.example.contract"))) {

    ArtifactSnapshot snapshot = adapter.loadArtifactAsync(
            Path.of("plugins/greeting-plugin-1.0.0.jar")).join();

    ArtifactFactoryHandle<NoConfig> factory = adapter.factories()
            .resolve("greeting", NoConfig.class)
            .orElseThrow();

    ComponentHandle<NoConfig> handle = factory.mount(
            runtime.root(), "greeting");

    ComponentState state = handle.whenSettled()
            .toCompletableFuture()
            .get(10, TimeUnit.SECONDS);

    Greeting greeting = runtime.root().view().require(GREETING);
    System.out.println(greeting.greet("Knotra"));
}
```

成功加载后 artifact 是 `ACTIVE`，工厂进入目录，但 `runtime.snapshot().components()` 仍为空，直到宿主 mount。`loadArtifact(Path)` 传入的路径会转绝对路径并归一化；相对路径按 JVM 工作目录解析，不相对 `pluginsRoot`。

## Plugin Class 与组件生命周期

不声明 `Plugin-Class` 时，PF4J 使用默认 `org.pf4j.Plugin`。绝大多数 Knotra 插件不需要自定义 Plugin 类；组件资源应由 `LifecycleScope`、Beans disposer 或 Spring child context 管理。

只有需要 PF4J 插件级别的启动、停止钩子时才声明：

```java
package com.example.plugin;

import org.pf4j.Plugin;

public final class GreetingPlugin extends Plugin {
    @Override
    public void start() {
        // 插件级初始化；不要在这里创建并缓存业务 Bean
    }

    @Override
    public void stop() {
        // 插件级释放；不要绕过 Knotra 清理组件资源
    }
}
```

```text
Plugin-Class: com.example.plugin.GreetingPlugin
```

适配器先按依赖拓扑启动 PF4J 插件，之后才调用 provider 并发布工厂。因此 Plugin `start()` 中不能假设任何 Knotra 组件已经挂载，也不应尝试向 Core 提供业务 Capability。

## RuntimeComponentProvider

`RuntimeComponentProvider` 是插件发布受控工厂的唯一入口：

```java
public interface RuntimeComponentProvider extends ExtensionPoint {
    Collection<ExportedComponentFactory<?>> factories();
}
```

约束：

- 实现类必须 `public` 且可无参实例化，通常加 `@Extension`；
- `factories()` 不能返回 null；
- 集合元素不能为 null；
- 直接加载的目标 artifact 至少要导出一个工厂；
- `factoryId()` 不能为 null 或 blank，前后空白会被固定为修剪后的值；
- 同一个 artifact 内不能出现重复 factoryId；
- 加载和启动 provider 不等于挂载组件。

一个 provider 可以导出多个工厂，一个插件也可以有多个 provider。工厂外壳可以复用，但组件实例和业务 Bean 必须按 Activation 创建。

## 类型化配置与 decoder

把配置类型放在共享合约模块：

```java
package com.example.contract;

public record GreetingConfig(String template) {}
```

定义类型化 Bean：

```java
public static BeanDefinition<GreetingConfig, Greeting> definition() {
    return Beans.component("greeting", GreetingConfig.class)
            .create(config -> new ConfiguredGreetingBean(config.template()))
            .provide(GREETING)
            .build();
}
```

默认导出使用精确类型 decoder：

```java
ExportedComponentFactory.of(GreetingConfig.class, definition())
```

宿主只能传精确的 `GreetingConfig` 实例，null 会被拒绝。若宿主持有 Map、JSON tree 或其他 raw 形态，显式提供 decoder：

```java
ExportedComponentFactory.of(
        GreetingConfig.class,
        raw -> decodeGreetingConfig(raw),
        definition())
```

decoder 输出必须非 null 且是声明的精确配置类型。宿主可以先调用 `factory.decodeConfig(rawConfig)` 检查边界，再调用 `mount(context, mountId, config)`。decoder 实现类可以是插件类，但 `ConfigDecoder` 接口必须来自宿主；配置 token 本身必须能由宿主解析。

类型化挂载：

```java
ArtifactFactoryHandle<GreetingConfig> factory = adapter.factories()
        .resolve("greeting", GreetingConfig.class)
        .orElseThrow();

ComponentHandle<GreetingConfig> handle = factory.mount(
        runtime.root(),
        "greeting",
        new GreetingConfig("hello %s"));
```

无配置工厂使用两个参数的 `mount(context, mountId)`。不要对 `NoConfig` 工厂传 null；typed null 会在创建工厂前被拒绝。

## 手写 Core ComponentFactory

不用 Beans 时，可以直接实现 Core 工厂：

```java
ComponentFactory<NoConfig> factory = new ComponentFactory<>() {
    @Override
    public String factoryId() {
        return "greeting";
    }

    @Override
    public Component<NoConfig> create() {
        return new Component<>() {
            @Override
            public ComponentDescriptor descriptor() {
                return ComponentDescriptor.named("greeting");
            }

            @Override
            public void start(ActivationContext context, NoConfig config) {
                Greeting value = name -> "hello " + name;
                context.provide(GREETING, value);
                context.lifecycle().onClose("greeting", () -> {
                    // 释放该次 Activation 拥有的资源
                });
            }
        };
    }
};
```

`create()` 返回的 `Component` 外壳可以在同一挂载的多次 Activation 中复用，因此它必须无状态。`start()` 每次收到当次 Activation 的 context 和 config，业务对象、连接和清理动作都在这里创建。

## 可选：编译期生成 Factory

引入 processor 后，可以在插件源码中声明 Bean：

```xml
<dependency>
    <groupId>io.knotra</groupId>
    <artifactId>knotra-beans-processor</artifactId>
    <version>${knotra.version}</version>
    <scope>provided</scope>
</dependency>
```

```java
package com.example.plugin;

import com.example.contract.Greeting;
import com.example.contract.GreetingConfig;
import io.knotra.beans.annotation.KnotraBean;
import io.knotra.beans.annotation.KnotraConfig;
import io.knotra.beans.annotation.KnotraConstructor;
import io.knotra.beans.annotation.KnotraNormalizeConfig;
import io.knotra.beans.annotation.KnotraOutput;

@KnotraBean(
        id = "greeting",
        config = GreetingConfig.class,
        outputs = @KnotraOutput(
                name = "app.greeting",
                contract = Greeting.class))
final class ConfiguredGreetingBean implements Greeting {
    private final String template;

    @KnotraConstructor
    ConfiguredGreetingBean(@KnotraConfig GreetingConfig config) {
        this.template = config.template();
    }

    @KnotraNormalizeConfig
    static GreetingConfig normalize(GreetingConfig config) {
        if (config.template().isBlank()) {
            throw new IllegalArgumentException("template must not be blank");
        }
        return new GreetingConfig(config.template().trim());
    }

    @Override
    public String greet(String name) {
        return template.formatted(name);
    }
}
```

编译期会在同包生成 `ConfiguredGreetingBean_KnotraFactory`。它是无反射的 `ComponentFactory`，工厂 ID 来自 `@KnotraBean.id`，并保留依赖、输出、初始化、销毁和配置归一化语义。

导出方式与手写工厂完全相同：

```java
ExportedComponentFactory.of(
        GreetingConfig.class,
        new ConfiguredGreetingBean_KnotraFactory())
```

NoConfig Bean 使用：

```java
ExportedComponentFactory.noConfig(new GreetingBean_KnotraFactory())
```

processor 只解决编译期检查和工厂生成，不改变插件边界。`@KnotraOutput.contract`、`@KnotraRequire.contract`、`@KnotraOptional.contract`、`@KnotraDynamic.contract` 和 `config` 仍必须使用共享且可访问的类型；插件私有 contract 即使通过编译，也会在 adapter 发现阶段被拒绝。

## 工厂目录与 typed handle

`adapter.factories()` 是当前活跃 artifact 的唯一目录：

```java
for (ArtifactFactoryCatalogEntry entry : adapter.factories().list()) {
    System.out.printf(
            "%s %s %s %s%n",
            entry.artifactId(),
            entry.artifactVersion(),
            entry.factoryId(),
            entry.configTypeName());
}
```

`list()` 与 `find(factoryId)` 返回只读元数据，不暴露可执行 factory、decoder、config `Class` 或 ClassLoader，适合目录服务和诊断。

`resolve(factoryId)` 返回 wildcard 可执行句柄，适合动态工具和诊断；宿主代码应优先使用：

```java
Optional<ArtifactFactoryHandle<NoConfig>> typed =
        adapter.factories().resolve("greeting", NoConfig.class);
```

typed resolve 使用精确 `Class` 相等。请求类型与导出 token 不一致时立即抛 `IllegalArgumentException`，不会延迟到 mount。`decodeConfig` 与 mount 的句柄都是活跃 artifact 视图；drain 或 unload 后句柄失效，必须重新 resolve。

## factoryId、版本与并存

规则：

- factoryId 在一个活跃 artifact 内必须唯一；
- factoryId 在整个活跃工厂目录内必须全局唯一；
- 后加载 artifact 撞上已编目 factoryId 时加载失败；
- artifact 版本来自 `Plugin-Version`，不参与 factoryId 去重；
- `FactoryRef.version` 为空时不限制 artifact version；非空时要求精确匹配，不解析版本范围；
- 同一 `Plugin-Id` 在依赖搜索范围内出现两个不同 path/version 组合会判定为歧义。

顺序升级使用“先 drain 旧 artifact，再加载新 artifact”：

```java
adapter.unloadArtifactAsync("greeting-plugin").join();
adapter.loadArtifactAsync(v2Jar).join();
```

成功 unload 后，同一个 artifactId 和 factoryId 可以再次使用。

灰度或蓝绿需要两个版本同时活跃时，必须同时满足：

1. 两个 JAR 使用不同 `Plugin-Id`；
2. 两个工厂使用不同 `factoryId`；
3. 宿主或 Loader 用不同引用显式选择版本。

例如：

```text
artifact: greeting-plugin-v1  factory: greeting-v1
artifact: greeting-plugin-v2  factory: greeting-v2
```

Knotra 目录不做同名 factory 的代际路由，也不会按 artifact version 自动分流。若需要流量切换，应在宿主装配层显式替换引用，或使用 Knotra Capability 的动态调用语义；动态调用的租约与排空约束见 [Knotra Beans 与 Spring 集成指南](<Knotra Beans 与 Spring 集成指南.md>)。

## 插件间依赖与 JAR 搜索

声明依赖：

```text
Plugin-Dependencies: base-plugin, storage@2.0.0, metrics?
```

格式为逗号分隔；`id@version` 表示版本约束；`?` 表示可选依赖。可选依赖只要在搜索范围内就会参与解析，完全缺失时才跳过。

加载目标 JAR 前，adapter 会离线解析完整依赖闭包。候选来源按顺序合并：

1. 构造 adapter 时传入的 plugins roots；
2. 目标 JAR 所在目录；
3. 已加载的 PF4J wrapper。

目标 JAR 自身也会加入候选。合并时按 `Plugin-Id` 与规范化 path 建索引；同一 id 指向不同 path、id 或 version 的组合会报 `ambiguous PF4J repository entry`。已加载 wrapper 可以复用，不会重复 load。

解析成功后：

- 缺失 required 依赖、依赖环和版本不兼容在任何插件加载前失败；
- 加载与启动按拓扑顺序执行，依赖先启动；
- 本次新加载的插件在失败时按逆序回滚；
- 卸载某个依赖时，会先收集并 drain 所有仍依赖它的未卸载下游插件；
- Capability 消费关系不会被自动推断为插件依赖；若插件类直接引用另一个插件的类型或资源，必须显式声明 `Plugin-Dependencies`。

plugins root 不会被自动批量加载。它只是依赖闭包解析和同 id 冲突检测的搜索范围。

## ClassLoader 与 guarded context

adapter 内置强制共享包：

```text
io.knotra
io.knotra.pf4j.spi
org.pf4j
```

并在创建时接收额外共享包：

```java
Pf4jArtifactAdapter.create(
        pluginsRoot, runtime, Set.of("com.example.contract"))
```

共享包匹配规则是包名相等或精确子包，不是字符串前缀。`com.example.contract.api` 命中 `com.example.contract`；`com.example.contractevil` 不命中。声明会去除首尾空白；空白、前后缀 `.` 和 null 元素被拒绝。

命中共享包的类总是从宿主 ClassLoader 加载，插件内同名副本不会胜出。非共享实现类仍按 PF4J 的插件边界解析。

跨边界校验包括：

- provider 实际看到的 `RuntimeComponentProvider` 必须是宿主版本的同一个 `Class`；
- 工厂实际看到的 `ComponentFactory` 必须是宿主版本；
- decoder 实际看到的 `ConfigDecoder` 必须是宿主版本；
- config token 必须能由宿主按同一二进制名解析，且 `Class` 身份完全相同；
- descriptor 中每个 Capability requirement 的 contract 类型在工厂包装时校验；
- `require`、`find`、`subscribe` 和 `provide` 在进入 Core 前再次校验 `CapabilityKey.type()`；
- `mountChild` 会递归包装子工厂和子组件。

`GuardedActivationContext` 不改变 Core 的绑定和生命周期语义，只在边界上执行上述校验。`mountChild` 会忽略调用方传入的 origin，始终强制使用当前 artifact 的 `ComponentOrigin`；调用方只能提供诊断 metadata，不能借 `MountOptions` 把子挂载伪装成宿主来源。

如果插件需要暴露动态 Capability，合约接口同样必须满足精确类型规则。方法级 proxy 的 provider 租约、异步调用和多方法事务边界见 Beans 与 Spring 指南。

## 所有权、drain、retry 与 unload

通过 `ArtifactFactoryHandle.mount(...)` 提交的组件根由 adapter 记账并拥有。组件内部使用 `context.mountChild(...)` 创建的子组件会继承 artifact 来源，随父组件一起释放。

卸载或关闭时的顺序是：

```text
进入 DRAINING
    ↓
从工厂目录移除，拒绝新 mount
    ↓
等待执行中的 mount
    ↓
dispose adapter 拥有的组件根
    ↓
等待同步/异步清理完成
    ↓
停止并卸载 PF4J 插件
    ↓
UNLOADED，插件 ClassLoader 可回收
```

如果清理失败，artifact 进入 `DRAIN_FAILED`：

- 诊断、ownership 和资源保留；
- PF4J 插件通常仍处于 STARTED；
- 修复阻塞点后调用 `retryDrainAsync`；
- 成功后才停止并卸载。

`adapter.closeAsync()` 会对所有未卸载 artifact 执行 drain；失败时可以再次 close 重试。`ArtifactSnapshot` 和 `ArtifactDiagnostic` 只保留稳定文本与 ID，不持有工厂、Throwable 或 ClassLoader。

宿主不要绕过 handle 直接用插件工厂向 Core mount。即使宿主手动设置 `ComponentOrigin.artifact(...)`，adapter 也不会自动认领这个根；drain 时发现来源为 artifact 但缺少 adapter ownership 的根，会进入 `DRAIN_FAILED`，直到宿主自行释放后 retry。

并发 close、unload、mount 和 Loader reconcile 的等待与线程规则见 [Knotra 线程模型与生产实践](<Knotra 线程模型与生产实践.md>)。

## 与 Loader bridge 集成

宿主使用 `knotra-pf4j-loader` 时，可以把活跃工厂目录适配为声明式 Loader resolver：

```java
ComponentFactoryResolver resolver = Pf4jFactoryResolver.of(adapter);

KnotraLoader loader = KnotraLoader.over(
        runtime,
        runtime.root(),
        resolver);
```

可以组合 fallback：

```java
ComponentFactoryResolver resolver = Pf4jFactoryResolver.withFallbacks(
        adapter, classpathResolver);
```

声明期望树：

```java
FactoryRef greeting = FactoryRef.of("greeting", "1.0.0");

ReconcileResult result = loader.reconcile(ComponentTree.of(
        ComponentEntry.configured(
                "greeting", greeting, rawConfig)));
```

bridge 的关键行为：

- 先用 artifact export 的 decoder 把 raw 配置转为类型化配置；
- 只把工厂挂载到 Loader 分配的 Context 和 mountId；
- `FactoryRef.version` 为空时接受任意活跃 artifact version，非空时才要求与 artifact version 精确相等；
- 实现指纹包含 artifactId、version、path、factoryId 和 config type；
- 组件仍保留 artifact ownership；
- Loader close 与 artifact drain 并发时，不留下部分挂载，失败后可重试收敛。

Loader 的期望树准备、替换和回退顺序见 [Knotra API 与集成指南](<Knotra API 与集成指南.md>)。

## 打包与运行错误参考

| 现象或报错 | 阶段 | 原因 | 处理 |
|---|---|---|---|
| `artifact has no RuntimeComponentProvider entries` | load | 缺 `extensions.idx`，provider 不在索引，或 provider 无法实例化 | 检查索引内容、`public` 无参构造和 `@Extension` 处理器 |
| `cannot read a valid PF4J descriptor` | load | 缺 id/version，描述符格式或 JAR 无效 | 检查 `plugin.properties` 或 MANIFEST；确认文件是可读 JAR |
| `failed to load PF4J artifact` 且底层为 zip 错误 | load | 文件不是 JAR | 检查构建产物和复制过程 |
| Plugin `start()` 异常 | load | `Plugin-Class` 启动失败 | 修复插件级初始化；adapter 会逆序回滚本次加载 |
| `missing required PF4J dependencies` | load | required 依赖不在搜索范围 | 把依赖 JAR 放到 plugins root 或目标目录，或修正声明 |
| `incompatible PF4J dependencies` | load | `id@version` 约束不满足 | 调整版本或发布匹配版本 |
| `PF4J dependency cycle contains artifact` | load | 插件依赖成环 | 拆分公共插件或移除环 |
| `ambiguous PF4J repository entry` | load | 同一 `Plugin-Id` 对应多个不同 JAR/version/path | 清理旧文件、改 id，或先卸载再加载 |
| `duplicate factory id inside artifact` | load | 同一插件多个工厂返回同一 factoryId | 为每个工厂分配稳定唯一 ID |
| `factory id is already cataloged` | load | 另一个活跃 artifact 已占用该 ID | unload 旧 artifact，或使用不同 factoryId |
| `factory returned a blank factory id` | load | 工厂返回 null/blank | 显式实现非 blank `factoryId()` |
| `provider returned null factories` / `provider returned no factories` | load | provider 违反导出契约 | 返回非 null 且至少包含一个非 null export |
| `plugin-private contract type rejected` | 发现或激活 | config 或 Capability 类型来自插件私有包 | 移到共享合约包，并把包名传给 adapter |
| `duplicate contract type rejected` | 发现或激活 | 二进制名相同但来自不同 ClassLoader | 移除插件内副本，确保共享包委派到宿主 |
| `shared interface identity mismatch` | 发现 | 插件内有 `RuntimeComponentProvider`、`ComponentFactory` 等私有副本 | 使用 `provided` 依赖并检查 JAR 内容 |
| `shared class is absent from parent` | 发现 | 插件引用的共享类宿主不存在 | 同步宿主与插件的 API/合约版本 |
| typed `resolve` 抛 `IllegalArgumentException` | resolve | 请求的 config Class 与导出 token 不一致 | 使用导出的精确配置类型 |
| decoder 输出 null、类型错误或异常 | decode/prepare | raw 配置不满足边界合约 | 修正 raw 配置或 decoder |
| `INVALID_CONFIG` / normalize 异常 | mount/reconfigure | 配置归一化失败 | 按工厂错误修正配置；原 Activation 保持不变 |
| NoConfig mount 报 null config 并提示 `mount(context, mountId)` | mount | 对 NoConfig 工厂传 null | 使用两个参数的 mount 方法 |
| `artifact is not accepting mounts` | mount | artifact 非 ACTIVE、正在 drain 或已失效 | 等待状态恢复或重新 load 后 resolve 新句柄 |
| `artifact entered draining while mount was in flight` | mount | mount 与 drain 并发且输给 drain | 读取结果或诊断；补偿 dispose 完成后 artifact 才卸载 |
| stale handle mount 失败 | mount | unload 后继续持有旧 factory handle | 重新 load 并重新 resolve |
| `DRAIN_FAILED` / `artifact snapshot contains roots without adapter ownership` | drain | 清理失败，或宿主手动 mount 了 artifact 来源根 | 释放宿主手动根、修复清理，然后 `retryDrainAsync` |
| Loader `RESOLUTION_FAILED` | reconcile | factory 不存在或 version 不匹配 | 检查工厂目录和 `FactoryRef.version` |
| Loader 结构冲突 | reconcile | 期望路径上已有外来 Context 或挂载 | 使用 Loader 管理的路径，或调整期望树 |

更多运行期排障方法见 [Knotra FAQ 与排障指南](<Knotra FAQ 与排障指南.md>)。

## 验证清单

发布前逐项验证：

1. `mvn clean package` 成功；
2. `jar tf` 只包含插件私有实现、`META-INF/MANIFEST.MF` 和 `META-INF/extensions.idx`；
3. JAR 不包含 contract、Knotra、Beans、Processor、Spring、PF4J 或其他宿主提供依赖；
4. `loadArtifactAsync` 返回 `ACTIVE`；
5. `adapter.artifacts()` 有版本和路径，`factories().find(id)` 有配置 token；
6. 加载后 `runtime.snapshot().components()` 为空；
7. typed `resolve` 成功，错误 token 被拒绝；
8. NoConfig 使用 `mount(context, mountId)`；
9. mount 后 `whenSettled()` 返回 `ACTIVE`；
10. Capability 值可从宿主 `ContextView` 读取；
11. 无效 reconfigure 被拒绝且当前 Activation 不变；
12. `disposeAsync` 或 unload 等待异步清理完成；
13. 清理失败后 artifact 为 `DRAIN_FAILED`，`retryDrainAsync` 可收敛；
14. 依赖插件按拓扑加载，卸载依赖时下游先 drain；
15. 成功 unload 后无组件残留，插件 ClassLoader 可回收；
16. Loader bridge 的 raw 配置、版本引用和 ownership 符合预期。

插件相关测试写法见 [Knotra 测试指南](<Knotra 测试指南.md>)；完整升级与动态替换场景见 [Knotra 实战案例：动态物流路由系统](<Knotra 实战案例：动态物流路由系统.md>)。
