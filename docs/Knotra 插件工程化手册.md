# Knotra 插件工程化手册

本文回答一个问题：**如何从一个空的 Maven 工程，做出一个能被 Knotra 宿主加载的插件 JAR**。适用于 `0.1.0-SNAPSHOT`；宿主侧接入见[API 与集成指南](<Knotra API 与集成指南.md>)。

文中"artifact"与"插件 JAR"是同一个东西：Knotra 的 PF4J 适配器把每个受管插件称为 artifact，运行时 `artifactId` 等于插件描述符里的 `Plugin-Id`，与 JAR 文件名和 Maven `artifactId` 无关。

## 最少要素清单

一个能通过 `loadArtifact(...)` 加载的插件 JAR 必须包含：

1. 一个 `public`、可无参实例化、实现 `RuntimeComponentProvider` 的 provider 类；
2. `factories()` 至少返回一个非 null 的 `ExportedComponentFactory`，且工厂的 `factoryId()` 非 null 非 blank；
3. `META-INF/extensions.idx`，内容为 provider 类的全限定名（每行一个，支持 `#` 注释）；
4. 描述符：`Plugin-Id` 非空、`Plugin-Version` 非 null；
5. 有插件间依赖时声明 `Plugin-Dependencies`；没有自定义生命周期时不需要 `Plugin-Class`；
6. JAR 内**不包含** `io.knotra`、`io.knotra.pf4j.spi`、`org.pf4j` 和共享合约包——这些类由宿主 ClassLoader 提供。

## 工程结构

推荐两个模块：合约模块与插件模块。宿主工程已存在时只需让宿主也依赖合约模块。

```text
greeting-demo/
├── greeting-contract/                 # 共享合约：宿主与插件都依赖
│   ├── pom.xml
│   └── src/main/java/com/example/contract/
│       ├── Greeting.java              # capability 合约接口
│       └── GreetingConfig.java        # 配置类型（config token 必须宿主可见）
└── greeting-plugin/                   # 插件：产物是可加载 JAR
    ├── pom.xml
    └── src/main/java/com/example/plugin/
        ├── GreetingProvider.java      # @Extension 入口
        └── GreetingFactory.java       # 工厂与组件实现
```

合约模块是普通 jar，没有特殊依赖。插件模块的 pom 关键点是**全部 Knotra/PF4J/合约依赖都用 `provided`**，保证它们不进插件 JAR：

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>
    <groupId>com.example</groupId>
    <artifactId>greeting-plugin</artifactId>
    <version>2.0.0</version>
    <packaging>jar</packaging>

    <properties>
        <maven.compiler.release>21</maven.compiler.release>
    </properties>

    <dependencies>
        <dependency>
            <groupId>io.knotra</groupId>
            <artifactId>knotra-core</artifactId>
            <version>0.1.0-SNAPSHOT</version>
            <scope>provided</scope>
        </dependency>
        <dependency>
            <groupId>io.knotra</groupId>
            <artifactId>knotra-pf4j-spi</artifactId>
            <version>0.1.0-SNAPSHOT</version>
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

仓库测试 fixture 用的是另一条等价路线：依赖用 compile 作用域编译，再靠 maven-jar-plugin 的 include 规则把插件包和 `META-INF/**` 以外的类全部排除。两条路线效果相同，`provided` 对独立插件工程更简单。

## 第一步：合约模块

```java
package com.example.contract;

public interface Greeting {
    String greet(String name);
}
```

```java
package com.example.contract;

public record GreetingConfig(String template) {}
```

**关键约束**：用作 capability 合约和 config token 的类型必须来自共享合约包，并且该包名要传给适配器（见第五步）。插件私有类型会在发现或激活前被拒绝。

## 第二步：Provider 与工厂

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
        return List.of(ExportedComponentFactory.of(
                GreetingConfig.class, new GreetingFactory()));
    }
}
```

无配置工厂用 `ExportedComponentFactory.noConfig(factory)` 导出。Typed artifact handle 可直接调用 `mount(context, mountId)`；Loader desired tree 使用 `ComponentEntry.of(...)`：

```java
ExportedComponentFactory.noConfig(new SimpleFactory())
```

```java
package com.example.plugin;

import com.example.contract.Greeting;
import com.example.contract.GreetingConfig;
import io.knotra.*;

public final class GreetingFactory implements ComponentFactory<GreetingConfig> {
    static final CapabilityKey<Greeting> GREETING =
            CapabilityKey.of("app.greeting", Greeting.class);

    @Override
    public String factoryId() {
        return "greeting";                       // 宿主用这个 id 做 typed resolve
    }

    @Override
    public Component<GreetingConfig> create() {
        return new Component<>() {
            @Override
            public ComponentDescriptor descriptor() {
                return ComponentDescriptor.of();
            }

            @Override
            public void start(ActivationContext context, GreetingConfig config) {
                Greeting service = name -> config.template().formatted(name);
                context.provide(GREETING, service);
                context.lifecycle().manage("greeting", (AutoCloseable) () -> {
                    // 释放该次 Activation 打开的资源
                });
            }
        };
    }

    @Override
    public GreetingConfig normalizeConfig(GreetingConfig config) {
        if (config.template() == null || config.template().isBlank()) {
            throw new IllegalArgumentException("template must not be blank");
        }
        return new GreetingConfig(config.template().trim());
    }
}
```

## 第三步：extensions.idx

PF4J 靠 `META-INF/extensions.idx` 发现扩展。两种生成方式任选：

- **自动生成（推荐）**：`@Extension` 注解由 PF4J 自带的注解处理器处理。pf4j 在编译类路径上（`provided` 作用域也算），`mvn package` 会自动在 `target/classes/META-INF/extensions.idx` 生成索引，无需额外配置。
- **手动维护**：在 `src/main/resources/META-INF/extensions.idx` 写一行 provider 全限定名：

```text
com.example.plugin.GreetingProvider
```

文件格式是每行一个扩展类全限定名，`#` 开头为注释，空行忽略。索引缺失或 provider 不在里面时，加载会在发现阶段失败，报错文本为 `artifact has no RuntimeComponentProvider entries: <artifactId>`。

注意 provider 类需要 `public` 且可无参实例化（PF4J 默认用无参构造反射创建实例）。

## 第四步：插件描述符

适配器按 `plugin.properties` → `MANIFEST.MF` 的顺序探测描述符，两者字段一一对应：

| MANIFEST 条目 | properties 键 | 必填 | 说明 |
|---|---|---|---|
| `Plugin-Id` | `plugin.id` | 是 | 即运行时 artifactId |
| `Plugin-Version` | `plugin.version` | 是 | 版本号 |
| `Plugin-Description` | `plugin.description` | 否 | 描述 |
| `Plugin-Class` | `plugin.class` | 否 | 自定义生命周期类；缺省 `org.pf4j.Plugin` |
| `Plugin-Provider` | `plugin.provider` | 否 | 提供方 |
| `Plugin-Dependencies` | `plugin.dependencies` | 否 | 插件间依赖 |
| `Plugin-Requires` | `plugin.requires` | 否 | PF4J 版本要求 |
| `Plugin-License` | `plugin.license` | 否 | 许可证 |

`Plugin-Dependencies` 的格式：逗号分隔的插件 id；`id@版本` 声明版本约束；`?` 后缀表示可选依赖，例如 `base-plugin, utils@1.2.0, optional-plugin?`。

用 `plugin.properties` 时放在 JAR 根目录：

```properties
plugin.id=greeting-plugin
plugin.version=2.0.0
```

用 MANIFEST 时由 maven-jar-plugin 写入（见上文 pom 模板）。缺少有效 id/version 时报 `cannot read a valid PF4J descriptor from <path>`。

## 第五步：构建与加载验证

```bash
cd greeting-plugin
mvn package
jar tf target/greeting-plugin-2.0.0.jar
```

内容检查清单：

```text
应包含：
  com/example/plugin/GreetingProvider.class
  com/example/plugin/GreetingFactory.class
  META-INF/MANIFEST.MF
  META-INF/extensions.idx

不应包含：
  com/example/contract/**    （共享合约，宿主提供）
  io/knotra/**               （Core，宿主提供）
  io/knotra/pf4j/spi/**      （SPI，宿主提供）
  org/pf4j/**                （PF4J，宿主提供）
```

宿主侧加载（注意共享合约包要传给适配器）：

```java
try (KnotraRuntime runtime = KnotraRuntime.create();
     Pf4jArtifactAdapter adapter = Pf4jArtifactAdapter.create(
             Path.of("plugins"), runtime, Set.of("com.example.contract"))) {

    adapter.loadArtifact(Path.of("plugins/greeting-plugin-2.0.0.jar"));

    // 只读目录可先确认 factory id 与配置类型名
    adapter.factories().find("greeting")
            .ifPresent(entry -> System.out.println(entry.configTypeName()));

    ArtifactFactoryHandle<GreetingConfig> factory =
            adapter.factories().resolve("greeting", GreetingConfig.class).orElseThrow();
    ComponentHandle<GreetingConfig> handle = factory.mount(
            runtime.root(), "greeting", new GreetingConfig("hello %s"));
    handle.whenSettled().toCompletableFuture().get(10, TimeUnit.SECONDS);
}
```

`loadArtifact(Path)` 的路径解析规则：传入路径立即转绝对路径并归一化，相对路径按**JVM 工作目录**解析，不相对 `pluginsRoot`。`pluginsRoot` 的作用是依赖闭包解析与同 id 冲突的扫描范围，不限制目标 JAR 位置。

## ClassLoader 契约

- 默认强制从宿主加载的包：`io.knotra`、`io.knotra.pf4j.spi`、`org.pf4j`，加上传给 `create(...)` 的额外共享包。
- 共享包匹配规则是**包名相等或精确子包**，不是任意字符串前缀（`io.knotraevil` 不会命中 `io.knotra`）。
- 额外共享包声明会去除首尾空白；空白、前后缀 `.`、null 元素被拒绝。
- 插件里所有对共享包类型的引用都拿到宿主身份的同一个 `Class`，因此跨插件的事件类型、capability 合约和 config token 能正确相等。
- 用插件私有类型充当合约会被拒绝，报错含 `plugin-private contract type rejected`；provider 无法以宿主身份看到 `RuntimeComponentProvider` 接口时报 `shared interface identity mismatch`。

## factoryId 与版本管理

- 同一插件内重复 factoryId：加载失败，`duplicate factory id inside artifact: <factoryId>`。
- 两个活跃 artifact 提供同一 factoryId：后加载失败，`factory id is already cataloged: <factoryId>`。
- 同一 `Plugin-Id` 的两个不同 JAR 都在扫描范围内：`ambiguous PF4J repository entry`。
- 升级路径是"先卸后装"：v1 `unloadArtifact` 完成后再 `loadArtifact` v2，v2 可以复用 v1 的 factoryId。完整场景见[实战案例](<Knotra 实战案例：动态物流路由系统.md>)。
- 需要两版本并存（灰度/蓝绿）时不能共用 factoryId：要么用不同 factoryId 分别挂载，要么在框架外用两个 Context 做流量切分后逐个替换。

## 插件间依赖

- 在 descriptor 里用 `Plugin-Dependencies` 声明，例如依赖插件 `base-plugin` 任意版本：`Plugin-Dependencies: base-plugin`。
- 适配器在加载前解析完整依赖闭包：依赖缺失、成环、版本不兼容都在任何插件启动前失败，报错含 `missing required PF4J dependencies` 或 `incompatible PF4J dependencies`。
- 加载与启动按拓扑顺序执行，依赖插件先启动。
- 卸载方向相反：卸载某个插件会先收集并 drain 所有依赖它的未卸载下游插件。

## 打包错误对照表

| 现象 / 报错文本 | 原因 | 处理 |
|---|---|---|
| `artifact has no RuntimeComponentProvider entries` | 缺 `META-INF/extensions.idx`，或 provider 不在索引中 | 用 `jar tf` 检查索引；确认注解处理器执行或手动维护 |
| `cannot read a valid PF4J descriptor` | 缺 `Plugin-Id` / `Plugin-Version` | 检查 manifest 条目或 plugin.properties |
| `shared interface identity mismatch` | 插件打包进了本应宿主提供的类 | 确认依赖为 `provided`，检查 JAR 内容 |
| `plugin-private contract type rejected` | 合约/config 类型来自插件私有包 | 把类型移到共享合约包并传给适配器 |
| `missing required PF4J dependencies` | `Plugin-Dependencies` 声明的插件不在扫描范围 | 把依赖 JAR 放进 plugins root，或修正依赖声明 |
| `ambiguous PF4J repository entry` | 同 `Plugin-Id` 存在两个 JAR | 清理旧版本文件或改用不同 id |
| `factory id is already cataloged` | 另一个活跃 artifact 已占用该 factoryId | 先卸载旧 artifact，或使用新 factoryId |
| mount 报 null config | 调用了三参数 typed mount 并传 null | 无配置工厂改用 `mount(context, mountId)` |

## 相关文档

- [Knotra API 与集成指南](<Knotra API 与集成指南.md>)：宿主侧 API 与插件加载链路。
- [Knotra FAQ 与排障指南](<Knotra FAQ 与排障指南.md>)：DRAIN_FAILED、ClassLoader 回收等运行期问题。
- [Knotra 实战案例：动态物流路由系统](<Knotra 实战案例：动态物流路由系统.md>)：v1 → v2 插件升级全流程。
