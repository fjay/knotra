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
import io.knotra.ContextState;
import io.knotra.KnotraRuntime;
import io.knotra.MountHandle;
import io.knotra.RuntimeSnapshot;
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
    private final BooleanSupplier closeStarted;

    ArtifactDrainService(
            PluginManager pluginManager,
            ArtifactCoordinator coordinator,
            KnotraRuntime runtime,
            ManagedArtifactStore store,
            BooleanSupplier closeStarted) {
        this.pluginManager = pluginManager;
        this.coordinator = coordinator;
        this.runtime = runtime;
        this.store = store;
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
        // 先在协调器上完成状态切换；后续等待和 dispose 异步执行，不占用唯一协调线程。
        CompletableFuture<Void> scheduled = coordinator.execute(() -> {
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
        return result;
    }

    private void waitAndDispose(DrainRequest request) {
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
                disposals.add(disposeRootHandle(handle));
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

    private CompletableFuture<ComponentState> disposeRootHandle(MountHandle handle) {
        boolean failedDisposedGoal = handle.state() == ComponentState.FAILED
                && handle.goal() == ComponentGoal.DISPOSED;
        // FAILED 且目标是 DISPOSED 时，只剩可重试的清理，不应重新启动组件。
        CompletableFuture<ComponentState> attempt = failedDisposedGoal
                ? handle.retryAsync().toCompletableFuture()
                : handle.disposeAsync().toCompletableFuture();
        return attempt.exceptionallyCompose(error -> {
            if (!isRuntimeClosing()) {
                return CompletableFuture.failedFuture(error);
            }
            // runtime.close 已拥有该清理；适配器等待其收敛，避免重复 dispose 争抢。
            return handle.whenSettled().toCompletableFuture();
        });
    }

    private boolean isRuntimeClosing() {
        String rootId = runtime.root().contextId();
        return runtime.advanced().snapshot().contexts().stream()
                .filter(context -> context.contextId().equals(rootId))
                .findFirst()
                .map(context -> context.state() == ContextState.DISPOSING
                        || context.state() == ContextState.DISPOSED)
                .orElse(false);
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
        coordinator.execute(() -> stopAndUnload(request)).whenComplete((ignored, error) -> {
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
