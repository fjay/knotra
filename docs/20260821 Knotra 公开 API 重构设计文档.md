## 背景

Knotra 当前公开 API 能准确表达事务提交、异步收敛、依赖代际和可重试清理，但调用方需要同时掌握多套配置、失败和异步约定。主要问题不是内核运行语义，而是这些语义被直接暴露为默认调用路径：

- `ConfigSchema<C>` 接收任意 `Object`，Core 的 `mount` 和 `reconfigure` 却只接受 `C`，公开类型与文档承诺不一致。
- `KnotraRuntime.mutate` 把事务体异常转换为可忽略的 `MutationResult`；Component、Context、Loader、EventBus 和 PF4J 又分别使用状态值、异常 stage 和诊断列表表达失败。
- 无配置组件仍需传递 `NoConfig.INSTANCE`；`factoryId`、`componentId` 和 `mountId` 在常见场景中反复填写。
- EventBus 在 definition、订阅方法和分发方法中重复指定 mode，非法组合只能在运行时拒绝。
- Loader 到 PF4J 的组合需要宿主手写配置归一化、实现 fingerprint 和受控挂载桥接。

本次重构不保留旧 API，也不提供兼容层。运行时状态机、事务原子性、BindingSet、stale activation 回滚、LifecycleScope 清理顺序、artifact drain 和 Snapshot 不持有活动对象等现有语义保持不变。

建议分支：`refactor/public-api`

## 整体设计

公开 API 分为两层：

- 默认路径面向宿主和组件开发者，失败不会静默，常见单操作和无配置场景不要求手工展开底层事务。
- 专家路径保留显式结构事务、异步 settlement、状态与诊断，供批量原子修改、Loader、PF4J 和排障逻辑使用。

配置职责按边界拆分：

- Core 只处理类型化配置 `C`，`ComponentFactory<C>` 可以校验并归一化同类型配置。
- Loader 的 desired tree 保留 raw `Object`，由 resolver 提供的 `ConfigDecoder<C>` 在预检阶段转换为 `C`。
- PF4J export 同时携带共享 `Class<C>` token 和 decoder，使 artifact 边界能够校验 ClassLoader 身份并支持 Loader raw config。

失败职责按阶段拆分：

- 参数错误和非法 API 使用立即抛出 `IllegalArgumentException` 或 `IllegalStateException`。
- 结构事务拒绝抛出 `TransactionRejectedException`，异常携带稳定 `RuntimeDiagnostic` 列表；事务仍保证零发布。
- 组件启动或清理失败继续以 `ComponentState.FAILED` 表达，因为它是可观察、可重试的运行状态，不转换成一次性异常。
- Loader reconcile 可能包含已提交变化，继续返回结构化 `ReconcileResult`，同时提供 `requireConverged()` 作为默认成功路径。
- Event listener 失败继续进入 dispatch report，不使已接受的整体 dispatch 异常完成。
- PF4J imperative operation 通过异常完成的 `CompletionStage` 返回 `ArtifactOperationException`。

返回 `CompletionStage` 的生命周期动作使用 `Async` 后缀；`whenSettled()`、`whenIdle()` 表示等待条件。EventBus 为保持五种 definition 的单一动词，异步 mode 仍统一使用 `dispatch` overload。阻塞 `close()` 统一通过 `join()` 以 unchecked failure 结束并验证最终状态。

## Core API

### 类型化配置

删除 `ConfigSchema<C>`。Component factory 直接提供同类型归一化钩子：

```java
public interface ComponentFactory<C> {
    default String factoryId() {
        return getClass().getName();
    }

    Component<C> create();

    default C normalizeConfig(C config) throws Exception {
        return Objects.requireNonNull(config, "config");
    }
}
```

Core 的 `mount` 和 `reconfigure` 保持类型化。配置归一化抛错或返回 null 时，事务以 `INVALID_CONFIG` 拒绝，不再使用 `INVALID_LIFECYCLE_OPERATION`。

无配置路径通过 overload 隐藏 unit value：

```java
ComponentHandle<NoConfig> mount(
        ContextHandle context,
        String mountId,
        ComponentFactory<NoConfig> factory);

ComponentHandle<NoConfig> mountChild(
        String mountId,
        ComponentFactory<NoConfig> factory);
```

Runtime 内部仍使用 `NoConfig.INSTANCE`，但宿主挂载 EventBus 等无配置组件时不再传递它。

原始配置转换使用独立契约：

```java
@FunctionalInterface
public interface ConfigDecoder<C> {
    C decode(Object raw) throws Exception;

    static <C> ConfigDecoder<C> typed(Class<C> type) { ... }
    static ConfigDecoder<NoConfig> noConfig() { ... }
}
```

`ConfigDecoder` 供 Loader 和 artifact export 使用，不进入 Core 的直接 mount 路径。

### 结构事务

`RuntimeMutation` 更名为 `RuntimeTransaction`，`mutate` 更名为 `transact`。成功返回值不再同时表示 rejected 分支：

```java
public record TransactionReceipt<R>(
        R value,
        long generation,
        CompletionStage<Void> settlement) {
}

public final class TransactionRejectedException extends RuntimeException {
    List<RuntimeDiagnostic> diagnostics();
}

public interface KnotraRuntime extends AutoCloseable {
    ContextHandle root();
    RuntimeSnapshot snapshot();

    <R> TransactionReceipt<R> transact(
            Function<RuntimeTransaction, R> transaction);
}
```

事务体异常、配置拒绝和结构冲突都导致零发布并抛出 `TransactionRejectedException`。因此即使调用方不使用 receipt，也不能无声忽略失败。

Runtime 提供根 Context 上的单操作便利入口：

```java
<T> RegistrationHandle provide(CapabilityKey<T> key, T value);

<C> ComponentHandle<C> mount(
        String mountId,
        ComponentFactory<C> factory,
        C config);

ComponentHandle<NoConfig> mount(
        String mountId,
        ComponentFactory<NoConfig> factory);
```

替换 provider、同时挂载多个组件或需要原子 revoke/provide 时继续使用 `transact`。

### Component 与 Context 句柄

`ComponentHandle<C>` 保留配置泛型，使直接 `reconfigureAsync(C)` 继续得到编译期约束。动作方法统一命名：

```java
CompletionStage<ComponentState> whenSettled();
CompletionStage<ComponentState> reconfigureAsync(C config);
CompletionStage<ComponentState> retryAsync();
CompletionStage<ComponentState> disposeAsync();
```

`close()` 阻塞等待 `disposeAsync()`；最终状态不是 `DISPOSED` 时抛出清理异常，不能像现有实现一样正常返回 `FAILED`。

只读 Context 视图从结构句柄中明确区分：

```java
public interface ContextHandle extends AutoCloseable {
    String contextId();
    ContextInfo info();
    ContextView view();
    ContextState state();
    CompletionStage<ContextState> disposeAsync();
}

public interface ContextView {
    String contextId();
    ContextInfo info();
    <T> T require(CapabilityKey<T> key);
    <T> Optional<T> find(CapabilityKey<T> key);
}
```

Runtime 根读取统一使用 `runtime.root().view()`，删除 `runtime.context()` 与 `rootContext().context()` 两条重复路径。

### 组件声明与资源托管

`ComponentFactory.factoryId()` 提供默认实现，普通 factory 不再必须重复声明类级 ID。`ComponentDescriptor` 仍保留可选显示 ID和 requirement 集合，但增加无需 ID 的创建入口；Runtime 在缺省时固化 factory ID 作为 component ID。

新增统一异步资源契约：

```java
public interface AsyncCloseable extends AutoCloseable {
    CompletionStage<Void> closeAsync();
}
```

`LifecycleScope` 将异步清理动作和异步资源分开命名：

```java
ManagedHandle onCloseAsync(String description, AsyncDisposer disposer);
<T extends AsyncCloseable> T manageAsync(String description, T resource);
```

Event subscription 的托管因此变为 `scope.manageAsync("listener", subscription)`，不再手写 `subscription::closeAsync`。

## EventBus API

事件 mode 编码到 definition 的静态类型中。删除公开 `EventKey`，definition 直接持有事件名与 JVM 类型：

```java
public sealed interface EventDefinition<T> {
    record Sync<T>(String name, Class<T> eventType) implements EventDefinition<T> {}
    record Parallel<T>(String name, Class<T> eventType) implements EventDefinition<T> {}
    record Serial<T>(String name, Class<T> eventType) implements EventDefinition<T> {}
    record Bail<T>(String name, Class<T> eventType) implements EventDefinition<T> {}
    record Waterfall<T>(String name, Class<T> eventType) implements EventDefinition<T> {}

    static <T> Sync<T> sync(Class<T> type) { ... }
    static <T> Serial<T> serial(Class<T> type) { ... }
}
```

EventBus 订阅统一使用 `subscribe`，异步分发统一使用 `dispatch`：

```java
<T> EventSubscription subscribe(
        EventDefinition.Serial<T> event,
        SerialEventListener<? super T> listener);

<T> CompletionStage<EventDispatch<T>> dispatch(
        EventDefinition.Serial<T> event,
        T value);
```

五种 overload 的第一个参数擦除后类型不同，不产生签名冲突。Definition 被上转为 `EventDefinition<?>` 后不能直接订阅或分发，这是有意的编译期边界。Sync 的 `dispatch` 直接返回 report，其余 mode 返回 `CompletionStage`。

原有事件接受、监听集合快照、取消、accepted work 排空和 ClassLoader identity 语义不变。运行时 mode mismatch 测试删除，改为覆盖 overload 的编译期形状和 raw/ClassLoader 防护。

`EventBus` 与 `EventSubscription` 实现 `AsyncCloseable`，可直接交给 `LifecycleScope.manageAsync`。

## Loader 与 PF4J

### Loader 声明

`ComponentEntry` 区分无配置和 raw 配置：

```java
ComponentEntry.of(path, factoryRef, children...);
ComponentEntry.configured(path, factoryRef, rawConfig, children...);
```

无配置 entry 在 resolver 预检时通过 `ConfigDecoder.noConfig()` 转为 `NoConfig.INSTANCE`。普通 desired tree 不再显式携带 Core 的 unit value。

`ResolvedComponentDefinition` 更名为 `ResolvedFactory`，继续作为 Loader expert SPI，职责限定为：

- 返回稳定 `FactoryIdentity`。
- 在任何结构修改之前 decode raw config。
- 通过 single-use `ControlledMountContext` 挂载已解码配置。
- 对既有 handle 应用同类型重配置。

`ReconcileResult` 增加 `requireConverged()`。该方法只验证无诊断和 desired state 已收敛，不把 `WAITING` 错当成失败。

### PF4J 工厂目录

PF4J 的目录查询收敛到单一入口：

```java
public interface ArtifactFactoryCatalog {
    List<ArtifactFactoryCatalogEntry> list();
    Optional<ArtifactFactoryCatalogEntry> find(String factoryId);
    Optional<ArtifactFactoryHandle<?>> resolve(String factoryId);
    <C> Optional<ArtifactFactoryHandle<C>> resolve(
            String factoryId,
            Class<C> configType);
}

ArtifactFactoryCatalog factories();
```

删除 adapter 的 `factoryCatalog()` 与 `resolver().handles()` 重复入口。无 token resolve 返回 wildcard executable handle，调用方不能直接把任意配置传给其 typed mount；官方桥接通过 capture helper、共享 `Class<C>` token 和 decoder 完成安全调用。

PF4J 动作统一使用异步后缀并只暴露 `CompletionStage`：

```java
CompletionStage<ArtifactSnapshot> loadArtifactAsync(Path path);
CompletionStage<Void> unloadArtifactAsync(String artifactId);
CompletionStage<Void> retryDrainAsync(String artifactId);
```

同步 convenience 方法阻塞等待上述 stage。Snapshot、diagnostic 与 ownership 查询保持同步。

### 官方 Loader 桥接

新增 `knotra-pf4j-loader` 模块，只依赖 `knotra-loader` 和 `knotra-pf4j`。`knotra-loader` 继续不感知 PF4J。

```java
public final class Pf4jFactoryResolver implements ComponentFactoryResolver {
    public static Pf4jFactoryResolver of(Pf4jArtifactAdapter adapter);

    public static ComponentFactoryResolver withFallbacks(
            Pf4jArtifactAdapter adapter,
            ComponentFactoryResolver... fallbacks);
}
```

桥接从 catalog entry 获取 artifact 坐标、配置 token 和 decoder，统一生成 fingerprint，并通过 `ControlledMountContext` 挂载。宿主不再手写 `ConfigSchema<Object>`、unchecked cast 或 fingerprint 拼接。

桥接只解析 ACTIVE artifact。drain 与 reconcile 并发时保持现有行为：新 mount 被拒绝，Loader 回滚本批新增，下一次 reconcile 可选择 fallback 或重新加载后的实现。

## 影响与发布

这是一次完整的 0.x API 替换：Core、Events、Loader、PF4J、SPI、所有 fixture、README 和专题文档必须在同一个版本中发布，不能混用新旧模块。仓库没有持久化数据或远端协议迁移，失败恢复方式是回退整个 reactor 版本；不提供按模块回退。

删除的旧类型和方法不保留 deprecated alias，避免长期存在两套配置与失败模型。发布前需要确认所有示例只使用新 API，尤其不能继续出现 `NoConfig.INSTANCE` 的普通 mount、未检查的 mutation result、EventBus mode 方法重复和手写 PF4J Loader bridge。

## 测试

自动化测试需要覆盖以下行为：

- 成功事务返回 receipt；结构冲突和事务体异常抛出携带诊断的 `TransactionRejectedException`，且 generation 与结构不变。
- 类型化配置归一化成功；归一化抛错或返回 null 使用 `INVALID_CONFIG`。
- 无配置 runtime mount、transaction mount、child mount 和 Loader entry 都不要求调用方传 `NoConfig.INSTANCE`。
- `ComponentHandle.close()` 遇到清理失败必须抛出，handle 保持 `FAILED`，`retryAsync()` 只重试失败条目。
- Context view 只有 `root().view()` 一条根读取路径；dispose settlement 仍等待完整子树。
- 每种 EventDefinition 只能调用对应的 `subscribe`/`dispatch` overload；监听失败、提前停止、waterfall 转换和 accepted work 排空保持现有结果。
- EventSubscription 和 EventBus 可直接由 `LifecycleScope.manageAsync` 托管。
- Loader raw decoder 在结构变更前运行，任一 decode 失败使新增批次零挂载。
- `ComponentEntry.of` 产生无配置声明，`configured` 保留 raw 输入。
- PF4J catalog 只有一个公开入口；typed resolve 继续校验 exact `Class`，wildcard handle 只能由安全 capture 路径挂载。
- 官方 `Pf4jFactoryResolver` 不需要宿主维护 factoryId 到 `Class<C>` 的映射即可完成 nested tree reconcile。
- artifact drain 与 Loader reconcile 的竞态不留下 partial mount；reload 后 fingerprint 变化并触发 replacement。
- 完整 Maven reactor 的单元、真实插件 JAR、跨模块 close 与 ClassLoader GC 测试全部通过。
