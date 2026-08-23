package io.knotra.loader;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import io.knotra.ComponentGoal;
import io.knotra.ComponentState;
import io.knotra.ContextHandle;
import io.knotra.ContextState;
import io.knotra.KnotraRuntime;
import io.knotra.MountHandle;

/**
 * Loader 的释放协作类：负责单个句柄 / Context 的释放结算，以及运行时整体关闭
 * 接管释放时的有界等待。释放成功后同步 {@link LoaderStateStore} 记账并重发视图。
 *
 * <p>每个同步等待前后都会推进挂起操作令牌的阶段（dispose-handle / dispose-context /
 * runtime-owned），令牌由调用方显式传入，不使用 ThreadLocal。</p>
 */
final class LoaderDisposer {

    private final KnotraRuntime runtime;
    private final ContextHandle baseContext;
    private final LoaderStateStore state;
    private final Supplier<LoaderTimeouts> timeouts;

    LoaderDisposer(
            KnotraRuntime runtime,
            ContextHandle baseContext,
            LoaderStateStore state,
            Supplier<LoaderTimeouts> timeouts) {
        this.runtime = runtime;
        this.baseContext = baseContext;
        this.state = state;
        this.timeouts = timeouts;
    }

    /** 判断基础 Context 的释放是否源于运行时整体关闭（根 Context 已在释放中）。 */
    boolean isRuntimeClosingBase() {
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

    /**
     * 通过运行时事务释放单个句柄并等待结算；若运行时整体关闭已经接管释放，
     * 只等待其完成而不把竞态重复报告为失败。
     */
    boolean disposeHandle(
            String path,
            MountHandle handle,
            List<LoaderDiagnostic> diagnostics,
            LoaderOperationTracker.Operation operation) {
        if (handle.state() == ComponentState.DISPOSED) {
            return true;
        }
        try {
            operation.phase(
                    LoaderOperationTracker.Phase.DISPOSE_HANDLE,
                    handle.handleId(),
                    path,
                    handle.handleId(),
                    Duration.ZERO);
            ComponentState settled = bestEffortRelease(handle)
                    .toCompletableFuture()
                    .get();
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
                    && awaitRuntimeOwnedHandleDisposal(path, handle, operation)) {
                return true;
            }
            LoaderDiagnosticMapper.addAsyncDiagnostics(
                    LoaderDiagnosticCode.TEARDOWN_FAILED, path, diagnostics, error);
            return false;
        }
    }

    /** 释放路径对应的 Context；空路径表示 owned 模式的基础 Context。 */
    boolean disposeContext(
            String path,
            List<LoaderDiagnostic> diagnostics,
            LoaderOperationTracker.Operation operation) {
        ContextHandle context = path.isEmpty()
                ? baseContext
                : state.context(path);
        return disposeHandlelessContext(path, context, diagnostics, operation);
    }

    /**
     * 通过运行时事务释放 Context 并等待结算。释放会级联其子 Context 与挂载，
     * 成功后从记账中移除整个子树；运行时整体关闭已接管释放时只做有界等待。
     */
    boolean disposeHandlelessContext(
            String path,
            ContextHandle context,
            List<LoaderDiagnostic> diagnostics,
            LoaderOperationTracker.Operation operation) {
        if (context == null) {
            return true;
        }
        if (context.state() == ContextState.DISPOSED) {
            state.prune(path);
            state.republish();
            return true;
        }
        try {
            operation.phase(
                    LoaderOperationTracker.Phase.DISPOSE_CONTEXT,
                    context.contextId(),
                    path,
                    context.contextId(),
                    Duration.ZERO);
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
                    && awaitRuntimeOwnedContextDisposal(path, context, operation)) {
                state.prune(path);
                state.republish();
                return true;
            }
            LoaderDiagnosticMapper.addAsyncDiagnostics(
                    LoaderDiagnosticCode.TEARDOWN_FAILED, path, diagnostics, error);
            return false;
        }
        state.prune(path);
        state.republish();
        return true;
    }

    /**
     * 选择释放手段：FAILED 且目标已是 DISPOSED 的句柄必须走 retry 完成挂起的
     * 释放，其余情况走常规 dispose。
     */
    private static CompletionStage<ComponentState> bestEffortRelease(MountHandle handle) {
        if (handle.state() == ComponentState.FAILED
                && handle.goal() == ComponentGoal.DISPOSED) {
            return handle.retryAsync();
        }
        return handle.disposeAsync();
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
    private boolean awaitRuntimeOwnedHandleDisposal(
            String path,
            MountHandle handle,
            LoaderOperationTracker.Operation operation) {
        try {
            operation.phase(
                    LoaderOperationTracker.Phase.RUNTIME_OWNED,
                    handle.handleId(),
                    path,
                    handle.handleId(),
                    timeouts.get().runtimeDisposal());
            ComponentState state = handle.whenSettled()
                    .toCompletableFuture()
                    .get(timeouts.get().runtimeDisposal().toMillis(), TimeUnit.MILLISECONDS);
            return state == ComponentState.DISPOSED;
        } catch (Exception error) {
            return false;
        }
    }

    /** 有界等待运行时接管的 Context 释放完成。 */
    private boolean awaitRuntimeOwnedContextDisposal(
            String path,
            ContextHandle context,
            LoaderOperationTracker.Operation operation) {
        CompletableFuture<Void> settled = new CompletableFuture<>();
        pollContextDisposal(context, settled, timeouts.get().contextPollTicks());
        try {
            operation.phase(
                    LoaderOperationTracker.Phase.RUNTIME_OWNED,
                    context.contextId(),
                    path,
                    context.contextId(),
                    timeouts.get().runtimeDisposal());
            settled.get(timeouts.get().runtimeDisposal().toMillis(), TimeUnit.MILLISECONDS);
            return true;
        } catch (Exception error) {
            return false;
        }
    }

    // Context 没有 whenSettled 之类的完成句柄，用短轮询有界等待 DISPOSED。
    private void pollContextDisposal(
            ContextHandle context,
            CompletableFuture<Void> settled,
            long remainingTicks) {
        if (context.state() == ContextState.DISPOSED) {
            settled.complete(null);
            return;
        }
        if (remainingTicks <= 0) {
            settled.completeExceptionally(new IllegalStateException(
                    "runtime-owned context disposal did not settle"));
            return;
        }
        CompletableFuture.delayedExecutor(
                        timeouts.get().contextPoll().toMillis(), TimeUnit.MILLISECONDS)
                .execute(() -> pollContextDisposal(context, settled, remainingTicks - 1));
    }
}
