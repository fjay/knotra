package io.knotra.pf4j;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.LongSupplier;

import io.knotra.PendingOperationsSnapshot;

/**
 * PF4J adapter 的纯元数据挂起操作跟踪器。
 *
 * <p>生命周期入口按对象身份更新记录；快照读取只采样文本和时间，不执行协调器、
 * PF4J 或组件代码。跟踪器不得保存 MountHandle、Runnable、Supplier 或 Throwable。</p>
 */
final class PendingOperationTracker {

    private final Object trackerLock = new Object();
    private final Map<ManagedArtifact, MountState> mounts = new IdentityHashMap<>();
    private final Map<CompletableFuture<Void>, DrainState> drains = new IdentityHashMap<>();
    private final LongSupplier ticker;

    PendingOperationTracker(LongSupplier ticker) {
        this.ticker = ticker;
    }

    void beginMount(ManagedArtifact artifact) {
        synchronized (trackerLock) {
            MountState state = mounts.computeIfAbsent(artifact, ignored -> new MountState());
            state.present = true;
            state.count++;
            if (!state.started) {
                state.started = true;
                state.startTick = ticker.getAsLong();
            }
        }
    }

    void endMount(ManagedArtifact artifact) {
        synchronized (trackerLock) {
            MountState state = mounts.get(artifact);
            if (state == null) {
                return;
            }
            state.count--;
            if (state.count <= 0) {
                mounts.remove(artifact);
            }
        }
    }

    void beginDrain(
            CompletableFuture<Void> identity,
            String artifactId,
            String phase,
            List<String> closureIds) {
        synchronized (trackerLock) {
            drains.put(identity, new DrainState(
                    artifactId,
                    phase,
                    List.copyOf(closureIds),
                    ticker.getAsLong()));
        }
    }

    void updateDrain(
            CompletableFuture<Void> identity,
            String phase,
            List<String> closureIds) {
        synchronized (trackerLock) {
            DrainState state = drains.get(identity);
            if (state != null) {
                state.phase = phase;
                state.closureIds = List.copyOf(closureIds);
            }
        }
    }

    void endDrain(CompletableFuture<Void> identity) {
        synchronized (trackerLock) {
            drains.remove(identity);
        }
    }

    PendingOperationsSnapshot snapshot(
            boolean closeRequested,
            String adapterId,
            List<ArtifactCoordinator.CoordinatorOperation> coordinatorOperations,
            Function<Set<String>, Map<String, ManagedArtifactStore.PendingMetadata>> metadataSupplier) {
        Sample sample = sample();
        Map<String, ManagedArtifactStore.PendingMetadata> metadata =
                metadataSupplier.apply(sample.artifactIds);
        List<PendingOperationsSnapshot.Operation> operations = new ArrayList<>(
                sample.mounts.size() + sample.drains.size() + coordinatorOperations.size());

        for (MountSample mount : sample.mounts) {
            ManagedArtifactStore.PendingMetadata item = metadata.get(mount.artifactId);
            int count = item == null ? 0 : item.mountsInFlight();
            List<String> handleIds = item == null ? List.of() : item.rootHandleIds();
            operations.add(new PendingOperationsSnapshot.Operation(
                    PendingOperationsSnapshot.Kind.ARTIFACT_MOUNT,
                    mount.artifactId,
                    PendingOperationsSnapshot.WaitType.MOUNTS_IN_FLIGHT,
                    mount.age,
                    "mountsInFlight=" + count
                            + ", knownHandleIds=" + sortedCopy(handleIds)
                            + ", unrecordedMounts=" + Math.max(0, count - handleIds.size())
                    ));
        }

        for (DrainSample drain : sample.drains) {
            List<String> rootIds = new ArrayList<>();
            for (String artifactId : drain.closureIds) {
                ManagedArtifactStore.PendingMetadata item = metadata.get(artifactId);
                if (item != null) {
                    rootIds.addAll(item.rootHandleIds());
                }
            }
            operations.add(new PendingOperationsSnapshot.Operation(
                    PendingOperationsSnapshot.Kind.ARTIFACT_DRAIN,
                    drain.artifactId,
                    waitType(drain.phase),
                    drain.age,
                    "phase=" + drain.phase
                            + ", rootIds=" + sortedCopy(rootIds)
                            + ", closureIds=" + sortedCopy(drain.closureIds)));
        }

        // 去重：drain 记录已经描述了自己在协调器上的排队/运行等待；
        // target 命中任一 drain 的操作属于该 drain，不再以 monitor 形式重复上报。
        for (ArtifactCoordinator.CoordinatorOperation operation : coordinatorOperations) {
            if (sample.drainTargets.contains(operation.target())) {
                continue;
            }
            operations.add(new PendingOperationsSnapshot.Operation(
                    PendingOperationsSnapshot.Kind.ARTIFACT_DRAIN,
                    adapterId + "/" + operation.target(),
                    PendingOperationsSnapshot.WaitType.COORDINATOR,
                    operation.age(),
                    "phase=coordinator, state=" + (operation.running() ? "running" : "queued")));
        }
        return new PendingOperationsSnapshot(closeRequested, operations, 0);
    }

    private Sample sample() {
        synchronized (trackerLock) {
            long now = ticker.getAsLong();
            Set<String> artifactIds = new LinkedHashSet<>();
            List<MountSample> mountSamples = new ArrayList<>(mounts.size());
            for (Map.Entry<ManagedArtifact, MountState> entry : mounts.entrySet()) {
                MountState state = entry.getValue();
                if (!state.present) {
                    continue;
                }
                ManagedArtifact artifact = entry.getKey();
                artifactIds.add(artifact.artifactId);
                mountSamples.add(new MountSample(
                        artifact.artifactId,
                        TickerAge.elapsed(state.startTick, now)));
            }
            List<DrainSample> drainSamples = new ArrayList<>(drains.size());
            Set<String> drainTargets = new LinkedHashSet<>();
            for (DrainState state : drains.values()) {
                artifactIds.addAll(state.closureIds);
                drainTargets.add(state.artifactId);
                drainSamples.add(new DrainSample(
                        state.artifactId,
                        state.phase,
                        state.closureIds,
                        TickerAge.elapsed(state.startTick, now)));
            }
            return new Sample(
                    artifactIds,
                    drainTargets,
                    List.copyOf(mountSamples),
                    List.copyOf(drainSamples));
        }
    }

    private static PendingOperationsSnapshot.WaitType waitType(String phase) {
        return switch (phase) {
            case "schedule-coordinator", "coordinator", "schedule-stop-unload" ->
                    PendingOperationsSnapshot.WaitType.COORDINATOR;
            case "wait-mounts" -> PendingOperationsSnapshot.WaitType.MOUNTS_IN_FLIGHT;
            case "dispose-roots" -> PendingOperationsSnapshot.WaitType.COMPONENT;
            case "wait-runtime-close" ->
                    PendingOperationsSnapshot.WaitType.RUNTIME_DRAIN;
            case "stop-unload" -> PendingOperationsSnapshot.WaitType.PF4J_STOP_UNLOAD;
            default -> PendingOperationsSnapshot.WaitType.COORDINATOR;
        };
    }

    private static List<String> sortedCopy(Collection<String> values) {
        List<String> copy = new ArrayList<>(values);
        Collections.sort(copy);
        return List.copyOf(copy);
    }

    private static final class MountState {
        boolean present;
        boolean started;
        long startTick;
        int count;
    }

    private static final class DrainState {
        private final String artifactId;
        private final long startTick;
        private String phase;
        private List<String> closureIds;

        private DrainState(String artifactId, String phase, List<String> closureIds, long startTick) {
            this.artifactId = artifactId;
            this.phase = phase;
            this.closureIds = closureIds;
            this.startTick = startTick;
        }
    }

    private record MountSample(String artifactId, Duration age) {
    }

    private record DrainSample(
            String artifactId,
            String phase,
            List<String> closureIds,
            Duration age) {
    }

    private record Sample(
            Set<String> artifactIds,
            Set<String> drainTargets,
            List<MountSample> mounts,
            List<DrainSample> drains) {
    }
}
