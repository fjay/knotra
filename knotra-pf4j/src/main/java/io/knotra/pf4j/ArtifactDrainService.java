package io.knotra.pf4j;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

import io.knotra.ComponentGoal;
import io.knotra.ComponentState;
import io.knotra.DiagnosticCode;
import io.knotra.KnotraRuntime;
import io.knotra.MountHandle;
import io.knotra.RuntimeSnapshot;
import io.knotra.TransactionRejectedException;
import org.pf4j.PluginManager;
import org.pf4j.PluginState;
import org.pf4j.PluginWrapper;

/**
 * PF4J artifact 的异步排空服务。服务只编排等待、释放与 PF4J 卸载，内存终态由
 * {@link ManagedArtifactStore} 提交。
 */
final class ArtifactDrainService {

    private final PluginManager pluginManager;
    private final ArtifactCoordinator coordinator;
    private final KnotraRuntime runtime;
    private final ManagedArtifactStore store;
    private final PendingOperationTracker pendingTracker;
    private final BooleanSupplier closeStarted;

    ArtifactDrainService(
            PluginManager pluginManager,
            ArtifactCoordinator coordinator,
            KnotraRuntime runtime,
            ManagedArtifactStore store,
            PendingOperationTracker pendingTracker,
            BooleanSupplier closeStarted) {
        this.pluginManager = pluginManager;
        this.coordinator = coordinator;
        this.runtime = runtime;
        this.store = store;
        this.pendingTracker = pendingTracker;
        this.closeStarted = closeStarted;
    }

    CompletableFuture<Void> drain(String artifactId, String phase) {
        if (closeStarted.getAsBoolean() && "unload".equals(phase)) {
            return CompletableFuture.failedFuture(new IllegalStateException("adapter is closing"));
        }
        if (coordinator.isStopped()) {
            return CompletableFuture.failedFuture(new IllegalStateException("adapter is closed"));
        }
        CompletableFuture<Void> result = new CompletableFuture<>();
        pendingTracker.beginDrain(result, artifactId, "schedule-coordinator", List.of(artifactId));
        result.whenComplete((ignored, error) -> pendingTracker.endDrain(result));
        // 先在协调器上完成状态切换；后续等待和 dispose 异步执行，不占用唯一协调线程。
        CompletableFuture<Void> scheduled = coordinator.execute(artifactId, () -> {
            pendingTracker.updateDrain(result, "coordinator", List.of(artifactId));
            try {
                ManagedArtifactStore.DrainDecision<DrainRequest> decision = store.prepareDrain(
                        artifactId,
                        phase,
                        result,
                        closure -> new DrainRequest(closure, result, phase));
                if (decision.isNotManaged()) {
                    result.completeExceptionally(new ArtifactOperationException(
                            artifactId, phase, "artifact is not managed"));
                    return;
                }
                if (decision.isReused()) {
                    return;
                }
                if (decision.isAlreadyUnloaded()) {
                    result.complete(null);
                    return;
                }
                waitAndDispose(decision.request());
            } catch (RuntimeException failure) {
                result.completeExceptionally(failure);
            }
        });
        scheduled.whenComplete((ignored, error) -> {
            if (error != null) {
                result.completeExceptionally(error);
            }
        });
        // result 是驱动 future（drainFuture/PendingTracker identity），只外发镜像。
        return FutureMirrors.mirror(result);
    }

    private void waitAndDispose(DrainRequest request) {
        List<String> closureIds = closureIds(request);
        pendingTracker.updateDrain(request.result(), "wait-mounts", closureIds);
        // drain 的顺序是等待 in-flight 挂载，再刷新归属并异步 dispose owned root。
        List<CompletableFuture<Void>> waits = request.artifacts().stream()
                .map(store::waitForMounts)
                .toList();
        CompletableFuture.allOf(waits.toArray(CompletableFuture[]::new))
                .whenComplete((ignored, waitFailure) -> {
                    if (waitFailure != null) {
                        transitionDrain(request, waitFailure);
                        return;
                    }
                    disposeOwnedHandles(request);
                });
    }

    private void disposeOwnedHandles(DrainRequest request) {
        pendingTracker.updateDrain(request.result(), "dispose-roots", closureIds(request));
        for (ManagedArtifact artifact : request.artifacts()) {
            refreshOwnership(artifact.artifactId);
        }
        // 只 dispose 适配器直接提交的根；来源标记像 artifact 的宿主根不能被悄悄夺走。
        ArtifactOperationException missingRoots = verifyKnownArtifactRoots(request);
        if (missingRoots != null) {
            transitionDrain(request, missingRoots);
            return;
        }
        List<CompletableFuture<ComponentState>> disposals = collectDisposals(request);
        if (disposals.isEmpty()) {
            transitionDrain(request, null);
            return;
        }
        CompletableFuture.allOf(disposals.toArray(CompletableFuture[]::new))
                .whenComplete((ignored, disposalFailure) ->
                        transitionDrain(request, disposalOutcome(request, disposalFailure)));
    }

    private List<CompletableFuture<ComponentState>> collectDisposals(DrainRequest request) {
        List<CompletableFuture<ComponentState>> disposals = new ArrayList<>();
        for (ManagedArtifact artifact : request.artifacts()) {
            for (MountHandle handle : artifact.rootHandles()) {
                if (handle.state() == ComponentState.DISPOSED) {
                    continue;
                }
                disposals.add(disposeRootHandle(request, handle));
            }
        }
        return disposals;
    }

    private Throwable disposalOutcome(
            DrainRequest request,
            Throwable disposalFailure) {
        for (ManagedArtifact artifact : request.artifacts()) {
            refreshOwnership(artifact.artifactId);
        }
        boolean unsettled = request.artifacts().stream()
                .flatMap(artifact -> artifact.rootHandles().stream())
                .anyMatch(handle -> handle.state() != ComponentState.DISPOSED);
        if (disposalFailure == null && !unsettled) {
            return null;
        }
        return disposalFailure == null
                ? new ArtifactOperationException(
                        request.targetId(),
                        "drain",
                        "one or more artifact components failed teardown")
                : disposalFailure;
    }

    private CompletableFuture<ComponentState> disposeRootHandle(
            DrainRequest request, MountHandle handle) {
        boolean failedDisposedGoal = handle.state() == ComponentState.FAILED
                && handle.goal() == ComponentGoal.DISPOSED;
        // FAILED 且目标是 DISPOSED 时，只剩可重试的清理，不应重新启动组件。
        CompletableFuture<ComponentState> attempt = failedDisposedGoal
                ? handle.retryAsync().toCompletableFuture()
                : handle.disposeAsync().toCompletableFuture();
        return attempt.exceptionallyCompose(error -> {
            if (!isRuntimeOwnedTeardown(error)) {
                return CompletableFuture.failedFuture(error);
            }
            // runtime.close 已拥有该清理；适配器等待其收敛，避免重复 dispose 争抢。
            return awaitRuntimeOwnedTeardown(request, handle);
        });
    }

    /**
     * 等待 runtime.close 接管的清理收敛到终态。
     *
     * <p>P0 之后 {@code whenSettled()} 是纯观察：只有 close 的结构事务把句柄翻成
     * STOPPING 并预约过渡后，它才会挂到真实收敛 Future 上。接管拒绝可能发生在
     * {@code closing} 置位之后、结构发布之前的窗口，此时直接观察只能拿到旧视图的
     * ACTIVE/STARTING 等中间态，把它当终态判定就是本修复要消除的竞态。</p>
     *
     * <p>因此分两段等待：第一段仍用 whenSettled——close 发布后整个根子树的过渡
     * 已在同一协调器临界区内原子预约，挂上句柄自身的过渡即可收敛，不必等全局
     * close 完成；只有观察到非 DISPOSED 中间态时，才说明处于发布前窗口，此时
     * 唯一 owner 信号是 runtime.closeAsync future，等它完成后再复核句柄终态。
     * close 失败原样传播；close 成功但句柄未到 DISPOSED 也按失败上报，绝不把
     * FAILED/清理失败伪装成 DISPOSED。</p>
     *
     * <p>循环约束：组件生命周期回调（start/清理、事件监听）不得阻塞等待同一
     * runtime 下 adapter 的 close/unload。该场景下 close 的收敛因果上依赖回调返回，
     * 回调又等待 adapter drain，第二段的 owner 等待会形成环；这是公开 API 可构造
     * 但被禁止的用法，文档见 {@link Pf4jArtifactAdapter#closeAsync()}。</p>
     */
    private CompletableFuture<ComponentState> awaitRuntimeOwnedTeardown(
            DrainRequest request, MountHandle handle) {
        return handle.whenSettled().toCompletableFuture().thenCompose(observed -> {
            if (observed == ComponentState.DISPOSED) {
                return CompletableFuture.completedFuture(observed);
            }
            pendingTracker.updateDrain(
                    request.result(), "wait-runtime-close", closureIds(request));
            return runtime.closeAsync().toCompletableFuture()
                    .thenCompose(ignored -> handle.whenSettled().toCompletableFuture())
                    .thenCompose(finalState -> finalState == ComponentState.DISPOSED
                            ? CompletableFuture.completedFuture(finalState)
                            : CompletableFuture.failedFuture(runtimeOwnedTeardownFailed(
                                    request.targetId(), handle, finalState)));
        });
    }

    private ArtifactOperationException runtimeOwnedTeardownFailed(
            String targetId, MountHandle handle, ComponentState finalState) {
        return new ArtifactOperationException(
                targetId,
                "drain",
                "artifact component " + handle.handleId()
                        + " did not reach DISPOSED after runtime close: " + finalState);
    }

    private boolean isRuntimeOwnedTeardown(Throwable error) {
        if (!runtime.advanced().pendingOperations().closeRequested()) {
            return false;
        }
        // 两种接管形态：
        // 1. dispose 在 runtime.close 置位后提交，事务以 runtime is closing 拒绝；
        // 2. dispose 提交前后 runtime.close 已移除组件所有权，事务以句柄不属于 runtime
        //    拒绝。两种形态都必须由 closeRequested + 精确诊断识别；句柄状态本身存在
        //    发布间隙，不能作为判据，最终收敛由 whenSettled 与 disposalOutcome 复核。
        return isRuntimeCloseRejection(error) || isHandleDisownedRejection(error);
    }

    /**
     * 判断 dispose 失败是否为 runtime.close 接管形态之一：
     *
     * <p>形态一：{@code INVALID_LIFECYCLE_OPERATION} 且消息为 runtime is closing 的
     * {@link TransactionRejectedException}。closeRequested 在 closeAsync 入口同步置位且
     * 不会复位，先于根 Context 状态翻转，查询无阻塞；根 Context 快照存在「已置 closing、
     * 尚未发布 DISPOSING」的窗口，不能作为判据。</p>
     *
     * <p>形态二：runtime.close 已移除组件所有权，事务以句柄不属于 runtime 拒绝。
     * 两种形态都以 closeRequested 与精确诊断双重复核；普通 dispose 失败（其他诊断码、
     * 其他消息或非事务异常）照常传播，收敛结果由 whenSettled 与 disposalOutcome 复核。</p>
     */
    static boolean isRuntimeCloseRejection(Throwable error) {
        return matchesRejection(error, "runtime is closing");
    }

    static boolean isHandleDisownedRejection(Throwable error) {
        return matchesRejection(error, "component handle does not belong to this runtime");
    }

    private static boolean matchesRejection(Throwable error, String messagePart) {
        Throwable cause = unwrap(error);
        if (!(cause instanceof TransactionRejectedException rejection)) {
            return false;
        }
        return rejection.diagnostics().stream().anyMatch(diagnostic ->
                diagnostic.code() == DiagnosticCode.INVALID_LIFECYCLE_OPERATION
                        && diagnostic.message().contains(messagePart));
    }

    private ArtifactOperationException verifyKnownArtifactRoots(DrainRequest request) {
        Set<String> artifactIds = request.artifacts().stream()
                .map(artifact -> artifact.artifactId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        RuntimeSnapshot snapshot = runtime.advanced().snapshot();
        List<String> missing = store.unownedArtifactRoots(artifactIds, snapshot);
        if (missing.isEmpty()) {
            return null;
        }
        return new ArtifactOperationException(
                request.targetId(),
                "drain",
                "artifact snapshot contains roots without adapter ownership: "
                        + missing.stream().sorted().toList());
    }

    private void transitionDrain(DrainRequest request, Throwable failure) {
        if (failure != null) {
            store.markDrainFailed(request.artifacts(), FailureText.describe(failure));
            request.result().completeExceptionally(failure);
            return;
        }
        // 所有 dispose settled 后重新进入协调器；最终 stop/unload 不能与读或其他 drain 交错。
        // 排队期间等待的是协调器槽位；真正进入 runnable 后才切换到 PF4J stop/unload 边界。
        pendingTracker.updateDrain(request.result(), "schedule-stop-unload", closureIds(request));
        coordinator.execute(request.targetId(), () -> {
            pendingTracker.updateDrain(request.result(), "stop-unload", closureIds(request));
            stopAndUnload(request);
        })
                .whenComplete((ignored, error) -> {
            Throwable outcome = error == null ? null : unwrap(error);
            if (outcome == null) {
                store.completeUnload(request.artifacts());
                request.result().complete(null);
                return;
            }
            store.markUnloadFailed(request.artifacts(), FailureText.describe(outcome));
            request.result().completeExceptionally(outcome);
        });
    }

    private void stopAndUnload(DrainRequest request) {
        for (ManagedArtifact artifact : request.artifacts()) {
            PluginWrapper wrapper = pluginManager.getPlugin(artifact.artifactId);
            PluginState current = wrapper == null || wrapper.getPluginState() == null
                    ? PluginState.UNLOADED
                    : wrapper.getPluginState();
            if (current == PluginState.STARTED) {
                PluginState stopped = pluginManager.stopPlugin(artifact.artifactId);
                if (stopped != PluginState.STOPPED) {
                    throw new IllegalStateException(
                            "PF4J stop returned " + stopped + " for " + artifact.artifactId);
                }
            }
            if (!pluginManager.unloadPlugin(artifact.artifactId)
                    && pluginManager.getPlugin(artifact.artifactId) != null) {
                throw new IllegalStateException(
                        "PF4J unload returned false for " + artifact.artifactId);
            }
        }
    }

    private void refreshOwnership(String artifactId) {
        store.ownershipCoordinated(artifactId, () -> runtime.advanced().snapshot());
    }

    private static List<String> closureIds(DrainRequest request) {
        return request.artifacts().stream()
                .map(artifact -> artifact.artifactId)
                .toList();
    }

    private static Throwable unwrap(Throwable failure) {
        return failure instanceof CompletionException completion
                && completion.getCause() != null
                ? completion.getCause()
                : failure;
    }

    private record DrainRequest(
            List<ManagedArtifact> artifacts,
            CompletableFuture<Void> result,
            String phase) {

        String targetId() {
            return artifacts.getLast().artifactId;
        }
    }
}
