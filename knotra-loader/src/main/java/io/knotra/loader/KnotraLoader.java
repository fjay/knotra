package io.knotra.loader;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import io.knotra.ComponentGoal;
import io.knotra.MountHandle;
import io.knotra.ComponentState;
import io.knotra.ContextHandle;
import io.knotra.ContextState;
import io.knotra.DiagnosticCode;
import io.knotra.KnotraRuntime;
import io.knotra.TransactionReceipt;
import io.knotra.TransactionRejectedException;
import io.knotra.RuntimeDiagnostic;
import io.knotra.RuntimeSnapshot;
import io.knotra.SettlementReport;

/**
 * 基于 Core 的声明式期望状态收敛器。
 *
 * <p>Loader 把每个期望条目映射到一个稳定路径，并为其分配专属的子 Context 与
 * 挂载 ID。reconcile 时，Loader 将期望树与运行时当前状态对比，按“完全准备
 * 校验 → 同步外部释放的记账 → 处理失败的期望 Context → 释放多余条目 →
 * 替换实现变化的条目 → 补挂缺失条目 → 重配置既有条目”的顺序收敛。准备阶段
 * 的失败不会触碰现有树；新增批次失败会回滚本批已挂载的句柄与新 Context，
 * 而不是留下部分挂载。
 *
 * <p>所有结构变更都通过宿主短事务提交给 Runtime，Loader 自身只保存“路径 →
 * Context/句柄”的记账信息。两种挂载范围：owned 模式在根 Context 下创建
 * 专属基础 Context，关闭时整体释放该子树；over 模式挂接宿主提供的 Context，
 * 只管理自己创建的子结构，绝不认领路径上已有的外来 Context 或挂载点。
 *
 * <p>操作在单线程协调器上串行执行，保证并发 reconcile 看到一致的树状态；
 * 协调器线程内的重入调用会被拒绝，避免受控策略回调造成自等待死锁。
 * FAILED 的挂载不会被自动重试，需通过 {@link #retry(String)} 显式恢复。
 */
public final class KnotraLoader implements AutoCloseable {

    private static final AtomicLong NEXT_ID = new AtomicLong();

    private final String loaderId;
    private final boolean owned;
    private final KnotraRuntime runtime;
    private final ContextHandle baseContext;
    private final ComponentFactoryResolver resolver;
    private final ExecutorService coordinator;
    private final AtomicReference<Thread> coordinatorThread = new AtomicReference<>();
    private final Object closeGate = new Object();

    /** 稳定路径 → 受管条目（句柄、定义、配置）记账，仅在协调器线程上变更。 */
    private final TreeMap<String, ManagedEntry> current = new TreeMap<>();
    /** 稳定路径 → Loader 创建的 Context 记账，仅在协调器线程上变更。 */
    private final TreeMap<String, ContextHandle> contexts = new TreeMap<>();
    /** 发布给 snapshot() 的不可变视图。 */
    private volatile LoaderView view = LoaderView.EMPTY;
    private volatile List<LoaderDiagnostic> latestDiagnostics = List.of();
    private volatile boolean closed;
    private CompletableFuture<Void> closeAttempt;
    /** 受控挂载 commit 后的 settlement 等待；包内可注入以做确定性测试。 */
    private volatile Duration mountSettlementTimeout = AllocatedMountContext.DEFAULT_SETTLEMENT_TIMEOUT;
    /** settlement 未收敛时释放已提交挂载的有界等待。 */
    private volatile Duration mountRecoveryTimeout = AllocatedMountContext.DEFAULT_RECOVERY_TIMEOUT;
    private KnotraLoader(
            KnotraRuntime runtime,
            ContextHandle baseContext,
            ComponentFactoryResolver resolver,
            boolean owned) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.loaderId = "loader-" + NEXT_ID.incrementAndGet();
        this.owned = owned;
        if (owned) {
            TransactionReceipt<ContextHandle> receipt;
            try {
                receipt = runtime.advanced().transact(transaction ->
                        transaction.childContext(runtime.root(), loaderId));
            } catch (TransactionRejectedException rejection) {
                throw new IllegalArgumentException(
                        "owned base context creation was rejected", rejection);
            }
            this.baseContext = receipt.value();
        } else {
            this.baseContext = Objects.requireNonNull(baseContext, "baseContext");
        }
        this.coordinator = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, loaderId + "-coordinator");
            thread.setDaemon(true);
            return thread;
        });
        publish(List.of());
    }

    /**
     * 创建 owned 模式的 Loader：在根 Context 下创建以 loaderId 命名的专属
     * 基础 Context。
     *
     * <p>该基础 Context 及其全部子结构都由 Loader 拥有；close 时整体释放，
     * 不影响根 Context 本身与其他宿主结构。
     */
    public static KnotraLoader owned(
            KnotraRuntime runtime,
            ComponentFactoryResolver resolver) {
        return new KnotraLoader(runtime, null, resolver, true);
    }

    /**
     * 创建 over 模式的 Loader：挂接到宿主提供的 baseContext。
     *
     * <p>Loader 只在该 Context 下创建并管理自己的子结构；close 时仅释放自己
     * 创建的顶层子树，baseContext 保持不变。期望路径上已存在的外来 Context
     * 或挂载会被判定为冲突并拒绝，不会隐式认领。
     */
    public static KnotraLoader over(
            KnotraRuntime runtime,
            ContextHandle context,
            ComponentFactoryResolver resolver) {
        return new KnotraLoader(runtime, context, resolver, false);
    }

    /** Loader 实例 ID；owned 模式下同时用作专属基础 Context 的名称。 */
    public String loaderId() {
        return loaderId;
    }

    /** 是否为 owned 模式（拥有专属基础 Context）。 */
    public boolean owned() {
        return owned;
    }

    /** 当前基础 Context：owned 模式为 Loader 专属 Context，over 模式为宿主提供。 */
    public ContextHandle baseContext() {
        return baseContext;
    }

    /**
     * 包内测试注入点：缩短受控挂载 commit 后的 settlement 与释放等待，
     * 使 commit 后超时路径可以快速、确定性地覆盖，无需等待真实 30 秒。
     */
    void mountTimeoutsForTesting(Duration settlementTimeout, Duration recoveryTimeout) {
        this.mountSettlementTimeout = positiveDuration(settlementTimeout, "settlementTimeout");
        this.mountRecoveryTimeout = positiveDuration(recoveryTimeout, "recoveryTimeout");
    }

    private static Duration positiveDuration(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    /**
     * 异步收敛期望树。
     *
     * <p>流程分两个层次：先把期望树完全准备并校验（路径归一化、父完整性、
     * 与运行时状态的冲突预检、工厂解析、配置归一化），任何准备失败都不会
     * 触碰现有树；随后按“先清理后建设”的顺序执行结构事务：释放期望树中
     * 已不存在的条目、整句柄替换实现身份变化的条目、挂载缺失条目，并对
     * 身份不变的条目应用配置重配置。
     *
     * <p>契约：操作在协调器上串行执行；收敛拒绝以结构化诊断返回而不是抛出，
     * 协调器内部错误与协调器线程上的重入调用仍以异常抛出；新增批次失败时
     * 本批已挂载的句柄与新 Context 按 LIFO 回滚；FAILED 挂载不做自动重试。
     * Loader 已关闭时立即完成并携带 CLOSED 诊断。
     *
     * @param desired 完整的期望组件树；每次调用都是全量状态而非增量
     * @return 收敛结果（converged、变更列表、诊断列表）
     */
    public CompletionStage<ReconcileResult> reconcileAsync(ComponentTree desired) {
        Objects.requireNonNull(desired, "desired");
        if (closed) {
            return CompletableFuture.completedFuture(finish(
                    false,
                    List.of(),
                    List.of(LoaderDiagnostic.of(
                            LoaderDiagnosticCode.CLOSED,
                            "",
                            "loader is closed"))));
        }
        return enqueue(() -> performReconcile(desired));
    }

    /**
     * 阻塞执行 {@link #reconcileAsync(ComponentTree)}。
     *
     * <p>协调器上的运行时异常原样抛出，其他异常包装为 IllegalStateException；
     * 等待被中断时恢复中断标记并抛出 IllegalStateException。
     */
    public ReconcileResult reconcile(ComponentTree desired) {
        try {
            return reconcileAsync(desired).toCompletableFuture().get();
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof RuntimeException runtimeError) {
                throw runtimeError;
            }
            throw new IllegalStateException(cause == null ? error.getMessage() : cause.getMessage(), cause);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("reconcile was interrupted", error);
        }
    }

    /**
     * 对指定路径执行显式恢复。
     *
     * <p>挂载处于 FAILED 时触发 MountHandle.retryAsync 重新激活；条目对应的
     * Context 处于 FAILED 时释放该子树，让下一次 reconcile 重建。两者之外的
     * 请求记为 INVALID_TREE。与 reconcile 不同，retry 不重读期望树，
     * 只作用于当前记账中该路径的状态。
     *
     * @param path 条目的归一化路径
     * @return 恢复结果；已关闭时返回 CLOSED 诊断
     */
    public CompletionStage<ReconcileResult> retryAsync(String path) {
        String normalized = normalizePath(path, "");
        if (closed) {
            return CompletableFuture.completedFuture(finish(
                    false,
                    List.of(),
                    List.of(LoaderDiagnostic.of(
                            LoaderDiagnosticCode.CLOSED,
                            normalized,
                            "loader is closed"))));
        }
        return enqueue(() -> performRetry(normalized));
    }

    /** 阻塞执行 {@link #retryAsync(String)}；异常约定与 {@link #reconcile(ComponentTree)} 一致。 */
    public ReconcileResult retry(String path) {
        try {
            return retryAsync(path).toCompletableFuture().get();
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof RuntimeException runtimeError) {
                throw runtimeError;
            }
            throw new IllegalStateException(cause == null ? error.getMessage() : cause.getMessage(), cause);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("retry was interrupted", error);
        }
    }

    /**
     * 返回 Loader 当前状态的不可变快照。
     *
     * <p>快照只包含数据：条目路径、Context 与挂载标识、实现身份、配置代际、
     * 组件状态与最近诊断，不暴露存活组件实例或内部记账结构。可在协调器
     * 之外随时调用，读取的是最近一次发布的一致视图。
     */
    public LoaderSnapshot snapshot() {
        LoaderView local = view;
        List<LoaderSnapshot.EntrySnapshot> entries = local.entries().values().stream()
                .map(entry -> new LoaderSnapshot.EntrySnapshot(
                        entry.path(),
                        entry.context().contextId(),
                        entry.context().info().canonicalPath(),
                        entry.handle().handleId(),
                        entry.handle().mountId(),
                        entry.handle().componentId(),
                        entry.definition().identity(),
                        entry.handle().configRevision(),
                        entry.handle().state(),
                        entry.handle().goal()))
                .toList();
        return new LoaderSnapshot(
                loaderId,
                owned,
                baseContext.contextId(),
                local.closed(),
                entries,
                latestDiagnostics);
    }

    /**
     * 异步关闭 Loader 并释放其管理的结构。
     *
     * <p>owned 模式整体释放基础 Context；over 模式只释放自己创建的顶层子树。
     * 清理失败不会伪造成功：close future 异常完成，closed 状态与诊断保留，
     * 可再次调用 close 重试。与运行时 close 的竞态会收敛：若运行时已在释放
     * 根 Context，Loader 只同步记账，不把该竞态报告为失败。重复调用返回
     * 同一个未完成（或已成功）的 close future。
     */
    public CompletionStage<Void> closeAsync() {
        rejectReentrant("close");
        synchronized (closeGate) {
            if (closeAttempt != null
                    && (!closeAttempt.isDone() || !closeAttempt.isCompletedExceptionally())) {
                return closeAttempt;
            }
            closed = true;
            closeAttempt = enqueue(this::performClose);
            return closeAttempt;
        }
    }

    /** 阻塞等待 {@link #closeAsync()} 完成；失败以 CompletionException 上抛，可再次调用重试。 */
    @Override
    public void close() {
        closeAsync().toCompletableFuture().join();
    }

    /**
     * 把操作排入单线程协调器。coordinatorThread 用于识别协调器线程本身，
     * 拒绝受控策略或组件代码在协调器线程内重入调用 Loader，防止自等待死锁。
     */
    private <T> CompletableFuture<T> enqueue(Callable<T> operation) {
        rejectReentrant("coordinator operation");
        CompletableFuture<T> outcome = new CompletableFuture<>();
        try {
            coordinator.execute(() -> {
                Thread thread = Thread.currentThread();
                Thread previous = coordinatorThread.getAndSet(thread);
                if (previous != null && previous != thread) {
                    outcome.completeExceptionally(new IllegalStateException(
                            "loader coordinator thread changed"));
                    return;
                }
                try {
                    outcome.complete(operation.call());
                } catch (Throwable error) {
                    outcome.completeExceptionally(error);
                }
            });
        } catch (RuntimeException error) {
            outcome.completeExceptionally(error);
        }
        return outcome;
    }

    private void rejectReentrant(String operation) {
        if (coordinatorThread.get() == Thread.currentThread()) {
            throw new IllegalStateException(
                    "reentrant " + operation + " on the loader coordinator thread is not allowed");
        }
    }

    /**
     * reconcile 主体。阶段刻意“先校验、后清理、再建设”：prepare 阶段的任何
     * 失败都不会触碰现有树；清理失败会立即中止，避免在未收敛的运行时上继续
     * 挂载；工厂替换在配置更新之前完成，后者只处理实现身份已匹配的条目。
     * 最后统一收集 FAILED 诊断，把重试决策留给显式 retry。
     */
    private ReconcileResult performReconcile(ComponentTree desired) {
        List<LoaderDiagnostic> diagnostics = new ArrayList<>();
        List<ReconcileResult.Change> changes = new ArrayList<>();
        boolean proposed = true;
        try {
            PreparedTree prepared = prepare(desired, diagnostics);
            if (!diagnostics.isEmpty()) {
                return finish(false, changes, diagnostics);
            }

            pruneExternallyDisposed();
            if (!settleFailedDesiredContexts(prepared, diagnostics)) {
                return finish(false, changes, diagnostics);
            }
            if (!removeMissing(prepared, changes, diagnostics)) {
                return finish(false, changes, diagnostics);
            }
            if (!replaceChangedFactories(prepared, changes, diagnostics)) {
                return finish(false, changes, diagnostics);
            }
            if (!addMissing(prepared, changes, diagnostics)) {
                return finish(false, changes, diagnostics);
            }
            updateConfigs(prepared, changes, diagnostics);
            addUnconvergedDiagnostics(prepared, diagnostics);
        } catch (Throwable error) {
            proposed = false;
            diagnostics.add(LoaderDiagnostic.of(
                    LoaderDiagnosticCode.STRUCTURE_REJECTED,
                    "",
                    safeError(error)));
        }
        return finish(proposed && diagnostics.isEmpty(), changes, diagnostics);
    }

    /**
     * 显式恢复单条路径：FAILED 组件走 handle.retry 重新激活；FAILED Context
     * 先整树释放，让下一次 reconcile 重建，避免在失败的 Context 上反复挂载。
     */
    private ReconcileResult performRetry(String path) {
        List<LoaderDiagnostic> diagnostics = new ArrayList<>();
        List<ReconcileResult.Change> changes = new ArrayList<>();
        ManagedEntry entry = current.get(path);
        if (entry == null) {
            diagnostics.add(LoaderDiagnostic.of(
                    LoaderDiagnosticCode.INVALID_TREE,
                    path,
                    "path is not managed or has no retryable activation"));
            return finish(false, changes, diagnostics);
        }

        if (entry.handle().state() == ComponentState.FAILED) {
            try {
                ComponentState state = entry.handle().retryAsync()
                        .toCompletableFuture().get();
                current.put(path, entry.withHandle(entry.handle()));
                changes.add(ReconcileResult.Change.of(
                        ReconcileResult.ChangeType.RETRIED,
                        path));
                if (state == ComponentState.FAILED) {
                    diagnostics.add(LoaderDiagnostic.of(
                            LoaderDiagnosticCode.ACTIVATION_FAILED,
                            path,
                            "component retry did not activate"));
                }
            } catch (Exception error) {
                diagnostics.add(LoaderDiagnostic.of(
                        LoaderDiagnosticCode.ACTIVATION_FAILED,
                        path,
                        safeError(error)));
            }
            return finish(diagnostics.isEmpty(), changes, diagnostics);
        }

        if (entry.context().state() == ContextState.FAILED) {
            if (disposeContext(path, diagnostics)) {
                changes.add(ReconcileResult.Change.of(
                        ReconcileResult.ChangeType.REMOVED,
                        path));
            }
            return finish(diagnostics.isEmpty(), changes, diagnostics);
        }

        diagnostics.add(LoaderDiagnostic.of(
                LoaderDiagnosticCode.INVALID_TREE,
                path,
                "component is not FAILED"));
        return finish(false, changes, diagnostics);
    }

    /**
     * 关闭主体。若运行时已在释放根 Context，说明运行时 close 已经负责本次
     * teardown，Loader 只清空记账并停机，避免把竞态误报为清理失败。
     */
    private Void performClose() throws Exception {
        List<LoaderDiagnostic> diagnostics = new ArrayList<>();
        pruneExternallyDisposed();
        if (isRuntimeClosingBase()) {
            current.clear();
            contexts.clear();
            publish(diagnostics);
            coordinator.shutdown();
            return null;
        }

        boolean converged = true;
        if (owned) {
            converged &= disposeContext("", diagnostics);
        } else {
            for (String path : topLevelPaths()) {
                converged &= disposeContext(path, diagnostics);
            }
        }
        if (converged) {
            current.clear();
            contexts.clear();
            publish(diagnostics);
            coordinator.shutdown();
            return null;
        }

        publish(diagnostics);
        throw new IllegalStateException("loader close did not settle");
    }

    /** 判断基础 Context 的释放是否源于运行时整体关闭（根 Context 已在释放中）。 */
    private boolean isRuntimeClosingBase() {
        ContextState baseState = baseContext.state();
        if (baseState != ContextState.DISPOSING && baseState != ContextState.DISPOSED) {
            return false;
        }
        return runtime.advanced().snapshot().contexts().stream()
                .filter(context -> context.contextId().equals(runtime.root().contextId()))
                .findFirst()
                .map(context -> context.state() == ContextState.DISPOSING
                        || context.state() == ContextState.DISPOSED)
                .orElseGet(() -> runtime.root().state() == ContextState.DISPOSING
                        || runtime.root().state() == ContextState.DISPOSED);
    }

    /** 运行时整体关闭已经接管本次释放时为 true；此时等待其完成而不是重复报错。 */
    private boolean runtimeOwnsDisposalNow() {
        ContextState baseState = baseContext.state();
        if (baseState != ContextState.DISPOSING && baseState != ContextState.DISPOSED) {
            return false;
        }
        String rootId = runtime.root().contextId();
        return runtime.advanced().snapshot().contexts().stream()
                .filter(context -> context.contextId().equals(rootId))
                .findFirst()
                .map(context -> context.state() == ContextState.DISPOSING
                        || context.state() == ContextState.DISPOSED)
                .orElse(true);
    }

    /** 有界等待运行时接管的句柄释放完成；未在时限内到达 DISPOSED 视为失败。 */
    private boolean awaitRuntimeOwnedHandleDisposal(MountHandle handle) {
        try {
            ComponentState state = handle.whenSettled()
                    .toCompletableFuture().get(30, TimeUnit.SECONDS);
            return state == ComponentState.DISPOSED;
        } catch (Exception error) {
            return false;
        }
    }

    /** 有界等待运行时接管的 Context 释放完成。 */
    private boolean awaitRuntimeOwnedContextDisposal(ContextHandle context) {
        CompletableFuture<Void> settled = new CompletableFuture<>();
        pollContextDisposal(context, settled, 3_000);
        try {
            settled.get(30, TimeUnit.SECONDS);
            return true;
        } catch (Exception error) {
            return false;
        }
    }

    // Context 没有 whenSettled 之类的完成句柄，用短轮询有界等待 DISPOSED。
    private void pollContextDisposal(
            ContextHandle context,
            CompletableFuture<Void> settled,
            int remainingTicks) {
        if (context.state() == ContextState.DISPOSED) {
            settled.complete(null);
            return;
        }
        if (remainingTicks <= 0) {
            settled.completeExceptionally(new IllegalStateException(
                    "runtime-owned context disposal did not settle"));
            return;
        }
        CompletableFuture.delayedExecutor(10, TimeUnit.MILLISECONDS)
                .execute(() -> pollContextDisposal(context, settled, remainingTicks - 1));
    }

    /**
     * 把期望树完全准备好：路径归一化、父子完整性、与运行时状态的冲突预检、
     * 工厂解析与配置归一化。任何诊断都会让准备结果为空，保证后续阶段不会
     * 基于半成品树提交结构事务（整批拒绝、现有树不动）。
     */
    private PreparedTree prepare(
            ComponentTree desired,
            List<LoaderDiagnostic> diagnostics) {
        Map<String, PreparedEntry> raw = new LinkedHashMap<>();
        collectEntries(desired.entries(), "", raw, diagnostics);
        if (!diagnostics.isEmpty()) {
            return new PreparedTree(Map.of());
        }
        for (String path : raw.keySet()) {
            String parent = parentPath(path);
            if (!parent.isEmpty() && !raw.containsKey(parent)) {
                diagnostics.add(LoaderDiagnostic.of(
                        LoaderDiagnosticCode.INVALID_TREE,
                        path,
                        "parent entry is missing: " + parent));
            }
        }
        if (!diagnostics.isEmpty()) {
            return new PreparedTree(Map.of());
        }

        preflight(raw.keySet(), diagnostics);
        if (!diagnostics.isEmpty()) {
            return new PreparedTree(Map.of());
        }

        Map<FactoryRef, ResolvedFactory> definitions = new LinkedHashMap<>();
        for (PreparedEntry entry : raw.values()) {
            if (definitions.containsKey(entry.ref())) {
                continue;
            }
            try {
                Optional<ResolvedFactory> definition = resolver.resolve(entry.ref());
                if (definition.isPresent()) {
                    definitions.put(entry.ref(), definition.get());
                } else {
                    diagnostics.add(LoaderDiagnostic.of(
                            LoaderDiagnosticCode.RESOLUTION_FAILED,
                            entry.path(),
                            "resolver returned no implementation"));
                }
            } catch (RuntimeException error) {
                diagnostics.add(LoaderDiagnostic.of(
                        LoaderDiagnosticCode.RESOLUTION_FAILED,
                        entry.path(),
                        safeError(error)));
            }
        }
        if (!diagnostics.isEmpty()) {
            return new PreparedTree(Map.of());
        }

        Map<String, PreparedEntry> prepared = new LinkedHashMap<>();
        for (PreparedEntry candidate : raw.values()) {
            ResolvedFactory definition = definitions.get(candidate.ref());
            Object config;
            try {
                config = definition.decodeConfig(candidate.config());
            } catch (Exception error) {
                diagnostics.add(LoaderDiagnostic.of(
                        LoaderDiagnosticCode.CONFIG_INVALID,
                        candidate.path(),
                        safeError(error)));
                continue;
            }
            prepared.put(candidate.path(), new PreparedEntry(
                    candidate.path(),
                    candidate.name(),
                    candidate.ref(),
                    definition,
                    config));
        }
        return new PreparedTree(prepared);
    }

    /**
     * 结构事务前的冲突预检：确认基础 Context 存活且 ACTIVE，且每个期望路径上的
     * Context 与挂载点要么尚不存在、要么恰好属于本 Loader 的记账。外来结构
     * 一律判为冲突，绝不隐式认领，避免 Loader 释放他人的挂载或 Context。
     */
    private void preflight(
            Set<String> paths,
            List<LoaderDiagnostic> diagnostics) {
        RuntimeSnapshot snapshot = runtime.advanced().snapshot();
        RuntimeSnapshot.ContextSnapshot base = snapshot.contexts().stream()
                .filter(context -> context.contextId().equals(baseContext.contextId()))
                .findFirst()
                .orElse(null);
        if (base == null) {
            diagnostics.add(LoaderDiagnostic.of(
                    LoaderDiagnosticCode.BASE_UNAVAILABLE,
                    "",
                    "base context does not belong to the runtime"));
            return;
        }
        if (base.state() != ContextState.ACTIVE) {
            diagnostics.add(LoaderDiagnostic.of(
                    LoaderDiagnosticCode.BASE_UNAVAILABLE,
                    "",
                    "base context state is " + base.state()));
            return;
        }

        Map<String, RuntimeSnapshot.ContextSnapshot> byPath = new LinkedHashMap<>();
        for (RuntimeSnapshot.ContextSnapshot context : snapshot.contexts()) {
            byPath.put(context.canonicalPath(), context);
        }
        Map<String, RuntimeSnapshot.MountSnapshot> mounts = new LinkedHashMap<>();
        for (RuntimeSnapshot.MountSnapshot mount : snapshot.mounts()) {
            mounts.put(mount.contextId() + "/" + mount.mountId(), mount);
        }
        String baseCanonical = base.canonicalPath();
        for (String path : paths) {
            String canonical = canonical(baseCanonical, path);
            RuntimeSnapshot.ContextSnapshot existing = byPath.get(canonical);
            ContextHandle local = contexts.get(path);
            if (existing != null && (local == null || !existing.contextId().equals(local.contextId()))) {
                diagnostics.add(LoaderDiagnostic.of(
                        LoaderDiagnosticCode.CONTEXT_CONFLICT,
                        path,
                        "canonical context already belongs to another owner: " + canonical));
                continue;
            }
            if (local != null && existing == null) {
                diagnostics.add(LoaderDiagnostic.of(
                        LoaderDiagnosticCode.CONTEXT_CONFLICT,
                        path,
                        "managed context is no longer present in the runtime"));
                continue;
            }

            ManagedEntry entry = current.get(path);
            RuntimeSnapshot.MountSnapshot mounted = existing == null
                    ? null
                    : mounts.get(existing.contextId() + "/" + path);
            if (mounted != null
                    && (entry == null || !mounted.handleId().equals(entry.handle().handleId()))) {
                diagnostics.add(LoaderDiagnostic.of(
                        LoaderDiagnosticCode.CONTEXT_CONFLICT,
                        path,
                        "mount id is already occupied by another component"));
            }
        }
    }

    /**
     * 同步记账与运行时状态：移除已被外部（宿主事务、运行时 close 等）释放的
     * Context 与句柄。Context 只把 ACTIVE/FAILED 视为存活；组件只要尚未到达
     * DISPOSED 就仍需记账。
     */
    private void pruneExternallyDisposed() {
        RuntimeSnapshot snapshot = runtime.advanced().snapshot();
        Set<String> liveContextIds = snapshot.contexts().stream()
                .filter(context -> context.state() == ContextState.ACTIVE
                        || context.state() == ContextState.FAILED)
                .map(RuntimeSnapshot.ContextSnapshot::contextId)
                .collect(LinkedHashSet::new, LinkedHashSet::add, LinkedHashSet::addAll);
        Set<String> liveHandleIds = snapshot.mounts().stream()
                .filter(mount -> mount.state() != ComponentState.DISPOSED)
                .map(RuntimeSnapshot.MountSnapshot::handleId)
                .collect(LinkedHashSet::new, LinkedHashSet::add, LinkedHashSet::addAll);

        contexts.keySet().removeIf(path -> !liveContextIds.contains(contexts.get(path).contextId()));
        current.keySet().removeIf(path -> {
            ManagedEntry entry = current.get(path);
            return !liveHandleIds.contains(entry.handle().handleId());
        });
        publish(latestDiagnostics);
    }

    /**
     * 期望仍然存在的路径若其 Context 已 FAILED，先释放该子树，后续阶段才能
     * 在下一次事务中重建干净的 Context。
     */
    private boolean settleFailedDesiredContexts(
            PreparedTree desired,
            List<LoaderDiagnostic> diagnostics) {
        for (String path : desired.paths()) {
            if (contexts.get(path) != null
                    && contexts.get(path).state() == ContextState.FAILED) {
                if (!disposeContext(path, diagnostics)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * 释放不再被期望树包含的条目。清理失败记 BLOCKED 并立即中止，留待下一次
     * reconcile 继续收敛，不在未释放干净的结构上挂载新条目。
     */
    private boolean removeMissing(
            PreparedTree desired,
            List<ReconcileResult.Change> changes,
            List<LoaderDiagnostic> diagnostics) {
        List<String> missing = contexts.keySet().stream()
                .filter(path -> !desired.paths().contains(path))
                .filter(this::isRemovalRoot)
                .sorted()
                .toList();
        for (String path : missing) {
            if (disposeContext(path, diagnostics)) {
                changes.add(ReconcileResult.Change.of(
                        ReconcileResult.ChangeType.REMOVED,
                        path));
            } else {
                changes.add(ReconcileResult.Change.of(
                        ReconcileResult.ChangeType.BLOCKED,
                        path));
                return false;
            }
        }
        return true;
    }

    /**
     * 判断是否应作为释放子树的根：顶层路径总是根；有父且父仍由 Loader 管理、
     * 且父本身不在本批已移除候选中时，子路径才是根。释放父 Context 会级联
     * 其子树，逐条释放反而可能与级联释放竞争。
     */
    private boolean isRemovalRoot(String path) {
        String parent = parentPath(path);
        return parent.isEmpty()
                || (contexts.containsKey(parent) && !contexts.get(parent).state().equals(ContextState.DISPOSED)
                && !isPathDesiredRemovalCandidate(parent));
    }

    private boolean isPathDesiredRemovalCandidate(String parent) {
        return !current.containsKey(parent) && !contexts.containsKey(parent);
    }

    /**
     * 实现身份（而非工厂引用）变化的条目必须整句柄替换：引用相同但指纹不同
     * 也视为新实现。任一替换失败都会中止后续阶段，防止半棵树跨实现版本。
     */
    private boolean replaceChangedFactories(
            PreparedTree desired,
            List<ReconcileResult.Change> changes,
            List<LoaderDiagnostic> diagnostics) {
        for (String path : desired.paths()) {
            ManagedEntry old = current.get(path);
            PreparedEntry next = desired.entry(path);
            if (old == null || old.definition().identity().equals(next.definition().identity())) {
                continue;
            }
            if (!replaceEntry(old, next, diagnostics)) {
                changes.add(ReconcileResult.Change.of(
                        ReconcileResult.ChangeType.BLOCKED,
                        path));
                return false;
            }
            changes.add(ReconcileResult.Change.of(
                    ReconcileResult.ChangeType.REPLACED,
                    path));
        }
        return true;
    }

    /**
     * 替换单个条目：先等待旧句柄完全释放，再在原 Context 上挂载新实现。
     * 新挂载被拒绝时，用旧定义做补偿性重挂载，保证路径不会停留在“旧已释放、
     * 新未挂载”的空档；补偿自身也失败时才报告 COMPENSATION_FAILED。
     */
    private boolean replaceEntry(
            ManagedEntry old,
            PreparedEntry next,
            List<LoaderDiagnostic> diagnostics) {
        if (!disposeHandle(old.path(), old.handle(), diagnostics)) {
            return false;
        }
        current.remove(old.path());

        MountResult mounted = mountOne(next, old.context(), diagnostics);
        if (mounted.mounted()) {
            register(mounted.attempt());
            if (mounted.attempt().handle().state() == ComponentState.FAILED) {
                diagnostics.add(LoaderDiagnostic.of(
                        LoaderDiagnosticCode.ACTIVATION_FAILED,
                        next.path(),
                        "replacement implementation failed to activate"));
            }
            return true;
        }
        if (mounted.committedUnsettled()) {
            // 新挂载已提交但未收敛，句柄已进入记账且占用槽位；
            // 此时绝不能用旧实现补偿重挂同一个 mountId。
            return false;
        }

        PreparedEntry fallback = new PreparedEntry(
                old.path(),
                old.name(),
                null,
                old.definition(),
                old.config());
        List<LoaderDiagnostic> compensation = new ArrayList<>();
        MountResult restored = mountOne(fallback, old.context(), compensation);
        if (restored.mounted()) {
            register(restored.attempt());
            diagnostics.add(LoaderDiagnostic.of(
                    LoaderDiagnosticCode.REPLACEMENT_BLOCKED,
                    old.path(),
                    "replacement was rejected and the previous implementation was restored"));
            return false;
        }
        diagnostics.add(LoaderDiagnostic.of(
                LoaderDiagnosticCode.COMPENSATION_FAILED,
                old.path(),
                "replacement was rejected and compensation also failed"));
        diagnostics.addAll(compensation);
        return false;
    }

    /**
     * 挂载全部缺失条目：Context 在同一个运行时事务中创建，挂载逐条执行；
     * 任一失败都按 LIFO 回滚本批已挂载句柄与新 Context，兑现“失败批次不
     * 留下部分挂载”的契约。
     */
    private boolean addMissing(
            PreparedTree desired,
            List<ReconcileResult.Change> changes,
            List<LoaderDiagnostic> diagnostics) {
        List<String> missing = desired.paths().stream()
                .filter(path -> !current.containsKey(path))
                .toList();
        if (missing.isEmpty()) {
            return true;
        }

        Map<String, ContextHandle> created = new LinkedHashMap<>();
        if (!createMissingContexts(missing, created, diagnostics)) {
            return false;
        }

        List<ManagedEntry> mounted = new ArrayList<>();
        try {
            for (String path : missing) {
                PreparedEntry entry = desired.entry(path);
                ContextHandle context = contexts.get(path);
                MountResult attempt = mountOne(entry, context, diagnostics);
                if (attempt.committedUnsettled()) {
                    // 本批存在已提交但未收敛的挂载：回滚可能无法完成，
                    // 保留全部记账，等待后续 reconcile/close 继续收敛。
                    changes.add(ReconcileResult.Change.of(
                            ReconcileResult.ChangeType.BLOCKED,
                            path));
                    return false;
                }
                if (!attempt.mounted()) {
                    rollbackAdd(mounted, created, diagnostics);
                    return false;
                }
                ManagedEntry managed = register(attempt.attempt());
                mounted.add(managed);
                changes.add(ReconcileResult.Change.of(
                        ReconcileResult.ChangeType.MOUNTED,
                        path));
            }
            return true;
        } catch (RuntimeException error) {
            diagnostics.add(LoaderDiagnostic.of(
                    LoaderDiagnosticCode.STRUCTURE_REJECTED,
                    "",
                    safeError(error)));
            rollbackAdd(mounted, created, diagnostics);
            return false;
        } finally {
            publish(latestDiagnostics);
        }
    }

    /**
     * 在单个运行时事务中按父先子后的顺序创建全部缺失 Context；事务被拒绝或
     * 结算失败时立即回滚已创建的 Context。
     */
    private boolean createMissingContexts(
            List<String> missing,
            Map<String, ContextHandle> created,
            List<LoaderDiagnostic> diagnostics) {
        Map<String, ContextHandle> provisional = new LinkedHashMap<>();
        TransactionReceipt<ContextHandle> receipt;
        try {
            receipt = runtime.advanced().transact(transaction -> {
                ContextHandle lastContext = baseContext;
                for (String path : missing) {
                    ContextHandle reusable = contexts.get(path);
                    if (reusable != null) {
                        if (reusable.state() != ContextState.ACTIVE) {
                            throw new IllegalStateException("managed context is not active: " + path);
                        }
                        provisional.put(path, reusable);
                        lastContext = reusable;
                        continue;
                    }
                    ContextHandle parent = parentContext(path, provisional);
                    ContextHandle child = transaction.childContext(parent, lastSegment(path));
                    provisional.put(path, child);
                    created.put(path, child);
                    lastContext = child;
                }
                return lastContext;
            });
        } catch (TransactionRejectedException rejection) {
            addCoreDiagnostics(LoaderDiagnosticCode.STRUCTURE_REJECTED, missing.getFirst(), diagnostics,
                    rejection.diagnostics());
            created.clear();
            return false;
        }
        SettlementReport report;
        try {
            report = receipt.awaitSettled(Duration.ofSeconds(30));
        } catch (Exception error) {
            diagnostics.add(LoaderDiagnostic.of(
                    LoaderDiagnosticCode.STRUCTURE_REJECTED,
                    missing.getFirst(),
                    "context settlement failed: " + safeError(error)));
            rollbackCreatedContexts(created, diagnostics);
            return false;
        }
        if (settlementIndicatesFailure(report)) {
            addSettlementDiagnostics(
                    LoaderDiagnosticCode.STRUCTURE_REJECTED,
                    missing.getFirst(),
                    diagnostics,
                    report);
            rollbackCreatedContexts(created, diagnostics);
            return false;
        }
        contexts.putAll(provisional);
        publish(latestDiagnostics);
        return true;
    }

    private ContextHandle parentContext(
            String path,
            Map<String, ContextHandle> provisional) {
        String parentPath = parentPath(path);
        if (parentPath.isEmpty()) {
            return baseContext;
        }
        ContextHandle provisionalParent = provisional.get(parentPath);
        if (provisionalParent != null) {
            return provisionalParent;
        }
        ContextHandle parent = contexts.get(parentPath);
        if (parent == null || parent.state() != ContextState.ACTIVE) {
            throw new IllegalStateException("parent context is unavailable: " + parentPath);
        }
        return parent;
    }

    /**
     * 执行一次受控挂载并消费其 settlement 报告。除异常外还校验策略确实使用了
     * 分配的槽位：返回句柄必须绑定在分配的 contextId 与 mountId 上；越界
     * 句柄立即释放并整批拒绝，防止策略把挂载放到 Loader 记账之外。
     *
     * <p>任何 commit 后的失败都不会遗留未跟踪挂载：settlement 未收敛且无法
     * 有界释放的已提交句柄会进入 Loader 记账并以 SETTLEMENT_UNSETTLED 拒绝；
     * 无法取得句柄的槽位占用也会被显式报告而不是静默吞掉。</p>
     */
    private MountResult mountOne(
            PreparedEntry entry,
            ContextHandle context,
            List<LoaderDiagnostic> diagnostics) {
        if (context == null || context.state() != ContextState.ACTIVE) {
            diagnostics.add(LoaderDiagnostic.of(
                    LoaderDiagnosticCode.STRUCTURE_REJECTED,
                    entry.path(),
                    "mount context is not active"));
            return MountResult.REJECTED;
        }
        AllocatedMountContext mountContext = new AllocatedMountContext(
                runtime, context, entry.path(), mountSettlementTimeout, mountRecoveryTimeout);
        MountHandle handle = null;
        boolean rejected = false;
        try {
            handle = entry.definition()
                    .mountStrategy()
                    .mountAsync(mountContext, entry.config())
                    .toCompletableFuture()
                    .get();
        } catch (ControlledMountException error) {
            addCoreDiagnostics(
                    LoaderDiagnosticCode.STRUCTURE_REJECTED,
                    entry.path(),
                    diagnostics,
                    error.diagnostics());
            rejected = true;
        } catch (ExecutionException error) {
            if (error.getCause() instanceof ControlledMountException controlled) {
                addCoreDiagnostics(
                        LoaderDiagnosticCode.STRUCTURE_REJECTED,
                        entry.path(),
                        diagnostics,
                        controlled.diagnostics());
            } else {
                diagnostics.add(LoaderDiagnostic.of(
                        LoaderDiagnosticCode.STRUCTURE_REJECTED,
                        entry.path(),
                        safeError(error)));
            }
            rejected = true;
        } catch (Exception error) {
            diagnostics.add(LoaderDiagnostic.of(
                    LoaderDiagnosticCode.STRUCTURE_REJECTED,
                    entry.path(),
                    safeError(error)));
            rejected = true;
        }
        if (!rejected) {
            if (handle == null) {
                diagnostics.add(LoaderDiagnostic.of(
                        LoaderDiagnosticCode.STRUCTURE_REJECTED,
                        entry.path(),
                        "controlled mount returned no mount handle"));
                return rejectedWithoutOrphan(entry, context, mountContext, diagnostics);
            }
            if (!context.contextId().equals(handle.contextId())
                    || !entry.path().equals(handle.mountId())) {
                diagnostics.add(LoaderDiagnostic.of(
                        LoaderDiagnosticCode.STRUCTURE_REJECTED,
                        entry.path(),
                        "controlled mount returned a mount handle outside its allocated slot"));
                disposeHandle(entry.path(), handle, diagnostics);
                return rejectedWithoutOrphan(entry, context, mountContext, diagnostics);
            }
            SettlementReport report = mountContext.lastReport();
            if (settlementIndicatesFailure(report)) {
                addSettlementDiagnostics(
                        LoaderDiagnosticCode.ACTIVATION_FAILED,
                        entry.path(),
                        diagnostics,
                        report);
            }
            return MountResult.mounted(new MountAttempt(
                    entry.path(),
                    entry.name(),
                    context,
                    handle,
                    entry.definition(),
                    entry.config()));
        }
        return rejectedWithoutOrphan(entry, context, mountContext, diagnostics);
    }

    /**
     * commit 后失败路径的收口：先接管 AllocatedMountContext 无法有界释放的
     * 已提交句柄（写入记账），再确认槽位上没有无法接管的存活挂载。
     */
    private MountResult rejectedWithoutOrphan(
            PreparedEntry entry,
            ContextHandle context,
            AllocatedMountContext mountContext,
            List<LoaderDiagnostic> diagnostics) {
        MountHandle committed = mountContext.committedHandle();
        if (committed != null) {
            register(new MountAttempt(
                    entry.path(),
                    entry.name(),
                    context,
                    committed,
                    entry.definition(),
                    entry.config()));
            diagnostics.add(LoaderDiagnostic.of(
                    LoaderDiagnosticCode.SETTLEMENT_UNSETTLED,
                    entry.path(),
                    "committed mount did not settle within " + mountSettlementTimeout
                            + "; its handle is kept in loader bookkeeping for recovery"));
            return MountResult.COMMITTED_UNSETTLED;
        }
        boolean untracked = runtime.advanced().snapshot().mounts().stream()
                .anyMatch(mount -> mount.contextId().equals(context.contextId())
                        && mount.mountId().equals(entry.path())
                        && mount.state() != ComponentState.DISPOSED);
        if (untracked) {
            diagnostics.add(LoaderDiagnostic.of(
                    LoaderDiagnosticCode.STRUCTURE_REJECTED,
                    entry.path(),
                    "an untracked committed mount occupies the allocated slot"
                            + " and could not be recovered"));
        }
        return MountResult.REJECTED;
    }

    /**
     * Loader 只把 FAILED 挂载视为 settlement 失败；owned child 与有意替换的旧
     * child 会以 DISPOSED 出现在报告中，那是预期结果而不是失败。
     */
    static boolean settlementIndicatesFailure(SettlementReport report) {
        return report != null && report.hasFailedMounts();
    }

    /**
     * 新增批次的补偿：按挂载的逆序释放句柄，再释放新建 Context 子树；
     * 补偿失败会额外报告 COMPENSATION_FAILED，不掩盖原始诊断。
     */
    private void rollbackAdd(
            List<ManagedEntry> mounted,
            Map<String, ContextHandle> created,
            List<LoaderDiagnostic> diagnostics) {
        List<LoaderDiagnostic> compensation = new ArrayList<>();
        for (int index = mounted.size() - 1; index >= 0; index--) {
            ManagedEntry entry = mounted.get(index);
            disposeHandle(entry.path(), entry.handle(), compensation);
        }
        rollbackContexts(created, compensation);
        if (!compensation.isEmpty()) {
            diagnostics.add(LoaderDiagnostic.of(
                    LoaderDiagnosticCode.COMPENSATION_FAILED,
                    "",
                    "add rollback left unresolved resources"));
            diagnostics.addAll(compensation);
        }
        for (ManagedEntry entry : mounted) {
            current.remove(entry.path());
        }
        for (String path : created.keySet()) {
            contexts.remove(path);
        }
        publish(latestDiagnostics);
    }

    // 只释放新建子树的根；释放根 Context 会级联子孙，逐个释放会重复处置。
    private void rollbackContexts(
            Map<String, ContextHandle> created,
            List<LoaderDiagnostic> compensation) {
        List<String> roots = created.keySet().stream()
                .filter(path -> {
                    String parent = parentPath(path);
                    return parent.isEmpty() || !created.containsKey(parent);
                })
                .sorted(Comparator.reverseOrder())
                .toList();
        for (String path : roots) {
            ContextHandle context = created.get(path);
            if (context != null) {
                disposeHandlelessContext(path, context, compensation);
            }
        }
    }

    /**
     * 实现身份不变的配置变化走重配置而不是替换。先更新记账中的期望配置：
     * 即使句柄当前 FAILED，也保留最新配置供后续显式 retry 使用；只有非
     * FAILED 句柄才立即调用定义的重配置策略。
     */
    private void updateConfigs(
            PreparedTree desired,
            List<ReconcileResult.Change> changes,
            List<LoaderDiagnostic> diagnostics) {
        for (String path : desired.paths()) {
            ManagedEntry managed = current.get(path);
            PreparedEntry desiredEntry = desired.entry(path);
            if (managed == null
                    || !managed.definition().identity().equals(
                            desiredEntry.definition().identity())) {
                continue;
            }
            if (Objects.equals(managed.config(), desiredEntry.config())) {
                continue;
            }

            ManagedEntry latest = managed.withDefinitionAndConfig(
                    desiredEntry.definition(),
                    desiredEntry.config());
            current.put(path, latest);
            if (managed.handle().state() == ComponentState.FAILED) {
                diagnostics.add(LoaderDiagnostic.of(
                        LoaderDiagnosticCode.ACTIVATION_FAILED,
                        path,
                        "configuration is stored but the failed implementation was not retried"));
                continue;
            }
            try {
                ComponentState state = desiredEntry.definition()
                        .reconfigureStrategy()
                        .reconfigureAsync(managed.handle(), desiredEntry.config())
                        .toCompletableFuture()
                        .get();
                changes.add(ReconcileResult.Change.of(
                        ReconcileResult.ChangeType.UPDATED,
                        path));
                if (state == ComponentState.FAILED) {
                    diagnostics.add(LoaderDiagnostic.of(
                            LoaderDiagnosticCode.ACTIVATION_FAILED,
                            path,
                            "implementation failed with the latest configuration"));
                }
            } catch (Exception error) {
                diagnostics.add(LoaderDiagnostic.of(
                        LoaderDiagnosticCode.ACTIVATION_FAILED,
                        path,
                        "configuration update was rejected: " + safeError(error)));
            }
        }
    }

    /**
     * 汇总仍未收敛的期望条目。FAILED 保持既有语义（显式 retry）；
     * STARTING/STOPPING/DISPOSING 等过渡态说明上一次已提交变更尚未完成，
     * 以 SETTLEMENT_UNSETTLED 报告而不是谎称收敛。WAITING 是合法稳态
     * （等待必需能力提供方），不计入诊断。
     */
    private void addUnconvergedDiagnostics(
            PreparedTree desired,
            List<LoaderDiagnostic> diagnostics) {
        for (String path : desired.paths()) {
            ManagedEntry entry = current.get(path);
            if (entry == null) {
                continue;
            }
            ComponentState state = entry.handle().state();
            if (state == ComponentState.FAILED) {
                diagnostics.add(LoaderDiagnostic.of(
                        LoaderDiagnosticCode.ACTIVATION_FAILED,
                        path,
                        "desired component is FAILED; call retry(path) explicitly"));
            } else if (state != ComponentState.ACTIVE && state != ComponentState.WAITING) {
                diagnostics.add(LoaderDiagnostic.of(
                        LoaderDiagnosticCode.SETTLEMENT_UNSETTLED,
                        path,
                        "desired component has not settled: state=" + state
                                + ", goal=" + entry.handle().goal()));
            }
        }
    }

    /**
     * 通过运行时事务释放单个句柄并等待结算；若运行时整体关闭已经接管释放，
     * 只等待其完成而不把竞态重复报告为失败。
     */
    private boolean disposeHandle(
            String path,
            MountHandle handle,
            List<LoaderDiagnostic> diagnostics) {
        if (handle.state() == ComponentState.DISPOSED) {
            return true;
        }
        try {
            CompletionStage<ComponentState> cleanup =
                    handle.state() == ComponentState.FAILED
                            && handle.goal() == ComponentGoal.DISPOSED
                    ? handle.retryAsync()
                    : handle.disposeAsync();
            ComponentState settled = cleanup.toCompletableFuture().get();
            if (settled == ComponentState.DISPOSED) {
                return true;
            }
            diagnostics.add(LoaderDiagnostic.of(
                    LoaderDiagnosticCode.TEARDOWN_FAILED,
                    path,
                    "component cleanup reached " + settled));
            return false;
        } catch (Exception error) {
            if (runtimeOwnsDisposalNow()
                    && awaitRuntimeOwnedHandleDisposal(handle)) {
                return true;
            }
            addAsyncDiagnostics(LoaderDiagnosticCode.TEARDOWN_FAILED, path, diagnostics, error);
            return false;
        }
    }

    /** 释放路径对应的 Context；空路径表示 owned 模式的基础 Context。 */
    private boolean disposeContext(
            String path,
            List<LoaderDiagnostic> diagnostics) {
        ContextHandle context = path.isEmpty()
                ? baseContext
                : contexts.get(path);
        return disposeHandlelessContext(path, context, diagnostics);
    }

    /**
     * 通过运行时事务释放 Context 并等待结算。释放会级联其子 Context 与挂载，
     * 成功后从记账中移除整个子树；运行时整体关闭已接管释放时只做有界等待。
     */
    private boolean disposeHandlelessContext(
            String path,
            ContextHandle context,
            List<LoaderDiagnostic> diagnostics) {
        if (context == null) {
            return true;
        }
        if (context.state() == ContextState.DISPOSED) {
            prune(path);
            publish(latestDiagnostics);
            return true;
        }
        try {
            ContextState settled = context.disposeAsync().toCompletableFuture().get();
            if (settled != ContextState.DISPOSED) {
                diagnostics.add(LoaderDiagnostic.of(
                        LoaderDiagnosticCode.TEARDOWN_FAILED,
                        path,
                        "context cleanup reached " + settled));
                return false;
            }
        } catch (Exception error) {
            if (runtimeOwnsDisposalNow()
                    && awaitRuntimeOwnedContextDisposal(context)) {
                prune(path);
                publish(latestDiagnostics);
                return true;
            }
            addAsyncDiagnostics(LoaderDiagnosticCode.TEARDOWN_FAILED, path, diagnostics, error);
            return false;
        }
        prune(path);
        publish(latestDiagnostics);
        return true;
    }

    /** 从记账中移除该路径及其子树；Context 释放会级联处置全部子孙。 */
    private void prune(String rootPath) {
        if (rootPath.isEmpty()) {
            current.clear();
            contexts.clear();
            return;
        }
        String prefix = rootPath + "/";
        current.keySet().removeIf(path -> path.equals(rootPath) || path.startsWith(prefix));
        contexts.keySet().removeIf(path -> path.equals(rootPath) || path.startsWith(prefix));
    }

    private List<String> topLevelPaths() {
        return contexts.keySet().stream()
                .filter(path -> parentPath(path).isEmpty())
                .sorted()
                .toList();
    }

    /** 记录一次成功挂载并发布新视图。 */
    private ManagedEntry register(MountAttempt entry) {
        contexts.put(entry.path(), entry.context());
        ManagedEntry managed = new ManagedEntry(
                entry.path(),
                entry.name(),
                entry.context(),
                entry.handle(),
                entry.definition(),
                entry.config());
        current.put(entry.path(), managed);
        publish(latestDiagnostics);
        return managed;
    }

    /** 汇总诊断、发布视图并构造结果；converged 要求无任何诊断。 */
    private ReconcileResult finish(
            boolean proposed,
            List<ReconcileResult.Change> changes,
            List<LoaderDiagnostic> diagnostics) {
        List<LoaderDiagnostic> copied = List.copyOf(diagnostics).stream().sorted().toList();
        latestDiagnostics = copied;
        publish(copied);
        return new ReconcileResult(proposed && copied.isEmpty(), changes, copied);
    }

    /** 原子发布不可变视图，供协调器之外的 snapshot() 读取。 */
    private void publish(List<LoaderDiagnostic> diagnostics) {
        view = new LoaderView(
                closed,
                Map.copyOf(current),
                Map.copyOf(contexts));
        latestDiagnostics = List.copyOf(diagnostics).stream().sorted().toList();
    }

    private void rollbackCreatedContexts(
            Map<String, ContextHandle> created,
            List<LoaderDiagnostic> diagnostics) {
        List<LoaderDiagnostic> compensation = new ArrayList<>();
        rollbackContexts(created, compensation);
        diagnostics.addAll(compensation);
        created.clear();
    }

    private void addSettlementDiagnostics(
            LoaderDiagnosticCode fallback,
            String path,
            List<LoaderDiagnostic> diagnostics,
            SettlementReport report) {
        for (SettlementReport.MountOutcome outcome : report.failedMounts()) {
            if (outcome.diagnostics().isEmpty()) {
                diagnostics.add(LoaderDiagnostic.of(
                        fallback,
                        path,
                        "mount " + outcome.mountId() + " settled as " + outcome.state()));
            } else {
                addCoreDiagnostics(fallback, path, diagnostics, outcome.diagnostics());
            }
        }
        if (!report.diagnostics().isEmpty()) {
            addCoreDiagnostics(fallback, path, diagnostics, report.diagnostics());
        }
        if (report.failedMounts().isEmpty() && report.diagnostics().isEmpty()) {
            diagnostics.add(LoaderDiagnostic.of(
                    fallback,
                    path,
                    "settlement completed with an unsuccessful outcome"));
        }
    }

    /** 展开 async 包装；Core 事务拒绝保留结构化诊断，其余失败使用根因消息。 */
    private void addAsyncDiagnostics(
            LoaderDiagnosticCode fallback,
            String path,
            List<LoaderDiagnostic> diagnostics,
            Throwable error) {
        Throwable cause = error;
        while ((cause instanceof java.util.concurrent.CompletionException
                || cause instanceof ExecutionException) && cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause instanceof TransactionRejectedException rejection) {
            addCoreDiagnostics(fallback, path, diagnostics, rejection.diagnostics());
            return;
        }
        diagnostics.add(LoaderDiagnostic.of(
                fallback,
                path,
                safeError(cause)));
    }
    /** 把 Core 事务诊断映射进 Loader 结果；核心未给出诊断时提供兜底文案。 */
    private void addCoreDiagnostics(
            LoaderDiagnosticCode fallback,
            String path,
            List<LoaderDiagnostic> diagnostics,
            List<RuntimeDiagnostic> values) {
        if (values.isEmpty()) {
            diagnostics.add(LoaderDiagnostic.of(fallback, path, "runtime transaction was rejected"));
            return;
        }
        for (RuntimeDiagnostic value : values) {
            diagnostics.add(LoaderDiagnostic.of(
                    mapCode(value.code(), fallback),
                    path,
                    diagnosticMessage(value)));
        }
    }

    private String diagnosticMessage(RuntimeDiagnostic diagnostic) {
        RuntimeDiagnostic stable = Objects.requireNonNull(diagnostic, "diagnostic");
        String summary = stable.failure().summary();
        return summary.isBlank() ? stable.message() : stable.message() + " (" + summary + ")";
    }

    /** Core 诊断码到 Loader 诊断码的稳定映射；无法识别时回退到调用方指定的码。 */
    private LoaderDiagnosticCode mapCode(
            DiagnosticCode code,
            LoaderDiagnosticCode fallback) {
        return switch (code) {
            case ACTIVATION_FAILED -> LoaderDiagnosticCode.ACTIVATION_FAILED;
            case CLEANUP_FAILED, ROLLBACK_FAILED -> LoaderDiagnosticCode.TEARDOWN_FAILED;
            case INVALID_CONFIG -> LoaderDiagnosticCode.CONFIG_INVALID;
            case MISSING_CAPABILITY, CAPABILITY_SLOT_OCCUPIED, CAPABILITY_TYPE_CONFLICT,
                    BINDING_CYCLE, NON_CONVERGENT_RECONCILE, INVALID_LIFECYCLE_OPERATION,
                    INVALID_MOUNT_ID -> LoaderDiagnosticCode.STRUCTURE_REJECTED;
        };
    }

    /**
     * 深度优先展平声明树：归一化路径、校验重复与越界。相对单段路径拼接在
     * 父路径之下；rawConfig 原样进入 ResolvedFactory decoder，null 表示无配置声明。
     */
    private void collectEntries(
            List<ComponentEntry> entries,
            String parentPath,
            Map<String, PreparedEntry> flattened,
            List<LoaderDiagnostic> diagnostics) {
        for (ComponentEntry entry : entries) {
            String path;
            try {
                path = normalizePath(entry.path(), parentPath);
            } catch (RuntimeException error) {
                diagnostics.add(LoaderDiagnostic.of(
                        LoaderDiagnosticCode.INVALID_TREE,
                        entry.path(),
                        safeError(error)));
                continue;
            }
            if (path.isEmpty()) {
                diagnostics.add(LoaderDiagnostic.of(
                        LoaderDiagnosticCode.INVALID_TREE,
                        parentPath,
                        "entry path is empty"));
                continue;
            }
            if (!parentPath.isEmpty() && !path.startsWith(parentPath + "/")) {
                diagnostics.add(LoaderDiagnostic.of(
                        LoaderDiagnosticCode.INVALID_TREE,
                        path,
                        "child path is outside parent: " + parentPath));
                continue;
            }
            if (flattened.containsKey(path)) {
                diagnostics.add(LoaderDiagnostic.of(
                        LoaderDiagnosticCode.INVALID_TREE,
                        path,
                        "duplicate normalized entry path"));
                continue;
            }
            flattened.put(path, new PreparedEntry(
                    path,
                    lastSegment(path),
                    entry.factoryRef(),
                    null,
                    entry.rawConfig()));
            collectEntries(entry.children(), path, flattened, diagnostics);
        }
    }

    private static String canonical(String baseCanonical, String path) {
        if (baseCanonical.endsWith("/")) {
            return baseCanonical + path;
        }
        return baseCanonical + "/" + path;
    }

    private static String parentPath(String path) {
        int index = path.lastIndexOf('/');
        return index < 0 ? "" : path.substring(0, index);
    }

    private static String lastSegment(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    /**
     * 统一路径：trim、反斜杠归一为斜杠、折叠 “.” 与空段、拒绝 “..”。
     * 相对单段路径在存在父路径时拼接到父路径之下，因此 “/alpha”、“alpha/”
     * 与 “ alpha ” 归一化后是同一条目。
     */
    private static String normalizePath(String raw, String parentPath) {
        if (raw == null) {
            return "";
        }
        String value = raw.trim().replace('\\', '/');
        if (value.isEmpty()) {
            return "";
        }
        boolean absolute = value.startsWith("/");
        String[] parts = value.split("/");
        List<String> segments = new ArrayList<>();
        for (String part : parts) {
            String segment = part == null ? "" : part.trim();
            if (segment.isEmpty() || segment.equals(".")) {
                continue;
            }
            if (segment.equals("..")) {
                throw new IllegalArgumentException("relative parent segments are not supported");
            }
            segments.add(segment);
        }
        if (segments.isEmpty()) {
            return "";
        }
        String normalized = String.join("/", segments);
        if (!absolute && !parentPath.isEmpty() && !normalized.contains("/")) {
            return parentPath + "/" + normalized;
        }
        return normalized;
    }

    /** 有界根因描述；对恶意 Throwable 的防护见 {@link LoaderErrors}。 */
    private static String safeError(Throwable error) {
        return LoaderErrors.safe(error);
    }

    /** 期望条目完成准备后的形态：归一化路径 + 解析出的定义 + 归一化配置。 */
    private record PreparedEntry(
            String path,
            String name,
            FactoryRef ref,
            ResolvedFactory definition,
            Object config) {
    }

    /** 完成准备的期望树；paths() 按层级从浅到深排序，保证先父后子处理。 */
    private record PreparedTree(Map<String, PreparedEntry> entries) {

        List<String> paths() {
            return entries.keySet().stream()
                    .sorted(Comparator.comparingInt((String path) -> path.split("/").length)
                            .thenComparing(Function.identity()))
                    .toList();
        }

        PreparedEntry entry(String path) {
            return entries.get(path);
        }
    }

    /** 一次成功受控挂载的产物，等待 register() 写入记账。 */
    private record MountAttempt(
            String path,
            String name,
            ContextHandle context,
            MountHandle handle,
            ResolvedFactory definition,
            Object config) {
    }

    /**
     * 一次受控挂载尝试的结果：MOUNTED 返回可用句柄；REJECTED 表示槽位
     * 干净（从未提交或已可靠释放）；COMMITTED_UNSETTLED 表示已提交句柄
     * 无法有界释放、已由 mountOne 写入 Loader 记账并占用槽位。
     */
    private record MountResult(Kind kind, MountAttempt attempt) {

        enum Kind {
            MOUNTED,
            REJECTED,
            COMMITTED_UNSETTLED
        }

        private static final MountResult REJECTED = new MountResult(Kind.REJECTED, null);
        private static final MountResult COMMITTED_UNSETTLED =
                new MountResult(Kind.COMMITTED_UNSETTLED, null);

        private static MountResult mounted(MountAttempt value) {
            return new MountResult(Kind.MOUNTED, value);
        }

        private boolean mounted() {
            return kind == Kind.MOUNTED;
        }

        private boolean committedUnsettled() {
            return kind == Kind.COMMITTED_UNSETTLED;
        }
    }
    /** Loader 对单个受管路径的记账：Context、句柄、当前定义与归一化配置。 */
    private static final class ManagedEntry {
        private final String path;
        private final String name;
        private final ContextHandle context;
        private final MountHandle handle;
        private final ResolvedFactory definition;
        private final Object config;

        private ManagedEntry(
                String path,
                String name,
                ContextHandle context,
                MountHandle handle,
                ResolvedFactory definition,
                Object config) {
            this.path = path;
            this.name = name;
            this.context = context;
            this.handle = handle;
            this.definition = definition;
            this.config = config;
        }

        private String path() {
            return path;
        }

        private String name() {
            return name;
        }

        private ContextHandle context() {
            return context;
        }

        private MountHandle handle() {
            return handle;
        }

        private ResolvedFactory definition() {
            return definition;
        }

        private Object config() {
            return config;
        }

        private ManagedEntry withHandle(MountHandle replacement) {
            return new ManagedEntry(path, name, context, replacement, definition, config);
        }

        private ManagedEntry withDefinitionAndConfig(
                ResolvedFactory replacementDefinition,
                Object replacementConfig) {
            return new ManagedEntry(path, name, context, handle, replacementDefinition,
                    replacementConfig);
        }
    }

    /** 发布给 snapshot() 的不可变状态视图。 */
    private record LoaderView(
            boolean closed,
            Map<String, ManagedEntry> entries,
            Map<String, ContextHandle> contexts) {

        private static final LoaderView EMPTY = new LoaderView(false, Map.of(), Map.of());
    }
}
