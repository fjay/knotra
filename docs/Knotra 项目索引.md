# Knotra 项目索引

**状态**：`0.1.0-SNAPSHOT`，六模块 Maven reactor 已完成并全部通过验证；工作区尚未提交 Git。

Knotra 是面向 JVM 动态组合应用的独立运行时，核心 primitives 包括 typed capability、层级 Context、稳定 ComponentHandle、原子 Activation、可逆 LifecycleScope，以及受控 artifact 边界。项目不兼容 Cordis API，也不实现 Cordis 框架；Cordis 仅是设计来源之一。

**源码**：仓库根目录

**模块**

| 模块 | 职责 |
|---|---|
| `knotra-core` | Runtime kernel：Context tree、capability binding、component/activation/lifecycle 状态机、snapshot 与 diagnostics |
| `knotra-events` | 独立 typed EventBus，作为普通 capability 挂载 |
| `knotra-pf4j-spi` | artifact 实现的共享 provider SPI；PF4J 为 provided scope |
| `knotra-pf4j` | PF4J artifact adapter：加载、typed controlled mount、ownership drain、ClassLoader guard |
| `knotra-loader` | 声明式 desired tree reconciliation 与异步 coordinator |
| `knotra-integration-tests` | 仅测试的跨模块真实 fixture 验证，不发布 |

**关键约束**

- Java 21+，Maven 3.9+。
- Core 无运行时依赖；Events 与 Loader 只依赖 Core，不允许引入 PF4J/ASM。
- PF4J 只存在于 artifact 边界；SPI 使用 provided scope，adapter 才依赖 PF4J 与 ASM。
- 结构修改必须通过 host structural transaction；`RuntimeContext` 保持只读。
- Capability 以 registration identity 触发 binding generation，不用 value equality 判断替换。
- Activation 的 staged registration 只有在验证成功后才能原子发布。
- 旧 Activation 完成 teardown 前不得创建下一 Activation。
- LifecycleScope cleanup 失败按 entry 保留并支持重试，不伪装成功。
- Snapshot 只包含稳定 DTO，不持有 component、disposer、Throwable、Class 或 ClassLoader 引用。

**文档**

- [README](../README.md)
- [Knotra 运行时设计文档](Knotra%20运行时设计文档.md)
- [Knotra API 与集成指南](Knotra%20API%20%E4%B8%8E%E9%9B%86%E6%88%90%E6%8C%87%E5%8D%97.md)
- [Knotra 实现与验收报告](Knotra%20%E5%AE%9E%E7%8E%B0%E4%B8%8E%E9%AA%8C%E6%94%B6%E6%8A%A5%E5%91%8A.md)
- [历史设计：Cordis Java 运行时设计文档](https://note.fjay.top/v/note-nest/note/Cordis%20Java%20%E8%BF%90%E8%A1%8C%E6%97%B6%E8%AE%BE%E8%AE%A1%E6%96%87%E6%A1%A3.md)

最后同步日期：2026-08-21
