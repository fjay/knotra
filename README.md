# Knotra

面向动态可组合 JVM 应用的结构化运行时。

Knotra 为应用提供一组精简的可组合原语，用于组装动态挂载的组件：类型化 Capability、层级 Context、原子 Activation、可逆 LifecycleScope，以及受控 artifact 边界。它是独立的运行时设计，不是任何其他框架的实现。

需要 Java 21+ 和 Maven 3.9+。

## 文档

- [实战案例：动态物流路由系统](<docs/Knotra 实战案例：动态物流路由系统.md>)：从仓库级能力覆盖、依赖重激活到 PF4J artifact 排空卸载，说明 Knotra 适合解决的问题和完整使用路径。
- [API 与集成指南](<docs/Knotra API 与集成指南.md>)：公开 API、模块依赖、宿主事务、EventBus、PF4J 与 Loader 接入方式。
- [运行时设计文档](<docs/Knotra 运行时设计文档.md>)：Context、Capability、Activation、LifecycleScope、依赖图和失败恢复的详细语义。

## 模块

| 模块 | 职责 | 运行时依赖 | 测试 |
|---|---|---|---|
| `knotra-core` | Runtime 内核：Context、Capability、ComponentHandle、Activation、LifecycleScope、Snapshot | 无 | 95 |
| `knotra-events` | 作为普通 Capability 发布的类型化 EventBus | `knotra-core` | 44 |
| `knotra-pf4j-spi` | 由 PF4J artifact 实现的共享提供方 SPI | `knotra-core`、PF4J（provided 作用域） | - |
| `knotra-pf4j` | PF4J artifact 适配器：加载/启动、类型化受控挂载、只读工厂目录、drain、卸载、ClassLoader 防护 | `knotra-core`、`knotra-pf4j-spi`、PF4J、ASM | 37 |
| `knotra-loader` | 基于 Core 的声明式期望状态收敛 | `knotra-core` | 36 |
| `knotra-integration-tests` | 跨模块真实测试样例验证（仅测试，不发布） | 所有模块（测试作用域） | 14 |
在仓库根目录构建全部内容：

```bash
mvn clean verify
```

Maven reactor 会按依赖顺序构建，不需要先执行 `mvn install`。

## 核心模型

运行时由五层组成：

1. **Context** 定义可见性。Context 是树中的一个节点；每个 Context 可以看到发布在自身及其祖先中的 Capability，子 Context 可以遮蔽父 Context 中的 Capability，直到子注册被撤销。
2. **Capability** 是类型化的命名值（`CapabilityKey<T>`）。注册由注册身份标识，而不是由对象相等性标识；即使用相等的值替换提供方，也会为消费方产生新的绑定代际。
3. **ComponentHandle** 是稳定的逻辑挂载点。`ComponentHandle` 拥有一个挂载 ID（`contextId + mountId`），并跨多次重新激活保持不变；该句柄的每次启动都会创建新的 `Activation`。
4. **Activation** 是组件的一次事务化执行。依赖需求会被解析为固定的 `BindingSet`；用户 `start()` 代码在协调器锁外执行，暂存注册只有在验证成功后才原子发布。过期候选会被回滚，并基于最新代际重试，而不是报告为业务失败。
5. **LifecycleScope** 拥有可逆资源。LifecycleScope 按 LIFO 顺序组成树（必要时使用显式并行组）；释放是异步且聚合的，清理失败的条目会保持可重试，并可通过 `ComponentHandle.retry()` 重试。

组件预先声明依赖需求，并在启动时收到 `ActivationContext`：

```java
public interface Component<C> {
    ComponentDescriptor descriptor();

    void start(ActivationContext context, C config) throws Exception;
}
```

`REQUIRED` 绑定会在所绑定的注册变化时重新激活消费方；`OPTIONAL` 绑定也属于同一个 `BindingSet`，因此可选提供方出现或消失同样会产生新的 Activation。Capability 合约类型在 Runtime 生命周期内按名称固定。

## 宿主事务

宿主不会通过共享可变对象修改 Runtime。结构调整通过短事务完成；事务被拒绝时不会发布任何内容：

```java
try (KnotraRuntime runtime = KnotraRuntime.create()) {
    CapabilityKey<Shell> SHELL = CapabilityKey.of("app.shell", Shell.class);

    MutationResult<ComponentHandle<AppConfig>> result = runtime.mutate(tx ->
            tx.mount(runtime.rootContext(), "app", new AppComponentFactory(), new AppConfig()));
    if (!result.committed()) {
        throw new IllegalStateException(result.diagnostics().toString());
    }
    ComponentHandle<AppConfig> app = result.value();
    app.whenSettled().toCompletableFuture().get(10, TimeUnit.SECONDS);

    // 宿主提供的 capability 通过同一个事务接口撤销
    MutationResult<RegistrationHandle> shell =
            runtime.mutate(tx -> tx.provide(runtime.rootContext(), SHELL, defaultShell()));
    runtime.mutate(tx -> {
        tx.revoke(shell.value());
        return null;
    });
}
```

读取通过 `RuntimeContext`（`require`、`find`、`info`）和 `RuntimeSnapshot` 完成；二者都不会暴露存活的组件实例、资源、释放器、`Throwable`、`Class` 或 `ClassLoader`。

## 事件

事件模块是一个普通组件，没有内核专用通道。挂载 `EventBusFactory`，并像依赖其他能力一样依赖 `EventBus` Capability。订阅必须由消费方的 LifecycleScope 管理，使运行时在 teardown 时等待已接受的 dispatch 收敛：

```java
EventBus bus = context.require(EventCapabilities.EVENT_BUS);
EventDefinition<JobFinished> JOB_FINISHED =
        EventDefinition.serial(EventKey.of(JobFinished.class));

EventSubscription subscription = bus.onSerial(JOB_FINISHED, event -> {
    // serial listener 返回 stage 保持 dispatch chain 异步
    return CompletableFuture.completedFuture(true);
});
context.lifecycle().manageAsync("job-finished-listener", subscription::closeAsync);
```

EventBus 或订阅的 `closeAsync()` 会等待关闭请求被观察到之前已接受的分发；关闭之后的新工作会被拒绝。事件身份是精确的 JVM `Class`，而不只是类型名，这条规则同样跨 artifact ClassLoader 生效。

## PF4J Artifact 边界

`knotra-pf4j` 只把 PF4J 当作 artifact 边界。加载 artifact 会启动对应的 PF4J 插件，并发现 `RuntimeComponentProvider` 导出；它本身不会直接挂载组件。每个导出的工厂都携带显式配置 token，该 token 必须属于宿主或共享合约；挂载必须通过类型化解析完成：

```java
try (KnotraRuntime runtime = KnotraRuntime.create();
     Pf4jArtifactAdapter adapter = Pf4jArtifactAdapter.create(
             Path.of("plugins"), runtime, Set.of("com.example.contract"))) {

    adapter.loadArtifact(Path.of("plugins/tool-1.0.0.jar")).join();
    ArtifactFactoryHandle<ToolConfig> factory =
            adapter.resolver().resolve("tool", ToolConfig.class).orElseThrow();
    ComponentHandle<ToolConfig> tool =
            factory.mount(runtime.rootContext(), "tool", new ToolConfig());

    adapter.unloadArtifact("tool-plugin").join(); // 先 drain owned mount
}
```

无 token 的解析器和 `factoryCatalog()` 只暴露不可变的 `ArtifactFactoryCatalogEntry` 元数据（包括稳定的配置类型名称）；它们不能挂载组件、归一化配置，也不能转换回工厂句柄。错误 token 会在类型化 `resolve(...)` 中失败；经 raw cast 传入的非空配置，也会在工厂创建或组件启动之前再次被拒绝。

适配器拥有通过其工厂句柄创建的每一个挂载：卸载会进入 drain 状态，等待执行中的挂载，逻辑 dispose 每个 owned handle（依赖该 artifact 的下游 artifact 优先），然后才停止并卸载 PF4J 插件、释放其 ClassLoader。清理失败会把 artifact 保留在 `DRAIN_FAILED` 并附带诊断；底层资源修复后，可通过 `retryDrain(...)` 完成该流程。并发的 `runtime.close()`、Loader 关闭和适配器关闭会收敛，不会因为一个所有者而导致其他所有者失败。

跨越 artifact 边界的 Class 必须来自共享合约包；插件私有的合约类型会在激活期间被拒绝，因此卸载才能真正让插件 ClassLoader 变为弱可达。

## Loader

Loader 会将期望的组件声明树与 Runtime 当前状态进行协调。条目具有稳定路径；解析器返回不透明的受控定义；失败的批次会回滚，而不是留下部分挂载：

```java
FactoryRef ref = FactoryRef.of("tool", "1.0.0");
ComponentFactoryResolver classpath = ClasspathComponentFactoryResolver.builder()
        .add(ref, new ToolComponentFactory())
        .build();

try (KnotraLoader loader = KnotraLoader.over(runtime, runtime.rootContext(), classpath)) {
    ReconcileResult result = loader.reconcile(ComponentTree.of(
            ComponentEntry.of("tools/tool", ref, new ToolConfig())));
    if (!result.converged()) {
        result.diagnostics().forEach(diagnostic -> log.warn("{}: {}", diagnostic.path(),
                diagnostic.message()));
    }
}
```

PF4J 工厂的解析器可以桥接不透明的 artifact 句柄，同时不会向 Loader 暴露 artifact 工厂、PF4J 管理器或 `RuntimeMutation`：

```java
ComponentFactoryResolver artifacts =
        ref -> bridge(adapter, ref, ToolConfig.class);

private static <C> Optional<ResolvedComponentDefinition> bridge(
        Pf4jArtifactAdapter adapter,
        FactoryRef ref,
        Class<C> configType) {
    return adapter.resolver().resolve(ref.factoryId(), configType)
            .map(handle -> {
                String fingerprint = handle.artifactId() + "@"
                        + handle.artifactVersion() + "#" + handle.factoryId();
                ConfigSchema<Object> schema = raw -> {
                    Optional<ConfigSchema<C>> selected = handle.configSchema();
                    if (selected.isPresent()) {
                        return selected.get().validate(raw);
                    }
                    return raw == null ? NoConfig.INSTANCE : raw;
                };
                ControlledMountStrategy strategy = (context, config) -> {
                    C typedConfig = configType.cast(config);
                    ComponentHandle<C> mounted = handle.mount(
                            context.context(),
                            context.mountId(),
                            typedConfig);
                    return CompletableFuture.completedFuture(mounted)
                            .thenApply(value -> (ComponentHandle<?>) value);
                };
                return new ResolvedComponentDefinition(
                        FactoryIdentity.fromRef(ref, fingerprint),
                        schema,
                        strategy,
                        ReconfigureStrategy.direct());
            });
}
```

受控策略只会收到分配出的 `ControlledMountContext`（其中的 context、挂载 ID 和一次类型化挂载操作）。它无法访问 `KnotraRuntime`、`RuntimeMutation`、任意 Context 的处置，或宿主的 Capability 发布。

同一个桥接方案会在 `knotra-integration-tests` 中针对真实插件样例验证，覆盖嵌套树、配置 schema 归一化、工厂替换、所有权，以及必须在下一次 reconcile 中收敛的 reconcile 与 drain 竞态。

## Snapshot 与诊断

`runtime.snapshot()` 报告当前代际、Context、ComponentHandle、带有绑定集的 Activation、注册、LifecycleScope 与受管条目的清理状态，以及稳定诊断。适配器和 Loader 发布各自的不可变 Snapshot（`ArtifactSnapshot`、`LoaderSnapshot`），规则相同：Snapshot 是数据，不会引用运行中的内部机制，因此持有 Snapshot 不会阻止已卸载插件的 ClassLoader 被回收。诊断码是枚举（`DiagnosticCode`、`LoaderDiagnosticCode`、结构化 artifact 操作），适合用于告警和协调逻辑。

## ClassLoader 合约

- `knotra-core` 和 `knotra-loader` 完全不感知 PF4J；每个模块都通过 Maven Enforcer 强制该依赖边界。
- `knotra-pf4j-spi` 以 `provided` 作用域针对 PF4J 编译，因此不会为不使用 artifact 的宿主增加运行时依赖。
- 共享合约包（`io.knotra`、`io.knotra.pf4j.spi`、`org.pf4j`，以及传给适配器的包）始终从宿主加载。用作 Capability 合约的插件私有类型会被拒绝。
- 成功卸载和加载失败回滚都会让插件 ClassLoader 变为弱可达；两条路径都用弱引用 GC 测试断言。

## 限制

- Loader 不监听文件系统；期望状态变化时请调用 `reconcile`。
- `stop()` 或 `unload()` 失败的 PF4J 插件会留下 `DRAIN_FAILED` 或残余诊断，必须重试；最坏情况下需要重启 JVM 恢复。适配器不会伪造成功的卸载。
- ClassLoader 回收在测试中通过弱引用和显式 GC 验证；生产环境中的回收仍取决于没有其他对象持有该 ClassLoader。
- 配置由每个工厂的配置 schema 归一化；Runtime 不强加配置文件格式。Artifact 类型化挂载拒绝 null 配置；工厂声明没有配置时使用 `NoConfig.INSTANCE`。

## 验证

`mvn clean verify` 会在 Maven reactor 中运行 226 项测试（Core 95、Events 44、PF4J 适配器 37、Loader 36、跨模块集成 14）。集成模块会构建真实的 PF4J 样例 jar，并只通过公开 API 验证：没有内部强制转换，没有 `Thread.sleep`，没有生产代码后门。

## 设计来源

Knotra 的设计参考了 Cordis 运行时模型中的思想；Knotra 是具有自身合约与语义的独立实现。
