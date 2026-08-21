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

import io.knotra.ComponentHandle;
import io.knotra.ComponentState;
import io.knotra.ContextHandle;
import io.knotra.ContextState;
import io.knotra.DiagnosticCode;
import io.knotra.KnotraRuntime;
import io.knotra.MutationResult;
import io.knotra.NoConfig;
import io.knotra.RuntimeDiagnostic;
import io.knotra.RuntimeSnapshot;

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
 * FAILED 的组件不会被自动重试，需通过 {@link #retry(String)} 显式恢复。
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
            MutationResult<ContextHandle> result = runtime.mutate(mutation ->
                    mutation.childContext(runtime.rootContext(), loaderId));
            if (!result.committed()) {
                throw new IllegalArgumentException("owned base context creation was rejected");
            }
            this.baseContext = result.value();
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
     * 本批已挂载的句柄与新 Context 按 LIFO 回滚；FAILED 组件不做自动重试。
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
     * <p>组件处于 FAILED 时触发 ComponentHandle.retry 重新激活；条目对应的
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
                        entry.context().contextInfo().canonicalPath(),
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

    /** 阻塞等待 {@link #closeAsync()} 完成；清理异常直接上抛，可再次调用重试。 */
    @Override
    public void close() throws Exception {
        closeAsync().toCompletableFuture().get();
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
            addFailedDiagnostics(prepared, diagnostics);
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
                ComponentState state = entry.handle().retry()
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
        return runtime.snapshot().contexts().stream()
                .filter(context -> context.contextId().equals(runtime.rootContext().contextId()))
                .findFirst()
                .map(context -> context.state() == ContextState.DISPOSING
                        || context.state() == ContextState.DISPOSED)
                .orElseGet(() -> runtime.rootContext().state() == ContextState.DISPOSING
                        || runtime.rootContext().state() == ContextState.DISPOSED);
    }

    /** 运行时整体关闭已经接管本次释放时为 true；此时等待其完成而不是重复报错。 */
    private boolean runtimeOwnsDisposalNow() {
        ContextState baseState = baseContext.state();
        if (baseState != ContextState.DISPOSING && baseState != ContextState.DISPOSED) {
            return false;
        }
        String rootId = runtime.rootContext().contextId();
        return runtime.snapshot().contexts().stream()
                .filter(context -> context.contextId().equals(rootId))
                .findFirst()
                .map(context -> context.state() == ContextState.DISPOSING
                        || context.state() == ContextState.DISPOSED)
                .orElse(true);
    }

    /** 有界等待运行时接管的句柄释放完成；未在时限内到达 DISPOSED 视为失败。 */
    private boolean awaitRuntimeOwnedHandleDisposal(ComponentHandle<?> handle) {
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

        Map<FactoryRef, ResolvedComponentDefinition> definitions = new LinkedHashMap<>();
        for (PreparedEntry entry : raw.values()) {
            if (definitions.containsKey(entry.ref())) {
                continue;
            }
            try {
                Optional<ResolvedComponentDefinition> definition = resolver.resolve(entry.ref());
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
            ResolvedComponentDefinition definition = definitions.get(candidate.ref());
            Object config;
            try {
                config = definition.normalizeConfig(candidate.config());
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
        RuntimeSnapshot snapshot = runtime.snapshot();
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
        Map<String, RuntimeSnapshot.ComponentSnapshot> mounts = new LinkedHashMap<>();
        for (RuntimeSnapshot.ComponentSnapshot component : snapshot.components()) {
            mounts.put(component.contextId() + "/" + component.mountId(), component);
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
            RuntimeSnapshot.ComponentSnapshot mounted = existing == null
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
        RuntimeSnapshot snapshot = runtime.snapshot();
        Set<String> liveContextIds = snapshot.contexts().stream()
                .filter(context -> context.state() == ContextState.ACTIVE
                        || context.state() == ContextState.FAILED)
                .map(RuntimeSnapshot.ContextSnapshot::contextId)
                .collect(LinkedHashSet::new, LinkedHashSet::add, LinkedHashSet::addAll);
        Set<String> liveHandleIds = snapshot.components().stream()
                .filter(component -> component.state() != ComponentState.DISPOSED)
                .map(RuntimeSnapshot.ComponentSnapshot::handleId)
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

        MountAttempt mounted = mountOne(next, old.context(), diagnostics);
        if (mounted != null) {
            register(mounted);
            if (mounted.handle().state() == ComponentState.FAILED) {
                diagnostics.add(LoaderDiagnostic.of(
                        LoaderDiagnosticCode.ACTIVATION_FAILED,
                        next.path(),
                        "replacement implementation failed to activate"));
            }
            return true;
        }

        PreparedEntry fallback = new PreparedEntry(
                old.path(),
                old.name(),
                null,
                old.definition(),
                old.config());
        List<LoaderDiagnostic> compensation = new ArrayList<>();
        MountAttempt restored = mountOne(fallback, old.context(), compensation);
        if (restored != null) {
            register(restored);
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
                MountAttempt attempt = mountOne(entry, context, diagnostics);
                if (attempt == null) {
                    rollbackAdd(mounted, created, diagnostics);
                    return false;
                }
                ManagedEntry managed = register(attempt);
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
        MutationResult<Void> result = runtime.mutate(mutation -> {
            for (String path : missing) {
                ContextHandle reusable = contexts.get(path);
                if (reusable != null) {
                    if (reusable.state() != ContextState.ACTIVE) {
                        throw new IllegalStateException("managed context is not active: " + path);
                    }
                    provisional.put(path, reusable);
                    continue;
                }
                ContextHandle parent = parentContext(path, provisional);
                ContextHandle child = mutation.childContext(parent, lastSegment(path));
                provisional.put(path, child);
                created.put(path, child);
            }
            return null;
        });
        if (!result.committed()) {
            addCoreDiagnostics(LoaderDiagnosticCode.STRUCTURE_REJECTED, missing.getFirst(), diagnostics,
                    result.diagnostics());
            created.clear();
            return false;
        }
        if (!await(result.settlement())) {
            diagnostics.add(LoaderDiagnostic.of(
                    LoaderDiagnosticCode.STRUCTURE_REJECTED,
                    missing.getFirst(),
                    "one or more contexts failed to settle"));
            List<LoaderDiagnostic> compensation = new ArrayList<>();
            rollbackContexts(created, compensation);
            diagnostics.addAll(compensation);
            created.clear();
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
     * 执行一次受控挂载并等待 Activation 结算。除异常外还校验策略确实使用了
     * 分配的槽位：返回句柄必须绑定在分配的 contextId 与 mountId 上；越界
     * 句柄立即释放并整批拒绝，防止策略把组件挂到 Loader 记账之外。
     */
    private MountAttempt mountOne(
            PreparedEntry entry,
            ContextHandle context,
            List<LoaderDiagnostic> diagnostics) {
        if (context == null || context.state() != ContextState.ACTIVE) {
            diagnostics.add(LoaderDiagnostic.of(
                    LoaderDiagnosticCode.STRUCTURE_REJECTED,
                    entry.path(),
                    "mount context is not active"));
            return null;
        }
        try {
            ControlledMountContext mountContext = new AllocatedMountContext(
                    runtime, context, entry.path());
            ComponentHandle<?> handle = entry.definition()
                    .mountStrategy()
                    .mount(mountContext, entry.config())
                    .toCompletableFuture()
                    .get();
            if (handle == null) {
                diagnostics.add(LoaderDiagnostic.of(
                        LoaderDiagnosticCode.STRUCTURE_REJECTED,
                        entry.path(),
                        "controlled mount returned no component handle"));
                return null;
            }
            if (!context.contextId().equals(handle.contextId())
                    || !entry.path().equals(handle.mountId())) {
                diagnostics.add(LoaderDiagnostic.of(
                        LoaderDiagnosticCode.STRUCTURE_REJECTED,
                        entry.path(),
                        "controlled mount returned a handle outside its allocated slot"));
                disposeHandle(entry.path(), handle, diagnostics);
                return null;
            }
            handle.whenSettled().toCompletableFuture().get();
            return new MountAttempt(
                    entry.path(),
                    entry.name(),
                    context,
                    handle,
                    entry.definition(),
                    entry.config());
        } catch (ControlledMountException error) {
            addCoreDiagnostics(
                    LoaderDiagnosticCode.STRUCTURE_REJECTED,
                    entry.path(),
                    diagnostics,
                    error.diagnostics());
        } catch (Exception error) {
            diagnostics.add(LoaderDiagnostic.of(
                    LoaderDiagnosticCode.STRUCTURE_REJECTED,
                    entry.path(),
                    safeError(error)));
        }
        return null;
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
                        .reconfigure(managed.handle(), desiredEntry.config())
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
     * 汇总仍处于 FAILED 的期望条目。Loader 刻意不做自动重试：失败的 start()
     * 可能是持续故障，自动重试会在每次 reconcile 中放大副作用，因此收敛语义
     * 要求调用方显式 retry。
     */
    private void addFailedDiagnostics(
            PreparedTree desired,
            List<LoaderDiagnostic> diagnostics) {
        for (String path : desired.paths()) {
            ManagedEntry entry = current.get(path);
            if (entry != null && entry.handle().state() == ComponentState.FAILED) {
                diagnostics.add(LoaderDiagnostic.of(
                        LoaderDiagnosticCode.ACTIVATION_FAILED,
                        path,
                        "desired component is FAILED; call retry(path) explicitly"));
            }
        }
    }

    /**
     * 通过运行时事务释放单个句柄并等待结算；若运行时整体关闭已经接管释放，
     * 只等待其完成而不把竞态重复报告为失败。
     */
    private boolean disposeHandle(
            String path,
            ComponentHandle<?> handle,
            List<LoaderDiagnostic> diagnostics) {
        if (handle.state() == ComponentState.DISPOSED) {
            return true;
        }
        MutationResult<Void> result = runtime.mutate(mutation -> {
            mutation.dispose(handle);
            return null;
        });
        if (!result.committed()) {
            if (runtimeOwnsDisposalNow()
                    && awaitRuntimeOwnedHandleDisposal(handle)) {
                return true;
            }
            addCoreDiagnostics(LoaderDiagnosticCode.TEARDOWN_FAILED, path, diagnostics,
                    result.diagnostics());
            return false;
        }
        try {
            result.settlement().toCompletableFuture().get();
        } catch (Exception error) {
            if (runtimeOwnsDisposalNow()
                    && awaitRuntimeOwnedHandleDisposal(handle)) {
                return true;
            }
            diagnostics.add(LoaderDiagnostic.of(
                    LoaderDiagnosticCode.TEARDOWN_FAILED,
                    path,
                    safeError(error)));
            return false;
        }
        if (handle.state() != ComponentState.DISPOSED) {
            diagnostics.add(LoaderDiagnostic.of(
                    LoaderDiagnosticCode.TEARDOWN_FAILED,
                    path,
                    "component cleanup did not reach DISPOSED"));
            return false;
        }
        return true;
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
        MutationResult<Void> result = runtime.mutate(mutation -> {
            mutation.dispose(context);
            return null;
        });
        if (!result.committed()) {
            if (runtimeOwnsDisposalNow()
                    && awaitRuntimeOwnedContextDisposal(context)) {
                prune(path);
                publish(latestDiagnostics);
                return true;
            }
            addCoreDiagnostics(LoaderDiagnosticCode.TEARDOWN_FAILED, path, diagnostics,
                    result.diagnostics());
            return false;
        }
        try {
            result.settlement().toCompletableFuture().get();
        } catch (Exception error) {
            if (runtimeOwnsDisposalNow()
                    && awaitRuntimeOwnedContextDisposal(context)) {
                prune(path);
                publish(latestDiagnostics);
                return true;
            }
            diagnostics.add(LoaderDiagnostic.of(
                    LoaderDiagnosticCode.TEARDOWN_FAILED,
                    path,
                    safeError(error)));
            return false;
        }
        if (context.state() != ContextState.DISPOSED) {
            diagnostics.add(LoaderDiagnostic.of(
                    LoaderDiagnosticCode.TEARDOWN_FAILED,
                    path,
                    "context cleanup reached " + context.state()));
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

    private boolean await(CompletionStage<Void> settlement) {
        try {
            settlement.toCompletableFuture().get();
            return true;
        } catch (Exception error) {
            return false;
        }
    }

    /** 把 Core 事务诊断映射进 Loader 结果；核心未给出诊断时提供兜底文案。 */
    private void addCoreDiagnostics(
            LoaderDiagnosticCode fallback,
            String path,
            List<LoaderDiagnostic> diagnostics,
            List<RuntimeDiagnostic> values) {
        if (values.isEmpty()) {
            diagnostics.add(LoaderDiagnostic.of(fallback, path, "runtime mutation was rejected"));
            return;
        }
        for (RuntimeDiagnostic value : values) {
            diagnostics.add(LoaderDiagnostic.of(
                    mapCode(value.code(), fallback),
                    path,
                    value.message()));
        }
    }

    /** Core 诊断码到 Loader 诊断码的稳定映射；无法识别时回退到调用方指定的码。 */
    private LoaderDiagnosticCode mapCode(
            DiagnosticCode code,
            LoaderDiagnosticCode fallback) {
        return switch (code) {
            case ACTIVATION_FAILED -> LoaderDiagnosticCode.ACTIVATION_FAILED;
            case CLEANUP_FAILED, ROLLBACK_FAILED -> LoaderDiagnosticCode.TEARDOWN_FAILED;
            case INVALID_CONFIG, MISSING_CAPABILITY, CAPABILITY_SLOT_OCCUPIED,
                    CAPABILITY_TYPE_CONFLICT, BINDING_CYCLE, NON_CONVERGENT_RECONCILE,
                    INVALID_LIFECYCLE_OPERATION, INVALID_MOUNT_ID -> LoaderDiagnosticCode.STRUCTURE_REJECTED;
        };
    }

    /**
     * 深度优先展平声明树：归一化路径、校验重复与越界。相对单段路径拼接在
     * 父路径之下；null 配置折算为 NoConfig.INSTANCE，交给定义的 schema 归一化。
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
                    entry.config() == null ? NoConfig.INSTANCE : entry.config()));
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

    /** 取根因消息用于诊断，避免把包装异常的无信息消息写进结果。 */
    private static String safeError(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank()
                ? current.getClass().getName()
                : message;
    }

    /** 期望条目完成准备后的形态：归一化路径 + 解析出的定义 + 归一化配置。 */
    private record PreparedEntry(
            String path,
            String name,
            FactoryRef ref,
            ResolvedComponentDefinition definition,
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
            ComponentHandle<?> handle,
            ResolvedComponentDefinition definition,
            Object config) {
    }

    /** Loader 对单个受管路径的记账：Context、句柄、当前定义与归一化配置。 */
    private static final class ManagedEntry {
        private final String path;
        private final String name;
        private final ContextHandle context;
        private final ComponentHandle<?> handle;
        private final ResolvedComponentDefinition definition;
        private final Object config;

        private ManagedEntry(
                String path,
                String name,
                ContextHandle context,
                ComponentHandle<?> handle,
                ResolvedComponentDefinition definition,
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

        private ComponentHandle<?> handle() {
            return handle;
        }

        private ResolvedComponentDefinition definition() {
            return definition;
        }

        private Object config() {
            return config;
        }

        private ManagedEntry withHandle(ComponentHandle<?> replacement) {
            return new ManagedEntry(path, name, context, replacement, definition, config);
        }

        private ManagedEntry withDefinitionAndConfig(
                ResolvedComponentDefinition replacementDefinition,
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
