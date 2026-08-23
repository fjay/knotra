package io.knotra.loader;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;

import io.knotra.ComponentState;
import io.knotra.ContextHandle;
import io.knotra.ContextState;
import io.knotra.KnotraRuntime;
import io.knotra.MountHandle;
import io.knotra.SettlementReport;

import static io.knotra.loader.LoaderStateStore.ManagedEntry;

/**
 * 受控挂载协作类：执行单次受控挂载、收口 commit 后失败路径，并提供新增批次
 * 失败时的 LIFO 回滚。任何 commit 后的失败都不会遗留未跟踪挂载。
 *
 * <p>挂载策略执行（用户回调）、挂载 settlement 与补偿释放的同步等待会推进显式传入的
 * 令牌，阶段信息只用于诊断，不影响挂载行为。</p>
 */
final class LoaderMounter {

    private final KnotraRuntime runtime;
    private final LoaderStateStore state;
    private final LoaderDisposer disposer;
    private final Supplier<LoaderTimeouts> timeouts;

    LoaderMounter(
            KnotraRuntime runtime,
            LoaderStateStore state,
            LoaderDisposer disposer,
            Supplier<LoaderTimeouts> timeouts) {
        this.runtime = runtime;
        this.state = state;
        this.disposer = disposer;
        this.timeouts = timeouts;
    }

    /**
     * 执行一次受控挂载并消费其 settlement 报告。除异常外还校验策略确实使用了
     * 分配的槽位：返回句柄必须绑定在分配的 contextId 与 mountId 上；越界
     * 句柄立即释放并整批拒绝，防止策略把挂载放到 Loader 记账之外。
     */
    MountResult mount(
            PreparedEntry entry,
            ContextHandle context,
            List<LoaderDiagnostic> diagnostics,
            LoaderOperationTracker.Operation operation) {
        if (context == null || context.state() != ContextState.ACTIVE) {
            diagnostics.add(LoaderDiagnostic.of(
                    LoaderDiagnosticCode.STRUCTURE_REJECTED,
                    entry.path(),
                    "mount context is not active"));
            return MountResult.REJECTED;
        }
        operation.phase(
                LoaderOperationTracker.Phase.MOUNT_EXECUTION,
                entry.path(),
                entry.path(),
                "",
                Duration.ZERO);
        AllocatedMountContext mountContext = new AllocatedMountContext(
                runtime, context, entry.path(),
                timeouts.get().settlement(), timeouts.get().recovery(), operation);
        MountHandle handle = null;
        boolean rejected = false;
        try {
            handle = entry.definition()
                    .mountStrategy()
                    .mountAsync(mountContext, entry.typedConfig())
                    .toCompletableFuture()
                    .get();
        } catch (ControlledMountException error) {
            LoaderDiagnosticMapper.addCoreDiagnostics(
                    LoaderDiagnosticCode.STRUCTURE_REJECTED,
                    entry.path(),
                    diagnostics,
                    error.diagnostics());
            rejected = true;
        } catch (ExecutionException error) {
            if (error.getCause() instanceof ControlledMountException controlled) {
                LoaderDiagnosticMapper.addCoreDiagnostics(
                        LoaderDiagnosticCode.STRUCTURE_REJECTED,
                        entry.path(),
                        diagnostics,
                        controlled.diagnostics());
            } else {
                diagnostics.add(LoaderDiagnostic.of(
                        LoaderDiagnosticCode.STRUCTURE_REJECTED,
                        entry.path(),
                        LoaderErrors.safe(error)));
            }
            rejected = true;
        } catch (Exception error) {
            diagnostics.add(LoaderDiagnostic.of(
                    LoaderDiagnosticCode.STRUCTURE_REJECTED,
                    entry.path(),
                    LoaderErrors.safe(error)));
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
                disposer.disposeHandle(entry.path(), handle, diagnostics, operation);
                return rejectedWithoutOrphan(entry, context, mountContext, diagnostics);
            }
            SettlementReport report = mountContext.lastReport();
            if (KnotraLoader.settlementIndicatesFailure(report)) {
                LoaderDiagnosticMapper.addSettlementDiagnostics(
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
                    entry.typedConfig()));
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
            state.register(new MountAttempt(
                    entry.path(),
                    entry.name(),
                    context,
                    committed,
                    entry.definition(),
                    entry.typedConfig()));
            diagnostics.add(LoaderDiagnostic.of(
                    LoaderDiagnosticCode.SETTLEMENT_UNSETTLED,
                    entry.path(),
                    "committed mount did not settle within " + timeouts.get().settlement()
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
     * 新增批次的补偿：按挂载的逆序释放句柄，再释放新建 Context 子树；
     * 补偿失败会额外报告 COMPENSATION_FAILED，不掩盖原始诊断。
     */
    void rollbackAdd(
            List<ManagedEntry> mounted,
            Map<String, ContextHandle> created,
            List<LoaderDiagnostic> diagnostics,
            LoaderOperationTracker.Operation operation) {
        List<LoaderDiagnostic> compensation = new ArrayList<>();
        for (int index = mounted.size() - 1; index >= 0; index--) {
            ManagedEntry entry = mounted.get(index);
            disposer.disposeHandle(entry.path(), entry.handle(), compensation, operation);
        }
        rollbackContexts(created, compensation, operation);
        if (!compensation.isEmpty()) {
            diagnostics.add(LoaderDiagnostic.of(
                    LoaderDiagnosticCode.COMPENSATION_FAILED,
                    "",
                    "add rollback left unresolved resources"));
            diagnostics.addAll(compensation);
        }
        for (ManagedEntry entry : mounted) {
            state.remove(entry.path());
        }
        for (String path : created.keySet()) {
            state.removeContext(path);
        }
        state.republish();
    }

    /** Context 创建事务失败后的补偿：只释放新建子树并把补偿诊断并入结果。 */
    void rollbackCreatedContexts(
            Map<String, ContextHandle> created,
            List<LoaderDiagnostic> diagnostics,
            LoaderOperationTracker.Operation operation) {
        List<LoaderDiagnostic> compensation = new ArrayList<>();
        rollbackContexts(created, compensation, operation);
        diagnostics.addAll(compensation);
        created.clear();
    }

    // 只释放新建子树的根；释放根 Context 会级联子孙，逐个释放会重复处置。
    private void rollbackContexts(
            Map<String, ContextHandle> created,
            List<LoaderDiagnostic> compensation,
            LoaderOperationTracker.Operation operation) {
        List<String> roots = created.keySet().stream()
                .filter(path -> {
                    String parent = DesiredTreePreparer.parentPath(path);
                    return parent.isEmpty() || !created.containsKey(parent);
                })
                .sorted(Comparator.reverseOrder())
                .toList();
        for (String path : roots) {
            ContextHandle context = created.get(path);
            if (context != null) {
                disposer.disposeHandlelessContext(path, context, compensation, operation);
            }
        }
    }

    /**
     * 一次受控挂载尝试的结果：MOUNTED 返回可用句柄；REJECTED 表示槽位
     * 干净（从未提交或已可靠释放）；COMMITTED_UNSETTLED 表示已提交句柄
     * 无法有界释放、已由 mount 写入 Loader 记账并占用槽位。
     */
    record MountResult(Kind kind, MountAttempt attempt) {

        private enum Kind {
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

        boolean mounted() {
            return kind == Kind.MOUNTED;
        }

        boolean committedUnsettled() {
            return kind == Kind.COMMITTED_UNSETTLED;
        }
    }
}
