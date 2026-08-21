# Knotra 实现与验收报告

**报告日期**：2026-08-21  
**项目版本**：`io.knotra:knotra-parent:0.1.0-SNAPSHOT`  
**源码位置**：仓库根目录  
**当前状态**：六模块 Maven reactor 已交付并通过完整验证；所有项目文件尚未提交 Git。

本报告基于当前仓库 POM、README、Surefire XML、Git 状态和残留扫描生成，不含过程流水账。

## 交付范围

Knotra 是独立的 JVM 动态组合运行时，不兼容 Cordis API，也不是 Cordis 的复刻实现。项目采用 Java 21 和 Maven 多模块结构，坐标统一为 `io.knotra`。

| 模块 | 交付内容 | 测试 |
|---|---|---:|
| `knotra-core` | Context tree、typed capability、稳定 ComponentHandle、原子 Activation、BindingSet、LifecycleScope、snapshot、diagnostics、SCC/收敛防护 | 95 |
| `knotra-events` | 独立 EventBus，支持 serial/parallel/bail/waterfall dispatch、订阅与总线 quiescence、跨 artifact 的精确 Class identity | 44 |
| `knotra-pf4j-spi` | artifact 实现的共享 provider SPI；PF4J 为 provided scope | - |
| `knotra-pf4j` | PF4J artifact 加载、依赖闭包、typed controlled mount、只读 catalog、ownership drain、失败重试、ClassLoader contract guard | 37 |
| `knotra-loader` | 声明式 desired tree、稳定 path identity、nested context、配置更新、factory replacement、异步 coordinator、rollback/compensation | 36 |
| `knotra-integration-tests` | 仅测试模块，使用真实 PF4J fixture 做跨模块验收，不发布 | 14 |
| **合计** | | **226** |

集成模块在 POM 中设置 `maven.install.skip=true` 与 `maven.deploy.skip=true`，只作为测试装配，不会作为可依赖 artifact 发布。

## 关键实现不变量

### Core 结构事务与可见性

- 宿主显式结构修改经由 `KnotraRuntime.mutate(...)` 的短事务完成；`RuntimeContext` 是只读接口。`ContextHandle.dispose()` 与 `runtime.closeAsync()` 是公开 lifecycle request，内部同样 draft/publish generation。
- 结构事务先形成 draft，再一次性发布到带 generation 的 immutable runtime view；事务被拒绝时不发布任何部分结构。
- mutation committed 返回时新 view 已发布；`settlement()` 只等待本次 mutation 直接触发的 component/context transitions，不表示全局 quiescence，也不覆盖后续独立 transition。特定 Handle 使用 `whenSettled()`。
- Context 是真实树结构。capability 沿 ancestor chain 解析，child registration 可 shadow parent；child registration 撤销后重新回退到 parent provider。
- 同名 capability 的 Java contract type 在同一 runtime 生命周期内固定，类型冲突会被拒绝。
- Component 逻辑身份是 `(contextId, mountId)`；`ComponentHandle` 稳定存在，同一 handle 的每次运行产生新的 Activation。

### Activation 与 Binding

- REQUIRED 与 OPTIONAL requirement 都进入固定 BindingSet；optional provider 出现或消失同样触发 reactivation。
- Binding 基于 registration identity，而不是 capability value 的 `equals()`；同值的新 registration 也构成新 generation。
- Resolve 阶段发布 STARTING reservation，使用户启动期间的结构变化可被发现。
- 用户 `start()` 在 coordinator 锁外执行；返回后重新校验 binding、config、context、goal 和 base generation。
- candidate 过期时 rollback 本次 LifecycleScope，并按最新 generation 重新 reconcile；不把正常竞态记为业务 FAILED。
- `ActivationContext.provide()` 与 `mountChild()` 均 staged；只有 Validate 成功后，ACTIVE 状态、BindingSet、registration 和 child handle 随同一 activation commit 原子发布。
- 启动或验证失败只撤销 staged 表面；成功前的能力对并发读者不可见。
- provider 替换先计算 dependent closure 并完成 logical detach，之后才执行 provider physical cleanup。

### Lifecycle 与恢复

- LifecycleScope 组成 LIFO 树；默认确定性逆序 teardown，只有显式 `parallelChild()` 才并行。
- cleanup 异步执行并聚合错误；失败按 managed entry 保留，重复 close/retry 只处理未收敛项。
- 组件 goal 区分 RUNNING 与 DISPOSED；失败 cleanup 不被伪装成成功，后续可通过 `ComponentHandle.retry()` 重试。
- Context 递归 disposal 与 runtime close 同样可重试、幂等，并等待已接受的异步清理收敛。

### Event 与 Artifact 边界

- EventBus 是普通 capability，不是 kernel 特权模块。
- subscription 和 bus 的 `closeAsync()` 等待已经 accepted 的 dispatch 结束，close 后拒绝新工作；close 先封闭入口并清空 registry，但 accepted dispatch 对象持有原 binding 到回调完成，活跃订阅与 accepted 计数均归零后 binding 才可移除。
- event identity 使用精确 JVM `Class`，不是 type name，避免不同 artifact 的同名类型混淆。
- dispatch 恢复调用方 Thread Context ClassLoader，避免 worker 线程钉住 plugin ClassLoader。
- PF4J artifact 加载只发现 factory export，不自动挂载组件。
- typed resolve 绑定 host/shared config token；factory 缺失返回 `Optional.empty()`，token 不匹配 fail fast 抛 `IllegalArgumentException`，Loader 记录 `RESOLUTION_FAILED`。tokenless catalog 只暴露 immutable 元数据，不能 mount、normalize configuration 或还原成 typed handle。
- artifact typed mount 拒绝 `null` config，并要求正确 config 类型；调用方可传 typed raw config，由 Core factory schema 归一化，无配置 factory 必须显式传 `NoConfig.INSTANCE`。
- adapter 拥有经其 handle 创建的全部 mount；unload 先 drain in-flight mount 和 owned handle，dependent leaf-first dispose，然后才 stop/unload PF4J plugin 并释放 ClassLoader。
- artifact stop/unload 或 cleanup 失败进入可诊断的失败状态，可重复 close 或 `retryDrain(...)`，不会虚构成功 unload。
- 只有 shared contract package 中的类型可跨 artifact 边界；plugin-private contract type 在 activation 阶段被拒绝。

### Loader 声明式收敛

- 声明路径归一化后作为稳定 identity；等价路径复用 handle，重复路径和 orphan child 在 mutation 前拒绝。
- resolver 返回 opaque `ResolvedComponentDefinition`，包含必填 factory id 与 implementation fingerprint、可选 version 组成的 factory identity、schema、controlled mount 和 reconfigure strategy，不暴露 raw artifact factory 或 PF4J manager。
- `ControlledMountContext` 只提供本次分配的 context、mountId 和一次 typed mount 操作，不能访问 `KnotraRuntime`、`RuntimeMutation` 或任意 host capability publication。
- 批量新增先创建/提交 context，再逐项 mount；任一步失败按反序补偿已创建资源。
- factory identity 变化触发 settled replacement：先等待旧 Handle 到 `DISPOSED`/settlement，再在同一 slot mount 新实现；替换失败时尝试恢复旧实现，恢复失败进入 `COMPENSATION_FAILED` 诊断而不是保留死声明。
- 配置更新走定义声明的 reconfigure strategy；FAILED 声明需要显式 `retry(path)`，避免失败实现被静默重启。
- loader coordinator 串行处理 reconcile/retry/close，拒绝 coordinator 线程重入；`closeAsync()` 是真实异步 future，失败后可再次调用完成剩余清理。
- Runtime close 已接管 Loader base subtree 时，Loader close 只清理自身托管簿记并停止 coordinator，不重复 teardown，也不等待或代替 Runtime close future；Core 收敛由调用方等待 `runtime.closeAsync()`。

## 真实验证

### 构建与环境

在仓库根目录执行：

```bash
mvn clean verify -q
```

命令在 60 秒限制内成功完成。

环境：

- JDK：Amazon Corretto / Amazon.com Inc. `21.0.11+10-LTS`，输出 `openjdk version "21.0.11" 2026-04-21 LTS`。
- Maven：`3.9.15`。
- 构建无需预先 `mvn install`，reactor 按依赖顺序编译。

### Surefire 结果

从当前 `target/surefire-reports/TEST-*.xml` 汇总：

| 模块 | Tests | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|
| `knotra-core` | 95 | 0 | 0 | 0 |
| `knotra-events` | 44 | 0 | 0 | 0 |
| `knotra-pf4j` | 37 | 0 | 0 | 0 |
| `knotra-loader` | 36 | 0 | 0 | 0 |
| `knotra-integration-tests` | 14 | 0 | 0 | 0 |
| **合计** | **226** | **0** | **0** | **0** |

Core 覆盖结构事务、activation 竞态、owned child、依赖刷新、LifecycleScope、snapshot、并发与 ClassLoader 释放；测试亦调用系统 `javac` 编译并加载真实 `ComponentFactory`。

### README 示例验证

README 中五组 Java 用法已独立整理成临时编译单元，并用当前模块 `target/classes` 与 PF4J/ASM 依赖执行：

```bash
javac -Xlint:all ...
```

覆盖：

host transaction、event consumer、PF4J artifact、Loader，以及 PF4J→Loader bridge 的 schema、fingerprint、`ControlledMountStrategy` 与 typed handle 包装。
编译通过。唯一输出为 `try (KnotraLoader loader = ...)` 的预期警告：`close()` 声明 `throws InterruptedException`，调用方需处理该异常；这不是 API 推断错误。

### 跨模块集成场景

`knotra-integration-tests` 构建真实 PF4J fixture JAR，并覆盖：

- artifact load/start 只发现 factory，不产生隐式 mount 或 registration；controlled mount 会归一化配置、保留 provenance，child 继承 origin 并随 artifact drain。
- 未 settle 的 plugin listener 会阻塞 provider replacement 与 artifact drain，且不重复投递；长期 host bus 可释放 plugin ClassLoader，同 event name 也可重新加载。
- event identity 在跨 ClassLoader 场景下仍要求精确 JVM `Class`。
- Loader 通过 opaque controlled strategy 桥接 PF4J factory，覆盖 nested tree、schema normalization、factory identity、ownership 和 replacement。
- reconcile 与 artifact drain 并发时避免 partial state，并在下一次 reconcile 收敛。
- Loader 对失败 start 的显式 retry 可收敛。
- runtime、adapter、loader 的正序/逆序并发 close 收敛且幂等；artifact cleanup 失败可在后续 close 重试。
- retained `RuntimeSnapshot` 与 `ArtifactSnapshot` 不钉住 plugin ClassLoader。

## 依赖与源码边界

- `knotra-core`：无运行时依赖，测试仅 JUnit Jupiter；Enforcer 禁止 PF4J、ASM、Events、PF4J SPI/adapter 和 Loader。
- `knotra-events`：仅依赖 `knotra-core`；Enforcer 禁止 PF4J、ASM 和 PF4J/Loader 模块。
- `knotra-pf4j-spi`：依赖 Core，PF4J `3.13.0` 为 `provided`。
- `knotra-pf4j`：依赖 Core、SPI、PF4J `3.13.0`、ASM `9.7`。
- `knotra-loader`：仅依赖 Core；Enforcer 禁止 PF4J、ASM 和 PF4J 模块。
- `knotra-integration-tests`：所有产品模块均为 test scope，仅本模块测试使用。
- Events、Loader、PF4J 生产源码未直接引用 `io.knotra.internal`；跨模块只通过公开 API 交互。

残留扫描结果：

- 未发现 `.orig`、`.rej`、`~`、`.bak`、`.patch` 残留文件。
- 未发现生产或测试源码中的 `Thread.sleep()`。
- 未发现源码/POM 中的 `io.cordis` 或旧 `cordis-*` 模块引用。
- README 仅在 “Design Influences” 中保留一次 Cordis 设计来源 attribution。
- 旧 `cordis-*` 目录已从当前仓库删除。

## 已知限制

- Loader 不监听文件系统；应用需在 desired state 或 artifact 集合变化后显式调用 `reconcile(...)`。
- PF4J plugin 的 `stop()` 或 `unload()` 失败后，adapter 保留失败诊断和可重试状态；若底层资源始终无法释放，最终恢复手段是重启 JVM。
- ClassLoader 回收测试使用 weak reference 和显式 GC 验证 runtime 不再持有引用；生产环境仍要求外部代码不保留 plugin 对象、Class 或 ClassLoader。
- 配置格式由 factory schema 定义，Core 不规定配置文件格式，也不默认把 `null` 解释为 no-config。
- 长时间宿主进程中的 GC 时机不可由 runtime 强制控制；runtime 的责任是释放可释放引用并让泄漏可通过 snapshot/diagnostic 定位。

## 恢复与操作建议

- 激活失败且 rollback 完成：对目标 `ComponentHandle` 调用 `retry()`。
- Loader 中某路径保持 FAILED：调用 `KnotraLoader.retry(path)`；配置已在 desired state 中保存时，恢复成功后使用新 activation。
- artifact 处于 drain/unload 失败状态：先修复外部资源，再调用 `retryDrain(artifactId)` 或重复 adapter close。
- Context 或 runtime close 部分失败：重复 close 会继续处理剩余 cleanup；操作幂等，不会重放已成功释放的资源。Runtime 接管 Loader base subtree 时，Loader close 完成不代表 Runtime close 已完成；需要另行等待 `runtime.closeAsync()`。
- 构建验证：仓库根目录执行 `mvn clean verify`，当前为 226 项测试。
- 若需要干净重跑，先让 Maven `clean` 移除 `target/`，不要手工修改源码之外的生成物。

## Git 状态

当前 `git status --short` 显示 README、根 POM、六个 Knotra 模块和 `docs/` 下四份 Knotra 文档均为未跟踪新文件；`target/` 由 `.gitignore` 排除。源码、POM、README 与文档尚未形成 Git commit，发布或迁移前应先审查并提交当前基线。
