# Knotra 测试指南

本文描述当前 `0.1.0-SNAPSHOT` 的测试方法，范围从最简单的业务 POJO 组件到 Core 并发语义、编译期生成、Spring、Events、Loader、PF4J 和跨模块集成。文档只覆盖当前源码已经测试过的行为。

语义背景可参考 [Knotra API 与集成指南](<Knotra API 与集成指南.md>)、[Knotra Beans 与 Spring 集成指南](<Knotra Beans 与 Spring 集成指南.md>) 和 [Knotra 线程模型与生产实践](<Knotra 线程模型与生产实践.md>)。

## 测试原则

1. **断言语义，不断言调度时机。** 优先检查 `ComponentState`、`ContextState`、activation id、registration id、config revision、generation、Snapshot、diagnostic code 和 ownership；不要用“方法调用过几次”代替绑定或所有权结论。
2. **有界等待确定性事件。** 等待 `CompletionStage` 使用 `get(timeout)` 或 Awaitility；进入并发区使用 `CountDownLatch`；控制释放使用 `CompletableFuture` gate，并在 `finally` 中放行。
3. **不使用固定睡眠。** `future.isDone() == false` 只有在 latch 已确认对立动作进入目标区间后才有意义，例如 provider cleanup 已进入、listener 已接受事件或 start 已阻塞。
4. **失败也要验证保留现场。** cleanup 失败应断言 `FAILED`、失败条目仍可 retry、成功条目没有被重放；事务拒绝应断言 generation、registration 和 Snapshot 未部分提交。
5. **测试资源必须关闭。** Runtime、Loader、PF4J adapter 和 executor 都要收敛；故意失败的 close 可以重复调用以完成 retry。
6. **诊断优先匹配 code。** `TransactionRejectedException.diagnostics()`、Loader `ReconcileResult.diagnostics()` 和 PF4J structured exception 是契约；错误文本只用于辅助定位。

常用命令：

```bash
mvn test
mvn clean verify

mvn -pl knotra-core test
mvn -pl knotra-beans test
mvn -pl knotra-beans-processor test
mvn -pl knotra-spring test
```

根目录 `mvn clean verify` 会重新编译并执行全部 reactor 模块，是提交前的最低门槛。

## 最小 Beans 测试

普通业务代码先按 POJO 测试。业务类不需要依赖 Knotra，装配层只定义 Capability 和 Factory：

```java
static final CapabilityKey<Storage> STORAGE =
        CapabilityKey.of("test.storage", Storage.class);
static final CapabilityKey<UserService> USER_SERVICE =
        CapabilityKey.of("test.user-service", UserService.class);

KnotraRuntime runtime = KnotraRuntime.create();

@AfterEach
void tearDown() {
    runtime.close();
}

@Test
void providerReplacementCreatesFreshService() throws Exception {
    RegistrationHandle first = runtime.provide(STORAGE, new Storage("one"));
    List<UserService> created = new CopyOnWriteArrayList<>();

    BeanDefinition<NoConfig, UserService> definition =
            Beans.component("user-service")
                    .with(Beans.required(STORAGE))
                    .create(storage -> {
                        UserService service = new UserService(storage);
                        created.add(service);
                        return service;
                    })
                    .provide(USER_SERVICE)
                    .build();

    ComponentHandle<NoConfig> handle =
            runtime.mount("user-service", definition);
    assertEquals(ComponentState.ACTIVE, settle(handle));

    runtime.revoke(first);
    assertEquals(ComponentState.WAITING, settle(handle));
    runtime.provide(STORAGE, new Storage("two"));
    assertEquals(ComponentState.ACTIVE, settle(handle));

    assertEquals(2, created.size());
    assertTrue(created.getFirst().closed);
    assertFalse(created.getLast().closed);
    assertEquals("two", created.getLast().storage.name());
}

private ComponentState settle(ComponentHandle<?> handle) throws Exception {
    return handle.whenSettled().toCompletableFuture()
            .get(10, TimeUnit.SECONDS);
}
```

这个 fixture 覆盖 Beans 的核心不变量：

- Component 外壳跨 Activation 复用，但 Bean 每次 Activation 新建。
- provider 消失时 required consumer 进入 `WAITING`。
- provider 回来时创建新 Bean。
- 旧 Bean 在旧 Activation 清理中关闭，新 Bean 保持打开。

当前源码证据：[BeansTest.java](../knotra-beans/src/test/java/io/knotra/beans/BeansTest.java)。

## Core 低层夹具

只有测试 Core 专家 API 或实现新适配层时才手写 `ComponentFactory`。保持夹具小而显式：

```java
static <C> ComponentFactory<C> factory(
        String id,
        ComponentDescriptor descriptor,
        TestStart<C> start) {
    return new ComponentFactory<>() {
        @Override
        public String factoryId() {
            return id;
        }

        @Override
        public Component<C> create() {
            return new Component<>() {
                @Override
                public ComponentDescriptor descriptor() {
                    return descriptor;
                }

                @Override
                public void start(ActivationContext context, C config)
                        throws Exception {
                    start.run(context, config);
                }
            };
        }
    };
}

interface TestStart<C> {
    void run(ActivationContext context, C config) throws Exception;
}
```

低层测试必须同时验证：

- descriptor 在 mount preparation 阶段冻结，后续结构决策不重复读取可变 descriptor。
- `NoConfig.INSTANCE` 显式传入，null config 以 `INVALID_CONFIG` 拒绝。
- factory `create()` 与 typed normalizer 不持有协调器锁；用 gate 阻塞它们时，其他独立事务仍可提交。
- `start()` 中 `provide` 的 registration 和组件状态同代提交；`STARTING` 期间 Snapshot 没有暂存 registration。
- start 失败时 lifecycle 资源回滚，暂存 capability 不可见。
- `require/find/subscribe` 只能访问 descriptor 声明的 key，错误绑定方式立即失败。
- `ActivationContext` 在 start 返回后关闭，保存下来的 context 不能再访问。
- start 内取得的 `LifecycleScope` 在 `OPEN` 期间允许 late manage；进入 `STOPPING` 后拒绝新条目。

## Pinned 与 Optional

PINNED 是默认语义。测试 provider 替换时，不要只比较 value 相等，还要比较 registration identity 和 activation identity。

### Required

已覆盖行为：

1. required capability 缺失时组件为 `WAITING`，`start()` 不执行，并产生 `MISSING_CAPABILITY`。
2. capability 出现后组件激活。
3. provider 被撤销后组件回到 `WAITING`。
4. 新 provider 即使 value 相等，只要 registration identity 不同，也会触发新 Activation。
5. 旧 BindingSet 中的对象在整个旧 Activation 内固定；替换事务等待旧 consumer 清理完成后才让新 consumer 可见。

关键断言形状：

```java
String firstActivation = component(runtime, handle)
        .currentActivationId();

TestKit.assertCommitted(runtime.transact(tx -> {
    tx.revoke(first);
    tx.provide(runtime.root(), TEXT, "same");
    return null;
}));

assertEquals(ComponentState.ACTIVE, settle(handle));
assertNotEquals(firstActivation,
        component(runtime, handle).currentActivationId());
```

### Optional

OPTIONAL 缺失不是向 pinned 注入 null。缺失时组件可以 ACTIVE，但 Snapshot binding 显式记录 `present=false`；provider 出现、消失或替换都会创建新 Activation。

```java
assertEquals(Optional.empty(), observed.get());

var registration = provide(TEXT, "one");
assertEquals(Optional.of("one"), observed.get());

revoke(registration);
assertEquals(Optional.empty(), observed.get());
```

测试还要比较前后 activation id。Beans 的 `Beans.optional(key)` 将该语义映射为 creator 参数 `Optional<D>`。

### Context shadow

子 Context registration 遮蔽父级，撤销后回落。测试需覆盖：

- child 消费者动态解析 parent provider。
- child provider 提交后遮蔽 parent。
- child registration 撤销后回落 parent。
- dynamic consumer 全程不重启。
- pinned consumer 遇到 shadow provider 提交后使用新 BindingSet 并重启。

## 类型化配置

配置测试使用真实 typed config，不用字符串模拟所有场景：

```java
record ToolConfig(String command) {}

ComponentFactory<ToolConfig> factory = new ComponentFactory<>() {
    @Override
    public String factoryId() {
        return "tool";
    }

    @Override
    public ToolConfig normalizeConfig(ToolConfig config) {
        if (config.command().isBlank()) {
            throw new IllegalArgumentException("blank command");
        }
        return new ToolConfig(config.command().trim());
    }

    @Override
    public Component<ToolConfig> create() {
        return new Component<>() {
            @Override
            public ComponentDescriptor descriptor() {
                return ComponentDescriptor.named("tool");
            }

            @Override
            public void start(
                    ActivationContext context,
                    ToolConfig config) {
                observed.add(config.command());
            }
        };
    }
};
```

已覆盖行为：

- mount preparation 执行一次 normalize，规范值进入 start。
- 相同规范 config 重复 reconcile 或 reconfigure 是 no-op，不改变 activation 和 revision。
- 配置变化复用 handle，新 Activation 使用新 config，revision 递增。
- normalizer 抛错、返回 null 或返回错误类型时以 `INVALID_CONFIG` 拒绝。
- raw 调用传入错误 config 类型时以 `INVALID_CONFIG` 拒绝，不挂载组件。
- 同一事务内连续 reconfigure 会逐个 normalize，最终只执行最后一个 config，revision 按意图计数。

## 事务原子性

结构变化通过 transaction 测试原子性，而不是分别调用多个 mutation：

```java
long generation = runtime.snapshot().generation();

TransactionRejectedException failure = assertThrows(
        TransactionRejectedException.class,
        () -> runtime.transact(tx -> {
            tx.provide(runtime.root(), TEXT, "first");
            tx.provide(runtime.root(), TEXT, "second");
            return null;
        }));

assertEquals(DiagnosticCode.CAPABILITY_SLOT_OCCUPIED,
        failure.diagnostics().getFirst().code());
assertEquals(generation, runtime.snapshot().generation());
assertTrue(runtime.root().view().find(TEXT).isEmpty());
```

已覆盖行为：

- callback 后半段失败时，前半段 child context、mount 和 provide 全部零提交。
- provisional handle、context 和 registration 可被同一事务后续 intent 使用。
- provisional handle 可先 mount 再 reconfigure，最终 revision 反映全部意图。
- 失败事务中的 provisional handle 变为 `DISPOSED`，不能泄漏为可用结构。
- 每个已提交结构事务 generation 精确加一；no-op 或拒绝不增加。
- 同名 Capability 在同一 Context 中只有一个 slot，类型冲突和 slot 占用分别是稳定 diagnostic。
- Runtime closing 后新事务以 `INVALID_LIFECYCLE_OPERATION` 拒绝。

## Stale Activation

用 latch 阻塞旧 `start()`，在阻塞期间提交新的结构变化：

```java
CountDownLatch started = new CountDownLatch(1);
CompletableFuture<Void> gate = new CompletableFuture<>();

var handle = mount("consumer", (context, config) -> {
    started.countDown();
    gate.get();
    observed.set(context.require(TEXT));
}, CapabilityRequirement.required(TEXT));

var first = provide(TEXT, "one");
assertTrue(started.await(10, TimeUnit.SECONDS));

revoke(first);
gate.complete(null);

assertEquals(ComponentState.WAITING, settle(handle));
assertTrue(runtime.snapshot().diagnostics().stream().noneMatch(d ->
        d.code() == DiagnosticCode.ACTIVATION_FAILED
                && d.targetId().equals(handle.handleId())));

provide(TEXT, "two");
assertEquals(ComponentState.ACTIVE, settle(handle));
assertEquals("two", observed.get());
```

额外要求：

- 旧 start 被判定 stale 后，即使随后抛业务异常，也按 stale rollback 处理，不产生 `ACTIVATION_FAILED`。
- STARTING 中的 shadow provider 未提交前，当前视图仍使用原 binding，Snapshot 没有该 provider registration。
- STARTING 中的 dynamic required consumer 失去 provider 时回滚到 `WAITING`，保存的 `DynamicCapability` 之后调用得到 `DynamicCapabilityClosedException`。
- reconfigure 等待当前用户 start 完成后再执行旧 scope 回滚和新 start。

## Lifecycle 与清理重试

LifecycleScope 测试重点是顺序、等待和失败保留。

### 顺序

- 同一 scope 按 LIFO 执行。
- nested child 作为整体插入父 scope 的全局登记顺序。
- parallel child 中条目并发执行，只包含自己的直接条目；父级晚登记的条目仍先于该 group 汇合点完成。
- direct 和 indirect dependent 先于 provider 清理。
- provider 替换时，新 provider activation 不与旧 consumer cleanup 重叠。

### 异步等待

```java
CountDownLatch entered = new CountDownLatch(1);
CompletableFuture<Void> gate = new CompletableFuture<>();

context.lifecycle().onCloseAsync("resource", () -> {
    entered.countDown();
    return gate;
});
```

断言 disposal future 在 gate 完成前未完成，gate 完成后得到 `DISPOSED`。`manageAsync` 对 `AsyncCloseable` 使用同一等待契约。

### 失败重试

已覆盖：

- 某个 cleanup 条目失败不影响其他条目执行。
- retry 只重试失败条目；成功条目跨多次尝试不重放。
- 嵌套 child、parallel async child 失败同样保留并重试。
- start failure 与 cleanup failure 是不同 diagnostic。
- reconfigure 遇到 cleanup 失败时，不启动下一代；先 retry 清理，再 retry 出新 Activation。
- owned child 清理失败时，旧 child 保持可 retry，新 child 不能抢占 mount id；旧 child 完成后父组件 retry 才创建新 child。

### 同步 close 与 Runtime close

- `handle.close()` 遇到清理失败会抛出异常并保持 `FAILED`，不能静默吞掉失败。
- `ContextHandle.disposeAsync()` 得到 `FAILED` 时正常返回状态；再次 `disposeAsync()` 继续重试。
- Runtime `closeAsync()` 失败时异常完成并保留执行器；再次调用继续收敛，成功后拒绝新 mutation。
- 并发 dispose 只执行一次 cleanup transition。

## Dynamic Capability

Dynamic 测试必须证明 provider 换代与 consumer 生命周期解耦，同时证明旧 provider 不会被提前关闭。

### 基础语义

已覆盖：

- dynamic requirement 在 Snapshot 中是 `DYNAMIC` binding，缺失时 `present=false`、registration id 为 null。
- dynamic 声明不能通过 pinned `require/find` 读取。
- `dynamicRequired` 首次提交前要求 provider 存在；ACTIVE 后 provider 消失不重启 consumer。
- `dynamicOptional` 缺失时 consumer 可 ACTIVE，`available()` 为 false。
- provider 缺失时调用得到 `CapabilityUnavailableException`。
- consumer 已关闭或 capability 已关闭时调用得到 `DynamicCapabilityClosedException`。
- provider 替换后 consumer 不重启，后续调用解析新 provider。
- context shadow 变化只改变后续解析，不重启 consumer。
- dynamic 与 pinned 组成的可达环同样触发 `BINDING_CYCLE`，不会伪装成普通缺失。

### 提交边界

Provider component 在 `STARTING` 中 staged 的 registration 对 dynamic consumer 不可见：

```java
assertTrue(staged.await(10, TimeUnit.SECONDS));
assertEquals(ComponentState.STARTING, provider.state());
assertFalse(dynamic.get().available());

release.complete(null);
assertEquals(ComponentState.ACTIVE, settle(provider));
assertEquals("v1", dynamic.get().call(Api::value));
```

### 同步 lease

一次 `call(...)` 内的多次目标方法调用固定同一 provider。原子替换事务在旧调用未完成时不能 settle；期间新调用可以解析并使用新 provider：

```java
var call = dynamic.get().call(api -> {
    String first = api.value();
    entered.countDown();
    release.join();
    return first + "|" + api.value();
});

var receipt = replaceProvider("v2");
assertEquals("v2", dynamic.get().call(Api::value));

release.complete(null);
assertEquals("v1|v1", call.get(10, TimeUnit.SECONDS));
receipt.settlement().toCompletableFuture().get(10, TimeUnit.SECONDS);
```

### 异步 lease

- `callAsync` 在返回的 `CompletionStage` 完成前持有 provider lease。
- proxy 方法返回 `CompletionStage` 时，lease 延续到该 stage 完成。
- stage 组合本身抛异常时也释放 lease，后续 provider revoke 可以 settle。
- consumer dispose、provider reconfigure 和 Runtime close 都等待在途同步或异步调用。
- consumer 开始 teardown 后拒绝新 dynamic 调用。

### Proxy

`proxy(Class)` 只接受当前 `DynamicCapability<T>` 的 exact contract interface，不接受子接口。proxy 的 `equals/hashCode/toString` 使用 proxy 身份；业务方法异常从 provider 异常原样解包。

多方法事务不能靠连续调用 proxy 方法固定 provider；测试多方法原子性应使用一次 `call`、`callAsync` 或 `withCurrent`。

## Beans 高级路径

### Creator 形状

- 0 到 5 个依赖都有定长泛型重载，依赖顺序即 creator 参数顺序。
- typed config 的 creator 第一个参数固定为 config，其后是依赖。
- `with(...)` 可以分多次调用，最终仍按传入顺序合并。
- Optional 依赖传入 `Optional<D>`；dynamic 依赖可注入 interface proxy 或显式 `DynamicCapability<D>`。
- `BeanDefinition` 的 factory id、component id 和 descriptor component id 一致且稳定。
- output key 列表只读并保持声明顺序。

### 生命周期

已覆盖：

- `AsyncCloseable` 优先于 `AutoCloseable`。
- 普通 `AutoCloseable` 自动 `manage`。
- `unmanaged()` 不登记 cleanup。
- `destroyWith` 替代自动 close 推断。
- `destroyAsyncWith` 的 stage 完成前 disposal 不 settle。
- cleanup 失败后 `retryAsync()` 只再次执行失败的 disposer。

### 初始化与输出

Beans 在 creator 返回后立即登记 cleanup，再执行 initializer，最后暂存输出并交给 Core 原子提交。因此：

- initializer 抛错时 Bean 已关闭。
- 任一输出转换失败时，所有输出都不可见，Bean 关闭。
- 多输出成功时同时可见。
- 同名输出重复声明在 DSL 构建期拒绝。
- start rollback 后 waiting reader 不会读到任何输出。

### Expert API

`BeanDefinition.expert(...)` 保留 dependency list 和 `(ActivationContext, config)` creator，用于无法映射到定长 creator 的边界。测试应确认：

- 传入 dependencies 仍是 descriptor 契约。
- required/optional mode 正确进入 descriptor。
- optional 出现和消失仍触发新 Activation。
- 输出仍通过 `provide/provideAs` 声明，不由 expert creator 私自提交。

### 失败契约

- creator 返回 null：Activation `FAILED`，诊断包含稳定原因。
- creator 抛 checked exception：Activation `FAILED`，资源回滚。
- normalizer 返回 null 或错误类型：mount 以 `INVALID_CONFIG` 拒绝。
- raw factory 携带错误 config 类型：mount 以 `INVALID_CONFIG` 拒绝。

## Beans Processor

Processor 测试必须真实调用 JavaCompiler，并加载生成类执行，不能只断言源码文本存在。

### 正向生成

当前覆盖：

- `ServiceLoader` 能发现 processor。
- valid no-config bean 生成同包 `ValidBean_KnotraFactory`。
- 两次编译生成完全相同源码，不包含时间戳。
- 生成源码不使用 `java.lang.reflect`，contract 使用编译期 class literal。
- 多个 factory 实例的 descriptor 相等。
- 生成 factory 可在独立 `URLClassLoader` 中实例化并 mount。
- required、Optional、dynamic proxy、多输出同时工作。
- `@KnotraInit` 和 `@KnotraDestroy` 在 activation 边界执行，close 后 activation-owned outputs 全部撤销。
- typed config 经 normalizer 规范化，reconfigure 触发 destroy/create/init 序列。
- async destroy 在返回 stage 完成前阻塞 disposal。

### 负向诊断

以下错误在编译期拒绝，且不写出 generated source：

| 场景 | 必须诊断 |
|---|---|
| 零个或多于一个 `@KnotraConstructor` | exactly one constructor |
| 参数缺少 require/optional/dynamic/config 注解 | exactly one parameter annotation |
| Optional 参数不是 `Optional<contract>` | Optional contract shape |
| dynamic contract 不是 interface | interface contract |
| config 类型不匹配 | config type |
| 输出不可赋值给 contract | output assignability |
| capability 名称重复 | duplicate capability |
| init 不是零参实例方法 | init shape |
| async destroy 不返回 `CompletionStage<Void>` | async destroy shape |
| normalizer 不是 static 单 config 参数方法 | normalizer shape |
| id 为空 | id required |
| required/optional contract 泛型或参数化 | non-generic contract |
| config 泛型或参数化 | non-generic config |
| config 是 primitive | non-primitive config |
| constructor 声明类型参数 | non-generic constructor |
| nested config/contract/output 不可访问 | accessible types |
| 已存在生成类名 | generated name conflict |

## Spring Child Context

Spring 测试以一个 Knotra Activation 对应一个 child context 为核心。

### 简单路径

```java
ComponentFactory<NoConfig> factory = SpringModules.noConfig("service")
        .annotatedClasses(ServiceConfiguration.class)
        .required("provider", PROVIDER)
        .expose(SERVICE)
        .build();
```

已覆盖：

- required provider 替换后旧 context 关闭、新 context 创建，输出 snapshot 更新。
- customizer 每代执行一次。
- typed config 支持 normalizer 和 reconfigure。
- 多输出按 Spring bean name 发布。

### 外部对象所有权

通过 external singleton registry 借给 Spring 的 Knotra provider 和 typed config 不归 child context 所有。测试确认：

- 实现外部 provider 正常注入。
- 外部对象实现了 `DisposableBean` 或 `AutoCloseable` 时，Spring 也不销毁它。
- context 多代重建和最终 dispose 都不关闭外部对象。

### Optional

`optional(...)` 在 provider 缺失时不注册对应 bean name；普通必需注入点会导致 refresh 失败。测试缺失分支时，应通过 `ObjectProvider#getIfAvailable()`、`containsBean(...)`、`@Nullable` 等显式可选形态观察空值，而不是断言 Spring 注入了 null。`optionalAsOptional(...)` 则始终注册一个 `Optional<T>` wrapper。两者都会随 capability 出现和消失重建 context：

```java
assertFalse(context.containsBean("valueProvider"));
assertTrue(snapshot.wrapped().isEmpty());
```

### Refresh 与 customizer 失败

- refresh 中途失败时，Spring 已创建的 disposable bean 被销毁，Knotra 输出不可见，组件 `FAILED`。
- customizer 手动注册并实例化 singleton 后抛错时，该 singleton 被销毁。
- inactive context 上 customizer 失败会先执行 custom closer，再物理关闭 singleton；custom closer 的 TCCL 是 annotated classes 的 loader。

### Closer retry

`SpringContextCloser` 成功前不执行物理 context close：

1. 第一次 closer 返回 failed stage，组件 `FAILED`，内部 Spring destroy 未执行。
2. retry 后 closer 成功。
3. 此时物理关闭 context，内部 disposable destroy 执行。
4. 两次 close 使用正确 TCCL。

### ClassLoader

默认使用 annotated classes 的共同 ClassLoader：

- start、customizer、Spring bean 创建和 cleanup 时 TCCL 都是该 loader。
- 与调用线程原 TCCL 不同时，调用结束后恢复原 TCCL。
- annotated classes 来自多个 loader 时，builder 在构建期以 `IllegalArgumentException` 拒绝。

### Spring dynamic dependency

`SpringModules.dynamic(...)` 注入 stable proxy：

- provider v1 到 v2 替换后 Spring child context 不重建。
- 输出对象保持同一实例。
- 后续调用返回 v2。
- context count 保持不变。

## Spring Dynamic Bridge

宿主 Spring singleton 使用 `SpringDynamicBridge` 获得稳定接口：

```java
SpringDynamicBridge<Api> bridge = SpringDynamicBridge.mount(
        runtime, "bridge", SOURCE, BRIDGED);
```

已覆盖：

- source 缺失时 `available()` 为 false，proxy 调用得到 `CapabilityUnavailableException`。
- source 出现后 `proxy()`、`withCurrent` 和 `withCurrentAsync` 都解析当前 provider。
- bridge close 后撤销输出，proxy 调用得到 `DynamicCapabilityClosedException`。
- proxy 异步方法持有 lease，直到返回 stage 完成。
- 旧 provider component reconfigure 的 cleanup 等待 bridge 异步调用完成。
- 调用完成后 reconfigure settle，后续 proxy 调用使用新 provider。

## EventBus

Definition 在静态层固定 mode 和 event type；测试不要在运行时拼 mode 字符串。

### 五种 mode

已覆盖：

- `SYNC`：调用线程按注册顺序执行；单个 listener 失败计入 failures，后续 listener 继续。
- `PARALLEL`：所有 accepted listener 并发执行，dispatch 等待全部完成；失败在全部结束后聚合。
- `SERIAL`：按顺序执行；返回 false 正常停止且不算 failure，listener 异常则记录 failure 并停止后续。
- `BAIL`：第一个 true claim 正常停止；listener 异常则记录 failure 并停止后续。
- `WATERFALL`：值逐 listener 转换；失败停止链、记录 failure 并保留最后成功值。

### Quiescence 与关闭

- `unsubscribe()` 立即移除未来 listener，不等待已 accepted work。
- `closeAsync()` 拒绝新 subscription 和全部 dispatch mode，等待 accepted dispatch 收敛。
- 被跳过的 Serial/Bail/Waterfall listener 释放 accepted lease。
- listener 回调内可以 unsubscribe 或触发 bus close，不产生锁死。
- subscription 和 bus 的 `closeAsync()` 幂等并复用同一个 in-flight future。
- `whenIdle()` 在 accepted dispatch 全部 settle 前不完成。

### 类型身份与 TCCL

- event binding 使用 exact JVM Class，而不是 class name。
- 同名不同 exact Class 在 canonical binding 占用时，subscribe 和 dispatch 都拒绝。
- binding 空闲、listener 失败、被跳过或并发注册/分发 quiescence 后，可由新 ClassLoader 重新绑定。
- listener 回调执行期间 TCCL 是 listener ClassLoader，结束后恢复调用线程原 TCCL。
- Snapshot 按 event name、mode 和 sequence 稳定排序，不包含 listener 对象。
- 恶意异常的 `getMessage()` 或 `toString()` 抛错时，failure message 仍有稳定上界。

### Runtime 生命周期

- EventBus capability registration 由 Activation 拥有。
- consumer subscription 用 `manageAsync` 登记后随组件 dispose 关闭。
- provider dispose 或替换时，consumer 先 detach，旧 bus 后关闭。
- gated listener 阻塞 provider dispose 和 replacement activation，gate 放行后收敛。
- Runtime close 会关闭托管 bus。

## Loader

Loader 测试以 desired tree 与实际 Snapshot 的差分为中心。

### 基本夹具

```java
FactoryRef ref = FactoryRef.of("tool");

KnotraLoader loader = KnotraLoader.over(
        runtime,
        runtime.root(),
        ClasspathFactoryResolver.builder()
                .add(ref, toolFactory, rawToolDecoder)
                .build());

ReconcileResult result = loader.reconcile(ComponentTree.of(
        ComponentEntry.configured("tool", ref, rawConfig)));
```

已覆盖：

- 全部 decoder 在结构变化前执行；decoder 失败产生 `CONFIG_INVALID`，不挂载新增项。
- normalized config 不变时复用 handle，revision 不变；变化时同 handle reconfigure。
- WAITING 声明保留最新 config，依赖出现后一次使用最新值启动。
- path 规范化后等价路径复用 handle，重复规范路径在变更前拒绝。
- 同一 factory 可挂多个 path，各自有独立 handle 和 context。
- 嵌套 path 每项一个 context，mount id 是规范 path。
- 孤儿 path、外部占用 path 和 disposed base 分别以 structured diagnostic 拒绝。

### 失败与替换

- activation 失败不会由后续 reconcile 自动重试，必须显式 `loader.retry(path)`。
- context teardown 失败先阻塞替换，下一次 reconcile 只推进失败清理。
- FactoryIdentity 改变时同 mount id 替换 handle。
- 旧实现 cleanup 未完成时，新实现不启动。
- 新实现 mount 被外部拒绝时，Loader 补偿恢复旧定义。
- 补偿也失败时保留 replacement 和 fallback 两组诊断，并移除不可信 entry。
- resolution、decoder 或冲突预检失败发生在结构变更前，不产生本批 handle/context。
- controlled mount 或 mount 事务在执行阶段被拒绝时，已为同批创建的 handle 和 Context 全部补偿销毁；mount 已提交但 activation 失败的 entry 则保留为 `FAILED`，等待显式 retry。

### 异步边界

- `reconcileAsync` 和 `closeAsync` 不阻塞调用线程，即使 Core start 被 gate 阻塞。
- close 后新 reconcile 快速失败为 `CLOSED`。
- 重复 `closeAsync` 返回同一个 in-flight future。
- 协调器线程上的 reentrant reconcile 被拒绝。
- controlled mount context 单次使用，不能把另一个 slot 的 handle 冒充为本 slot 结果。
- Runtime close 与 base disposal 竞争时，Loader close 不把 base 已销毁误报为自身失败。

## PF4J Artifact

PF4J 测试使用真实 fixture jar 和独立插件 ClassLoader。

### 加载与目录

已覆盖：

- artifact load/start 只发布 factory catalog，不隐式挂载组件。
- catalog metadata 只含稳定文本，不暴露 throwable 或插件私有类型。
- typed resolve 返回 factory handle；metadata 查询和 handle 解析是不同层次。
- config token mismatch、raw wrong type 和 null config 在 create 前拒绝，不产生 ownership。
- 插件私有 config token、descriptor contract、provide contract 和 child contract 按边界拒绝。
- required dependency 缺失时加载前失败；optional dependency 缺失不阻塞目标。
- dependent 加载会加载 required closure，已加载 dependency 被复用。
- repository 或 direct artifact 之间存在相同 plugin id 的多个版本时，在加载前报告 ambiguous entry。
- partial start failure 回滚已加载 dependencies 和 factory catalog，并释放 ClassLoader。

### Mount、reconfigure 与 ownership

- 一个 factory 挂多个 context 产生多个稳定 handle，并全部计入 artifact ownership。
- decoder 将 raw config 转为 exact config type，Core normalizer 再支持 mount/reconfigure。
- invalid reconfigure 保持当前 Activation、输出和 revision。
- artifact child 继承 artifact origin，并随 artifact drain 一起销毁。
- artifact unload 后 stale factory handle 不能再 mount 或 decode。

### Drain 与重试

- in-flight mount 遇到 drain 时进入 `DRAINING`，catalog 先移除；mount 随后可被拒绝，但已接受或 lost mount 的 ownership 不丢。
- async component cleanup 阻塞 drain，release 后进入 `UNLOADED`。
- cleanup 失败进入 `DRAIN_FAILED`，component 保持 `FAILED` 且 goal 为 disposed；`retryDrainAsync` 只推进失败清理。
- 依赖 unload 先 drain dependent，再 drain dependency。
- 并发 dependent/dependency drain 汇合成同一个 closure。
- artifact 外部创建但标记为该 artifact root 的 active snapshot 会阻塞 unload 并保留 ClassLoader；该 root dispose 后 retry drain 才释放。
- adapter 并发 close 幂等并 drain 全部 ownership；失败后下一次 close 继续重试。

### ClassLoader

成功 unload、partial load rollback 和 adapter close 后，插件 ClassLoader 弱引用归零。测试中的 host 静态 coordinator/vault 必须清空，否则会制造假泄漏。

## Loader 与 PF4J 桥接

官方 `Pf4jFactoryResolver` 的跨模块测试覆盖：

- artifact catalog 可解析为 Loader `ResolvedFactory`，宿主无需提供 factoryId 到 class 的映射。
- nested tree 正常挂载，entry 记录 factory id、version 和非空 fingerprint，artifact child 与 Loader entries 均计入 ownership。
- raw config 经 artifact decoder 和 Core normalizer 后进入组件。
- `FactoryRef.version` 与 artifact version 不匹配时，在创建结构前以 `RESOLUTION_FAILED` 拒绝。
- Core `INVALID_CONFIG` 穿过 artifact factory 和 bridge 后映射为 Loader `CONFIG_INVALID`。
- reconcile 与 artifact drain 竞争时不留下 partial mount；fallback resolver 可在 artifact 卸载后恢复本地实现。
- Loader 显式 retry 可修复首次 start 失败。

## 跨模块集成

### 并发 close

同时调用 adapter close、Loader close 和 Runtime close，或按 Runtime -> Loader -> adapter 反向关闭，都必须：

- 全部 future 有界完成。
- 重复调用幂等。
- artifact 最终 `UNLOADED`。
- Runtime Snapshot 中组件清零。
- 失败 cleanup 修复后下一次 close 完成收敛。

### Events 与插件

- gated host listener 阻塞 provider replacement，旧 bus 关闭后 consumer 才使用新 bus。
- gated plugin listener 阻塞 artifact drain；期间 canonical event binding 未释放，同名不同 ClassLoader 的 shadow event 被拒绝。
- listener 完成并 artifact unload 后，binding 释放，shadow ClassLoader 的同名 event 可无 listener 分发。
- 开放宿主 bus 不因已移除插件 listener 保留插件 ClassLoader，同一 event name 可 reload 并重新订阅。
- event identity 是 exact JVM Class，不是 binary name。

### Snapshot 与 GC

保留 Runtime、Artifact、EventBus 和 Loader Snapshot，然后 unload artifact 并关闭 bus provider：

- Snapshot 仍可用于稳定观测。
- 它们不持有插件业务对象或 ClassLoader 强引用。
- 释放宿主静态 coordinator 引用并触发 GC 后，插件 loader 弱引用归零。

## 覆盖矩阵

| 领域 | 主要源码 | 已验证重点 |
|---|---|---|
| Beans 简单路径 | `knotra-beans/src/test/.../BeansTest.java` | fresh-per-Activation、required/optional、typed config、AUTO/custom/unmanaged 生命周期、多输出、expert、dynamic |
| Core 依赖与 Activation | `ActivationTransactionTest.java` | required/optional、registration identity、stale、retry、reconfigure、owned child staging |
| Core 生命周期 | `LifecycleAndDependencyTest.java` | LIFO/parallel、异步清理、失败重试、dependent 先于 provider、并发 dispose |
| Core 结构事务 | `StructuralMutationTest.java` | config、slot/type conflict、原子回滚、provisional intent、shadow、Snapshot 稳定 |
| Core 并发与 Snapshot | `ConcurrencySnapshotGcTest.java` | 并发 mount、generation、STARTING shadow、cycle、Runtime close retry、DTO、ClassLoader GC |
| Dynamic Capability | `DynamicCapabilityTest.java` | required/optional、closed/unavailable、lease、proxy、shadow、cycle、consumer/provider/runtime close |
| Processor | `KnotraBeanProcessorTest.java` | JavaCompiler 正向生成、真实 mount/reconfigure、稳定负向诊断、生成名冲突 |
| Spring child context | `SpringModuleTest.java` | required/optional/config、外部所有权、refresh/customizer rollback、multi-output、closer retry、TCCL |
| Spring dynamic | `SpringDynamicDependencyTest.java`、`SpringDynamicBridgeTest.java` | context 不重建、宿主 bridge、异步 lease、provider cleanup 等待 |
| EventBus mode | `EventBusTest.java` | 五种 mode、失败聚合、注册顺序、关闭、Snapshot |
| EventBus quiescence | `EventBusQuiescenceTest.java` | accepted lease、exact Class、rebind、TCCL、恶意异常、GC |
| EventBus 生命周期 | `EventBusLifecycleTest.java` | consumer 先关闭、provider replacement 等待 gated teardown |
| Loader reconcile | `LifecycleReconcileTest.java` | config identity、waiting、失败重试、实现替换、补偿、并发协调 |
| Loader structure | `LoaderStructureTest.java` | path 规范化、嵌套 context、移除、resolver、模块依赖边界 |
| Loader async boundary | `LoaderAsyncBoundaryTest.java` | controlled mount、close future 复用、reentrance、base lifecycle |
| PF4J artifact | `Pf4jArtifactAdapterTest.java` | catalog、dependency、drain、ownership、retry、ClassLoader、close |
| PF4J/Loader bridge | `LoaderPf4jBridgeIntegrationTest.java` | version/fingerprint、诊断映射、drain race、fallback |
| 跨模块 close | `CrossModuleCloseIntegrationTest.java` | adapter/Loader/Runtime 正反向并发关闭、失败重试 |
| Events 集成 | `EventBusIntegrationTest.java` | gated listener、插件 drain、exact Class、open bus reload |
| Snapshot GC | `SnapshotClassLoaderIntegrationTest.java` | 四类 Snapshot 不钉住插件 ClassLoader |

当前全量测试为 **308 项，0 failures、0 errors、0 skipped**：

| 模块 | 测试数 |
|---|---:|
| knotra-core | 113 |
| knotra-beans | 24 |
| knotra-beans-processor | 25 |
| knotra-events | 44 |
| knotra-spring | 14 |
| knotra-pf4j | 37 |
| knotra-loader | 36 |
| knotra-integration-tests | 15 |
| **总计** | **308** |
