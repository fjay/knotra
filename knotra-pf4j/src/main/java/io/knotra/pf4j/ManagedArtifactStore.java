package io.knotra.pf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import io.knotra.ComponentOrigin;
import io.knotra.ComponentState;
import io.knotra.MountHandle;
import io.knotra.RuntimeSnapshot;
import org.pf4j.PluginWrapper;

/**
 * 受管 artifact 状态仓库，独占适配器内存表和状态锁。
 *
 * <p>需要执行插件代码的协议由本类负责“锁内检查、锁外执行、锁内复查”；调用方只提交
 * 候选对象或状态切换意图，不能直接触碰表结构和锁。</p>
 */
final class ManagedArtifactStore {

    private final Object stateLock = new Object();
    private final Map<String, ManagedArtifact> artifacts = new LinkedHashMap<>();
    private final Map<String, ArtifactDiagnostic> loadFailures = new LinkedHashMap<>();
    private final Map<String, ArtifactSnapshot> terminalSnapshots = new LinkedHashMap<>();
    private final Map<String, ManagedFactory> catalog = new LinkedHashMap<>();

    List<ArtifactFactoryCatalogEntry> catalogEntries() {
        synchronized (stateLock) {
            List<ArtifactFactoryCatalogEntry> entries = new ArrayList<>();
            for (ManagedFactory factory : catalog.values()) {
                entries.add(new ArtifactFactoryCatalogView(
                        factory.artifactId(),
                        factory.artifactVersion(),
                        factory.artifactPath(),
                        factory.factoryId(),
                        factory.configTypeName()));
            }
            return List.copyOf(entries);
        }
    }

    ManagedFactory activeFactory(String factoryId) {
        synchronized (stateLock) {
            return catalog.get(factoryId);
        }
    }

    void requireFactoryIdAbsent(String factoryId) {
        synchronized (stateLock) {
            if (catalog.containsKey(factoryId)) {
                throw new IllegalStateException(
                        "factory id is already cataloged: " + factoryId);
            }
        }
    }

    boolean containsArtifact(String artifactId) {
        synchronized (stateLock) {
            return artifacts.containsKey(artifactId);
        }
    }

    ManagedArtifact artifact(String artifactId) {
        synchronized (stateLock) {
            return artifacts.get(artifactId);
        }
    }

    List<String> artifactIds() {
        synchronized (stateLock) {
            return List.copyOf(artifacts.keySet());
        }
    }

    List<String> activeArtifactIds() {
        synchronized (stateLock) {
            return artifacts.values().stream()
                    .filter(artifact -> artifact.state != ArtifactState.UNLOADED)
                    .map(artifact -> artifact.artifactId)
                    .toList();
        }
    }

    ArtifactSnapshot snapshot(String artifactId, List<ArtifactOwnership> ownership) {
        synchronized (stateLock) {
            ManagedArtifact artifact = artifacts.get(artifactId);
            return artifact == null ? null : artifact.snapshot(ownership);
        }
    }

    Optional<ArtifactSnapshot> terminalSnapshot(String artifactId) {
        synchronized (stateLock) {
            return Optional.ofNullable(terminalSnapshots.get(artifactId));
        }
    }

    Optional<ArtifactDiagnostic> stoppedDiagnostic(String artifactId) {
        synchronized (stateLock) {
            ManagedArtifact artifact = artifacts.get(artifactId);
            return Optional.ofNullable(artifact == null
                    ? loadFailures.get(artifactId)
                    : artifact.diagnostic(List.of()));
        }
    }

    Optional<ArtifactDiagnostic> diagnostic(String artifactId, List<ArtifactOwnership> ownership) {
        synchronized (stateLock) {
            ManagedArtifact artifact = artifacts.get(artifactId);
            if (artifact != null) {
                return Optional.of(artifact.diagnostic(ownership));
            }
            return Optional.ofNullable(loadFailures.get(artifactId));
        }
    }

    /**
     * 执行发布协议：锁内确认无冲突，释放锁构建候选，再锁内复查并提交。
     */
    boolean publishCandidate(
            String artifactId,
            Consumer<ManagedArtifact> conflictCheck,
            Supplier<Candidate> candidateSupplier) {
        synchronized (stateLock) {
            ManagedArtifact existing = artifacts.get(artifactId);
            if (existing != null && existing.state != ArtifactState.UNLOADED) {
                conflictCheck.accept(existing);
                return false;
            }
        }

        Candidate publication = candidateSupplier.get();
        synchronized (stateLock) {
            ManagedArtifact existing = artifacts.get(artifactId);
            if (existing != null && existing.state != ArtifactState.UNLOADED) {
                conflictCheck.accept(existing);
                return false;
            }
            ManagedArtifact candidate = publication.artifact();
            artifacts.put(artifactId, candidate);
            loadFailures.remove(artifactId);
            for (ManagedFactory factory : publication.factories()) {
                candidate.factories.add(factory);
                candidate.factoriesById.put(factory.factoryId, factory);
                candidate.factoryIdHistory.add(factory.factoryId);
                catalog.put(factory.factoryId, factory);
            }
            return true;
        }
    }

    void registerResidual(
            String artifactId,
            PluginWrapper wrapper,
            Function<PluginWrapper, ManagedArtifact> candidate,
            String detail) {
        synchronized (stateLock) {
            ManagedArtifact artifact = artifacts.get(artifactId);
            if (artifact == null) {
                artifact = candidate.apply(wrapper);
                artifacts.put(artifactId, artifact);
            }
            artifact.fail("load-rollback-residual", detail);
            loadFailures.put(artifactId, artifact.diagnostic(List.of()));
        }
    }

    void discardUnloadedView(String artifactId) {
        synchronized (stateLock) {
            ManagedArtifact artifact = artifacts.get(artifactId);
            if (artifact != null) {
                artifact.unloadView();
            }
        }
    }

    void recordLoadFailure(String targetId, String message, boolean residual) {
        synchronized (stateLock) {
            ManagedArtifact existing = artifacts.get(targetId);
            if (existing != null && existing.state != ArtifactState.UNLOADED) {
                existing.lastError = message;
                return;
            }
            loadFailures.put(targetId, new ArtifactDiagnostic(
                    targetId,
                    ArtifactState.FAILED,
                    residual ? "load-rollback-residual" : "load-rollback",
                    List.of(),
                    List.of(),
                    Optional.of(message),
                    List.of()));
        }
    }

    void beginMount(ManagedFactory handle) {
        synchronized (stateLock) {
            requireMountable(handle);
            handle.artifact.mountsInFlight++;
        }
    }

    boolean isMountable(ManagedFactory handle) {
        synchronized (stateLock) {
            return isMountableUnlocked(handle);
        }
    }

    boolean recordDirectHandle(
            ManagedArtifact artifact,
            String handleId,
            MountHandle handle) {
        synchronized (stateLock) {
            artifact.directHandles.put(handleId, handle);
            return artifact.acceptingMounts && artifact.state == ArtifactState.ACTIVE;
        }
    }

    void endMount(ManagedArtifact artifact) {
        synchronized (stateLock) {
            artifact.mountsInFlight = Math.max(0, artifact.mountsInFlight - 1);
            if (artifact.mountsInFlight == 0) {
                CompletableFuture<Void> future = artifact.mountsInFlightFuture;
                artifact.mountsInFlightFuture = null;
                if (future != null) {
                    future.complete(null);
                }
            }
        }
    }

    CompletableFuture<Void> waitForMounts(ManagedArtifact artifact) {
        synchronized (stateLock) {
            if (artifact.mountsInFlight == 0) {
                return CompletableFuture.completedFuture(null);
            }
            if (artifact.mountsInFlightFuture == null) {
                artifact.mountsInFlightFuture = new CompletableFuture<>();
            }
            return artifact.mountsInFlightFuture;
        }
    }

    <T> DrainDecision<T> prepareDrain(
            String artifactId,
            String phase,
            CompletableFuture<Void> result,
            Function<List<ManagedArtifact>, T> requestFactory) {
        synchronized (stateLock) {
            ManagedArtifact target = artifacts.get(artifactId);
            if (target == null) {
                return DrainDecision.notManaged();
            }
            // 复用同一个 drain future，使并发 unload/retry 观察到同一结果。
            if (target.state == ArtifactState.DRAINING && target.drainFuture != null) {
                target.drainFuture.whenComplete((ignored, error) -> {
                    if (error == null) {
                        result.complete(null);
                    } else {
                        result.completeExceptionally(error);
                    }
                });
                return DrainDecision.reused();
            }
            if (target.state == ArtifactState.UNLOADED) {
                return DrainDecision.unloaded();
            }
            List<ManagedArtifact> closure = dependentClosureLocked(target);
            transitionToDrainingLocked(closure, result, phase);
            return DrainDecision.started(requestFactory.apply(closure));
        }
    }

    void markDrainFailed(List<ManagedArtifact> closure, String detail) {
        synchronized (stateLock) {
            for (ManagedArtifact artifact : closure) {
                artifact.state = ArtifactState.DRAIN_FAILED;
                artifact.transition = "drain-failed";
                artifact.acceptingMounts = false;
                artifact.lastError = detail;
                artifact.drainFuture = null;
            }
        }
    }

    void completeUnload(List<ManagedArtifact> closure) {
        synchronized (stateLock) {
            for (ManagedArtifact artifact : closure) {
                artifact.state = ArtifactState.UNLOADED;
                artifact.transition = "unloaded";
                terminalSnapshots.put(artifact.artifactId, artifact.snapshot(List.of()));
                artifact.unloadView();
            }
        }
    }

    void markUnloadFailed(List<ManagedArtifact> closure, String detail) {
        synchronized (stateLock) {
            for (ManagedArtifact artifact : closure) {
                if (artifact.state == ArtifactState.UNLOADED) {
                    continue;
                }
                artifact.state = ArtifactState.DRAIN_FAILED;
                artifact.transition = "pf4j-unload-failed";
                artifact.acceptingMounts = false;
                artifact.lastError = detail;
                artifact.drainFuture = null;
            }
        }
    }

    List<String> unownedArtifactRoots(Set<String> artifactIds, RuntimeSnapshot snapshot) {
        List<String> missing = new ArrayList<>();
        synchronized (stateLock) {
            for (RuntimeSnapshot.MountSnapshot component : snapshot.mounts()) {
                ComponentOrigin origin = component.origin();
                if (origin.kind() != ComponentOrigin.Kind.ARTIFACT
                        || !artifactIds.contains(origin.sourceId())
                        || component.parentHandleId() != null) {
                    continue;
                }
                ManagedArtifact artifact = artifacts.get(origin.sourceId());
                if (artifact == null || !artifact.directHandles.containsKey(component.handleId())) {
                    missing.add(component.handleId());
                }
            }
        }
        return missing;
    }

    /**
     * 生成 ownership 前在锁外读取运行时快照，再在锁内移除过期本地句柄。
     */
    List<ArtifactOwnership> ownershipCoordinated(
            String artifactId,
            Supplier<RuntimeSnapshot> snapshotSupplier) {
        ManagedArtifact artifact;
        synchronized (stateLock) {
            artifact = artifacts.get(artifactId);
        }
        if (artifact == null || artifact.state == ArtifactState.UNLOADED) {
            return List.of();
        }
        RuntimeSnapshot snapshot = snapshotSupplier.get();
        Map<String, RuntimeSnapshot.MountSnapshot> byId = snapshot.mounts().stream()
                .collect(java.util.stream.Collectors.toMap(
                        RuntimeSnapshot.MountSnapshot::handleId,
                        item -> item,
                        (left, right) -> right,
                        LinkedHashMap::new));
        synchronized (stateLock) {
            // 运行时快照具有权威性；已释放挂载的过期本地记录会被移除。
            artifact.directHandles.keySet().removeIf(handleId ->
                    artifact.directHandles.get(handleId) != null
                            && artifact.directHandles.get(handleId).state() == ComponentState.DISPOSED
                            && !byId.containsKey(handleId));
            List<ArtifactOwnership> result = new ArrayList<>();
            for (RuntimeSnapshot.MountSnapshot component : snapshot.mounts()) {
                ComponentOrigin origin = component.origin();
                if (origin.kind() != ComponentOrigin.Kind.ARTIFACT
                        || !origin.sourceId().equals(artifactId)) {
                    continue;
                }
                result.add(new ArtifactOwnership(
                        artifactId,
                        component.factoryId(),
                        component.handleId(),
                        component.mountId(),
                        component.parentHandleId(),
                        component.state()));
            }
            return result;
        }
    }

    private void requireMountable(ManagedFactory handle) {
        if (!isMountableUnlocked(handle)) {
            throw mountUnavailable(handle);
        }
    }

    private boolean isMountableUnlocked(ManagedFactory handle) {
        return handle.factory != null
                && handle.artifact.acceptingMounts
                && handle.artifact.state == ArtifactState.ACTIVE;
    }

    private ArtifactOperationException mountUnavailable(ManagedFactory handle) {
        return new ArtifactOperationException(
                handle.artifact.artifactId,
                "mount",
                "artifact is not accepting mounts");
    }

    private List<ManagedArtifact> dependentClosureLocked(ManagedArtifact target) {
        List<ManagedArtifact> order = new ArrayList<>();
        collectDependentsLocked(
                target.artifactId,
                new LinkedHashSet<>(),
                order,
                new LinkedHashSet<>());
        return order;
    }

    private void collectDependentsLocked(
            String artifactId,
            Set<String> visited,
            List<ManagedArtifact> order,
            Set<String> visiting) {
        if (!visiting.add(artifactId)) {
            throw new IllegalStateException("PF4J dependent cycle contains artifact " + artifactId);
        }
        for (ManagedArtifact candidate : artifacts.values()) {
            if (candidate.state != ArtifactState.UNLOADED
                    && candidate.dependencies.contains(artifactId)) {
                collectDependentsLocked(
                        candidate.artifactId,
                        visited,
                        order,
                        visiting);
            }
        }
        visiting.remove(artifactId);
        if (visited.add(artifactId)) {
            ManagedArtifact self = artifacts.get(artifactId);
            if (self != null && self.state != ArtifactState.UNLOADED) {
                order.add(self);
            }
        }
    }

    private void transitionToDrainingLocked(
            List<ManagedArtifact> closure,
            CompletableFuture<Void> result,
            String phase) {
        // 卸载依赖会先 drain 全部未卸载下游 artifact，避免下游仍引用已释放插件类。
        for (ManagedArtifact artifact : closure) {
            artifact.state = ArtifactState.DRAINING;
            artifact.transition = phase;
            artifact.acceptingMounts = false;
            artifact.drainFuture = result;
            for (ManagedFactory factory : artifact.factories) {
                catalog.remove(factory.factoryId);
            }
            artifact.invalidateFactories();
        }
    }

    record Candidate(ManagedArtifact artifact, List<ManagedFactory> factories) {
    }

    private enum DrainDisposition {
        NOT_MANAGED,
        REUSED,
        UNLOADED,
        STARTED
    }

    record DrainDecision<T>(DrainDisposition disposition, T request) {

        static <T> DrainDecision<T> notManaged() {
            return new DrainDecision<>(DrainDisposition.NOT_MANAGED, null);
        }

        static <T> DrainDecision<T> reused() {
            return new DrainDecision<>(DrainDisposition.REUSED, null);
        }

        static <T> DrainDecision<T> unloaded() {
            return new DrainDecision<>(DrainDisposition.UNLOADED, null);
        }

        static <T> DrainDecision<T> started(T request) {
            return new DrainDecision<>(DrainDisposition.STARTED, request);
        }

        boolean isNotManaged() {
            return disposition == DrainDisposition.NOT_MANAGED;
        }

        boolean isReused() {
            return disposition == DrainDisposition.REUSED;
        }

        boolean isAlreadyUnloaded() {
            return disposition == DrainDisposition.UNLOADED;
        }
    }
}
