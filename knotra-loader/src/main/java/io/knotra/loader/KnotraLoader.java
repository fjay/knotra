package io.knotra.loader;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.LongSupplier;

import io.knotra.ComponentState;
import io.knotra.ContextHandle;
import io.knotra.ContextState;
import io.knotra.KnotraRuntime;
import io.knotra.PendingOperationsSnapshot;
import io.knotra.RuntimeSnapshot;
import io.knotra.SettlementReport;
import io.knotra.TransactionReceipt;
import io.knotra.TransactionRejectedException;

import static io.knotra.loader.LoaderMounter.MountResult;
import static io.knotra.loader.LoaderStateStore.ManagedEntry;

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
    /** 记账、诊断与对外视图的唯一存储。 */
    private final LoaderStateStore state = new LoaderStateStore();
    /** Loader 级超时集合；包内可注入以便确定性测试。 */
    private volatile LoaderTimeouts timeouts = LoaderTimeouts.DEFAULTS;
    private final LoaderDisposer disposer;
    private final LoaderMounter mounter;
    private final DesiredTreePreparer preparer;
    private final LongSupplier ticker;
    private final LoaderOperationTracker operationTracker;
    private CompletableFuture<Void> closeAttempt;

    private KnotraLoader(
            KnotraRuntime runtime,
            ContextHandle baseContext,
            ComponentFactoryResolver resolver,
            boolean owned,
            LongSupplier ticker) {
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
        this.disposer = new LoaderDisposer(runtime, this.baseContext, state, () -> timeouts);
        this.mounter = new LoaderMounter(runtime, state, disposer, () -> timeouts);
        this.preparer = new DesiredTreePreparer(runtime, this.baseContext, resolver, state);
        this.ticker = ticker;
        this.operationTracker = new LoaderOperationTracker(ticker);
        state.publish(List.of());
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
        return new KnotraLoader(runtime, null, resolver, true, System::nanoTime);
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
        return new KnotraLoader(runtime, context, resolver, false, System::nanoTime);
    }

    /**
     * 包内测试注入点：替换挂起诊断使用的单调时钟。ticker 只影响 age 与剩余
     * deadline 的计算，不参与任何行为分支；负值或回退值会被钳制为非负 age。
     */
    static KnotraLoader over(
            KnotraRuntime runtime,
            ContextHandle context,
            ComponentFactoryResolver resolver,
            LongSupplier ticker) {
        return new KnotraLoader(runtime, context, resolver, false,
                Objects.requireNonNull(ticker, "ticker"));
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
        this.timeouts = this.timeouts.withMountTimeouts(settlementTimeout, recoveryTimeout);
    }

    /** 包内测试注入点：整体替换超时集合，覆盖运行时接管释放与 Context 轮询间隔。 */
    void timeoutsForTesting(LoaderTimeouts replacement) {
        this.timeouts = Objects.requireNonNull(replacement, "replacement");
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
        if (state.isClosed()) {
            return CompletableFuture.completedFuture(finish(
                    false,
                    List.of(),
                    List.of(LoaderDiagnostic.of(
                            LoaderDiagnosticCode.CLOSED,
                            "",
                            "loader is closed"))));
        }
        return enqueue("reconcile", operation -> performReconcile(desired, operation));
    }

    /**
     * 阻塞执行 {@link #reconcileAsync(ComponentTree)}。
     *
     * <p>协调器上的运行时异常原样抛出，其他异常包装为 IllegalStateException；
     * 等待被中断时恢复中断标记并抛出 IllegalStateException。
     */
    public ReconcileResult reconcile(ComponentTree desired) {
        return joinBlocking(reconcileAsync(desired), "reconcile");
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
        String normalized = DesiredTreePreparer.normalizePath(path, "");
        if (state.isClosed()) {
            return CompletableFuture.completedFuture(finish(
                    false,
                    List.of(),
                    List.of(LoaderDiagnostic.of(
                            LoaderDiagnosticCode.CLOSED,
                            normalized,
                            "loader is closed"))));
        }
        return enqueue("retry", operation -> performRetry(normalized, operation));
    }

    /** 阻塞执行 {@link #retryAsync(String)}；异常约定与 {@link #reconcile(ComponentTree)} 一致。 */
    public ReconcileResult retry(String path) {
        return joinBlocking(retryAsync(path), "retry");
    }

    /**
     * 返回 Loader 当前状态的不可变快照。
     *
     * <p>快照只包含数据：条目路径、Context 与挂载标识、实现身份、配置代际、
     * 组件状态与最近诊断，不暴露存活组件实例或内部记账结构。可在协调器
     * 之外随时调用，单次读取最近一次发布的代际一致视图。
     */
    public LoaderSnapshot snapshot() {
        LoaderStateStore.LoaderView local = state.view();
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
                local.diagnostics());
    }

    /**
     * 返回 Loader 排空过程中的挂起操作快照（point-in-time，无副作用）。
     *
     * <p>该方法不进入协调器，可在任意阶段、任意线程（包括协调器线程）调用。
     * 操作条目来自协调器队列与各收敛阶段的短锁采样：包含 reconcile / retry /
     * close 的当前阶段、稳定目标标识与有界 detail，不引用组件、句柄、Context、
     * 工厂、配置、期望树或 ClassLoader。
     *
     * <p>closeRequested 一旦 closeAsync 已请求即为 true 并保持粘性：close 尝试
     * 失败后处于可重试状态时仍视为“已请求关闭”，与 {@link #snapshot()} 的
     * closed 语义一致。各操作来自叶子锁的独立采样，不承诺全局原子性；排序
     * 与截断由公共 DTO 构造器统一完成。
     */
    public PendingOperationsSnapshot pendingOperations() {
        return operationTracker.snapshot(ticker.getAsLong(), state.isClosed());
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
            state.markClosed();
            closeAttempt = enqueue("close", this::performClose);
            return closeAttempt;
        }
    }

    /** 阻塞等待 {@link #closeAsync()} 完成；失败以 CompletionException 上抛，可再次调用重试。 */
    @Override
    public void close() {
        closeAsync().toCompletableFuture().join();
    }

    /**
     * 阻塞等待协调器结果：协调器上的运行时异常原样抛出，其余包装为
     * IllegalStateException；被中断时恢复中断标记并按操作名报告。
     */
    private static <T> T joinBlocking(CompletionStage<T> stage, String operationName) {
        try {
            return stage.toCompletableFuture().get();
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof RuntimeException runtimeError) {
                throw runtimeError;
            }
            throw new IllegalStateException(cause == null ? error.getMessage() : cause.getMessage(), cause);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(operationName + " was interrupted", error);
        }
    }

    /**
     * 把操作排入单线程协调器。coordinatorThread 用于识别协调器线程本身，
     * 拒绝受控策略或组件代码在协调器线程内重入调用 Loader，防止自等待死锁。
     */
    private <T> CompletableFuture<T> enqueue(
            String operationType,
            Function<LoaderOperationTracker.Operation, T> operation) {
        rejectReentrant("coordinator operation");
        CompletableFuture<T> outcome = new CompletableFuture<>();
        LoaderOperationTracker.Operation tracked =
                operationTracker.begin(operationType, loaderId);
        try {
            coordinator.execute(() -> {
                try {
                    Thread thread = Thread.currentThread();
                    Thread previous = coordinatorThread.getAndSet(thread);
                    if (previous != null && previous != thread) {
                        outcome.completeExceptionally(new IllegalStateException(
                                "loader coordinator thread changed"));
                        return;
                    }
                    tracked.running();
                    outcome.complete(operation.apply(tracked));
                } catch (Throwable error) {
                    outcome.completeExceptionally(error);
                } finally {
                    tracked.complete();
                }
            });
        } catch (RuntimeException error) {
            outcome.completeExceptionally(error);
            tracked.complete();
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
    private ReconcileResult performReconcile(
            ComponentTree desired,
            LoaderOperationTracker.Operation operation) {
        List<LoaderDiagnostic> diagnostics = new ArrayList<>();
        List<ReconcileResult.Change> changes = new ArrayList<>();
        boolean proposed = true;
        try {
            operation.phase(LoaderOperationTracker.Phase.PREPARE,
                    loaderId, "", "", Duration.ZERO);
            PreparedTree prepared = preparer.prepare(desired, diagnostics);
            if (!diagnostics.isEmpty()) {
                return finish(false, changes, diagnostics);
            }

            pruneExternallyDisposed();
            if (!settleFailedDesiredContexts(prepared, diagnostics, operation)) {
                return finish(false, changes, diagnostics);
            }
            if (!removeMissing(prepared, changes, diagnostics, operation)) {
                return finish(false, changes, diagnostics);
            }
            if (!replaceChangedFactories(prepared, changes, diagnostics, operation)) {
                return finish(false, changes, diagnostics);
            }
            if (!addMissing(prepared, changes, diagnostics, operation)) {
                return finish(false, changes, diagnostics);
            }
            updateConfigs(prepared, changes, diagnostics, operation);
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
    private ReconcileResult performRetry(
            String path,
            LoaderOperationTracker.Operation operation) {
        List<LoaderDiagnostic> diagnostics = new ArrayList<>();
        List<ReconcileResult.Change> changes = new ArrayList<>();
        ManagedEntry entry = state.entry(path);
        if (entry == null) {
            diagnostics.add(LoaderDiagnostic.of(
                    LoaderDiagnosticCode.INVALID_TREE,
                    path,
                    "path is not managed or has no retryable activation"));
            return finish(false, changes, diagnostics);
        }

        if (entry.handle().state() == ComponentState.FAILED) {
            try {
                operation.phase(
                        LoaderOperationTracker.Phase.RETRY_ACTIVATION,
                        entry.handle().handleId(),
                        path,
                        entry.handle().handleId(),
                        Duration.ZERO);
                ComponentState retried = entry.handle().retryAsync()
                        .toCompletableFuture().get();
                state.put(entry.withHandle(entry.handle()));
                changes.add(ReconcileResult.Change.of(
                        ReconcileResult.ChangeType.RETRIED,
                        path));
                if (retried == ComponentState.FAILED) {
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
            if (disposer.disposeContext(path, diagnostics, operation)) {
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
    private Void performClose(LoaderOperationTracker.Operation operation) {
        List<LoaderDiagnostic> diagnostics = new ArrayList<>();
        pruneExternallyDisposed();
        if (disposer.isRuntimeClosingBase()) {
            state.clear();
            state.publish(diagnostics);
            coordinator.shutdown();
            return null;
        }

        boolean converged = true;
        if (owned) {
            converged &= disposer.disposeContext("", diagnostics, operation);
        } else {
            for (String path : topLevelPaths()) {
                converged &= disposer.disposeContext(path, diagnostics, operation);
            }
        }
        if (converged) {
            state.clear();
            state.publish(diagnostics);
            coordinator.shutdown();
            return null;
        }

        state.publish(diagnostics);
        throw new IllegalStateException("loader close did not settle");
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

        state.pruneDisposed(liveContextIds, liveHandleIds);
        state.republish();
    }

    /**
     * 期望仍然存在的路径若其 Context 已 FAILED，先释放该子树，后续阶段才能
     * 在下一次事务中重建干净的 Context。
     */
    private boolean settleFailedDesiredContexts(
            PreparedTree desired,
            List<LoaderDiagnostic> diagnostics,
            LoaderOperationTracker.Operation operation) {
        for (String path : desired.paths()) {
            if (state.context(path) != null
                    && state.context(path).state() == ContextState.FAILED) {
                if (!disposer.disposeContext(path, diagnostics, operation)) {
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
            List<LoaderDiagnostic> diagnostics,
            LoaderOperationTracker.Operation operation) {
        List<String> missing = state.contextPaths().stream()
                .filter(path -> !desired.paths().contains(path))
                .filter(this::isRemovalRoot)
                .toList();
        for (String path : missing) {
            if (disposer.disposeContext(path, diagnostics, operation)) {
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
        String parent = DesiredTreePreparer.parentPath(path);
        return parent.isEmpty()
                || (state.hasContext(parent)
                        && !state.context(parent).state().equals(ContextState.DISPOSED)
                        && !isPathDesiredRemovalCandidate(parent));
    }

    private boolean isPathDesiredRemovalCandidate(String parent) {
        return !state.hasEntry(parent) && !state.hasContext(parent);
    }

    /**
     * 实现身份（而非工厂引用）变化的条目必须整句柄替换：引用相同但指纹不同
     * 也视为新实现。任一替换失败都会中止后续阶段，防止半棵树跨实现版本。
     */
    private boolean replaceChangedFactories(
            PreparedTree desired,
            List<ReconcileResult.Change> changes,
            List<LoaderDiagnostic> diagnostics,
            LoaderOperationTracker.Operation operation) {
        for (String path : desired.paths()) {
            ManagedEntry old = state.entry(path);
            PreparedEntry next = desired.entry(path);
            if (old == null || old.definition().identity().equals(next.definition().identity())) {
                continue;
            }
            if (!replaceEntry(old, next, diagnostics, operation)) {
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
            List<LoaderDiagnostic> diagnostics,
            LoaderOperationTracker.Operation operation) {
        if (!disposer.disposeHandle(old.path(), old.handle(), diagnostics, operation)) {
            return false;
        }
        state.remove(old.path());

        MountResult mounted = mounter.mount(next, old.context(), diagnostics, operation);
        if (mounted.mounted()) {
            state.register(mounted.attempt());
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

        PreparedEntry fallback = PreparedEntry.fromManaged(old);
        List<LoaderDiagnostic> compensation = new ArrayList<>();
        MountResult restored = mounter.mount(fallback, old.context(), compensation, operation);
        if (restored.mounted()) {
            state.register(restored.attempt());
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
            List<LoaderDiagnostic> diagnostics,
            LoaderOperationTracker.Operation operation) {
        List<String> missing = desired.paths().stream()
                .filter(path -> !state.hasEntry(path))
                .toList();
        if (missing.isEmpty()) {
            return true;
        }

        Map<String, ContextHandle> created = new LinkedHashMap<>();
        if (!createMissingContexts(missing, created, diagnostics, operation)) {
            return false;
        }

        List<ManagedEntry> mounted = new ArrayList<>();
        try {
            for (String path : missing) {
                PreparedEntry entry = desired.entry(path);
                ContextHandle context = state.context(path);
                MountResult attempt = mounter.mount(entry, context, diagnostics, operation);
                if (attempt.committedUnsettled()) {
                    // 本批存在已提交但未收敛的挂载：回滚可能无法完成，
                    // 保留全部记账，等待后续 reconcile/close 继续收敛。
                    changes.add(ReconcileResult.Change.of(
                            ReconcileResult.ChangeType.BLOCKED,
                            path));
                    return false;
                }
                if (!attempt.mounted()) {
                    mounter.rollbackAdd(mounted, created, diagnostics, operation);
                    return false;
                }
                ManagedEntry managed = state.register(attempt.attempt());
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
            mounter.rollbackAdd(mounted, created, diagnostics, operation);
            return false;
        } finally {
            state.republish();
        }
    }

    /**
     * 在单个运行时事务中按父先子后的顺序创建全部缺失 Context；事务被拒绝或
     * 结算失败时立即回滚已创建的 Context。
     */
    private boolean createMissingContexts(
            List<String> missing,
            Map<String, ContextHandle> created,
            List<LoaderDiagnostic> diagnostics,
            LoaderOperationTracker.Operation operation) {
        Map<String, ContextHandle> provisional = new LinkedHashMap<>();
        TransactionReceipt<ContextHandle> receipt;
        try {
            receipt = runtime.advanced().transact(transaction -> {
                ContextHandle lastContext = baseContext;
                for (String path : missing) {
                    ContextHandle reusable = state.context(path);
                    if (reusable != null) {
                        if (reusable.state() != ContextState.ACTIVE) {
                            throw new IllegalStateException("managed context is not active: " + path);
                        }
                        provisional.put(path, reusable);
                        lastContext = reusable;
                        continue;
                    }
                    ContextHandle parent = parentContext(path, provisional);
                    ContextHandle child = transaction.childContext(
                            parent, DesiredTreePreparer.lastSegment(path));
                    provisional.put(path, child);
                    created.put(path, child);
                    lastContext = child;
                }
                return lastContext;
            });
        } catch (TransactionRejectedException rejection) {
            LoaderDiagnosticMapper.addCoreDiagnostics(
                    LoaderDiagnosticCode.STRUCTURE_REJECTED,
                    missing.getFirst(), diagnostics, rejection.diagnostics());
            created.clear();
            return false;
        }
        SettlementReport report;
        try {
            operation.phase(
                    LoaderOperationTracker.Phase.CONTEXT_SETTLEMENT,
                    loaderId,
                    missing.isEmpty() ? "" : missing.getFirst(),
                    "",
                    timeouts.settlement());
            report = receipt.awaitSettled(timeouts.settlement());
        } catch (Exception error) {
            diagnostics.add(LoaderDiagnostic.of(
                    LoaderDiagnosticCode.STRUCTURE_REJECTED,
                    missing.getFirst(),
                    "context settlement failed: " + safeError(error)));
            mounter.rollbackCreatedContexts(created, diagnostics, operation);
            return false;
        }
        if (settlementIndicatesFailure(report)) {
            LoaderDiagnosticMapper.addSettlementDiagnostics(
                    LoaderDiagnosticCode.STRUCTURE_REJECTED,
                    missing.getFirst(),
                    diagnostics,
                    report);
            mounter.rollbackCreatedContexts(created, diagnostics, operation);
            return false;
        }
        state.putContexts(provisional);
        state.republish();
        return true;
    }

    private ContextHandle parentContext(
            String path,
            Map<String, ContextHandle> provisional) {
        String parentPath = DesiredTreePreparer.parentPath(path);
        if (parentPath.isEmpty()) {
            return baseContext;
        }
        ContextHandle provisionalParent = provisional.get(parentPath);
        if (provisionalParent != null) {
            return provisionalParent;
        }
        ContextHandle parent = state.context(parentPath);
        if (parent == null || parent.state() != ContextState.ACTIVE) {
            throw new IllegalStateException("parent context is unavailable: " + parentPath);
        }
        return parent;
    }

    /**
     * 实现身份不变的配置变化走重配置而不是替换。先更新记账中的期望配置：
     * 即使句柄当前 FAILED，也保留最新配置供后续显式 retry 使用；只有非
     * FAILED 句柄才立即调用定义的重配置策略。
     */
    private void updateConfigs(
            PreparedTree desired,
            List<ReconcileResult.Change> changes,
            List<LoaderDiagnostic> diagnostics,
            LoaderOperationTracker.Operation operation) {
        for (String path : desired.paths()) {
            ManagedEntry managed = state.entry(path);
            PreparedEntry desiredEntry = desired.entry(path);
            if (managed == null
                    || !managed.definition().identity().equals(
                            desiredEntry.definition().identity())) {
                continue;
            }
            if (Objects.equals(managed.config(), desiredEntry.typedConfig())) {
                continue;
            }

            ManagedEntry latest = managed.withDefinitionAndConfig(
                    desiredEntry.definition(),
                    desiredEntry.typedConfig());
            state.put(latest);
            if (managed.handle().state() == ComponentState.FAILED) {
                diagnostics.add(LoaderDiagnostic.of(
                        LoaderDiagnosticCode.ACTIVATION_FAILED,
                        path,
                        "configuration is stored but the failed implementation was not retried"));
                continue;
            }
            try {
                String handleId = managed.handle().handleId();
                operation.phase(
                        LoaderOperationTracker.Phase.RECONFIGURE,
                        handleId,
                        path,
                        handleId,
                        Duration.ZERO);
                ComponentState state = desiredEntry.definition()
                        .reconfigureStrategy()
                        .reconfigureAsync(managed.handle(), desiredEntry.typedConfig())
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
            ManagedEntry entry = state.entry(path);
            if (entry == null) {
                continue;
            }
            ComponentState componentState = entry.handle().state();
            if (componentState == ComponentState.FAILED) {
                diagnostics.add(LoaderDiagnostic.of(
                        LoaderDiagnosticCode.ACTIVATION_FAILED,
                        path,
                        "desired component is FAILED; call retry(path) explicitly"));
            } else if (componentState != ComponentState.ACTIVE
                    && componentState != ComponentState.WAITING) {
                diagnostics.add(LoaderDiagnostic.of(
                        LoaderDiagnosticCode.SETTLEMENT_UNSETTLED,
                        path,
                        "desired component has not settled: state=" + componentState
                                + ", goal=" + entry.handle().goal()));
            }
        }
    }

    private List<String> topLevelPaths() {
        return state.contextPaths().stream()
                .filter(path -> DesiredTreePreparer.parentPath(path).isEmpty())
                .toList();
    }

    /** 汇总诊断、发布视图并构造结果；converged 要求无任何诊断。 */
    private ReconcileResult finish(
            boolean proposed,
            List<ReconcileResult.Change> changes,
            List<LoaderDiagnostic> diagnostics) {
        List<LoaderDiagnostic> copied = List.copyOf(diagnostics).stream().sorted().toList();
        state.publish(copied);
        return new ReconcileResult(proposed && copied.isEmpty(), changes, copied);
    }

    /**
     * Loader 只把 FAILED 挂载视为 settlement 失败；owned child 与有意替换的旧
     * child 会以 DISPOSED 出现在报告中，那是预期结果而不是失败。
     */
    static boolean settlementIndicatesFailure(SettlementReport report) {
        return report != null && report.hasFailedMounts();
    }

    /** 有界根因描述；对恶意 Throwable 的防护见 {@link LoaderErrors}。 */
    private static String safeError(Throwable error) {
        return LoaderErrors.safe(error);
    }
}
