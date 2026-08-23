package io.knotra.internal;

import io.knotra.CapabilityKey;
import io.knotra.CapabilityRequirement;
import io.knotra.ComponentDescriptor;
import io.knotra.ComponentState;
import io.knotra.ContextHandle;
import io.knotra.KnotraRuntime;
import io.knotra.MountFactory;
import io.knotra.MountHandle;
import io.knotra.RuntimeSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RuntimeView 规模基线 harness（只读采集，不写任何文件）。
 *
 * <p>拓扑为 {@code knotra.perf.scale} 个 WAITING 组件，每个组件要求一个独立缺失能力。
 * 每轮在全新 Runtime 上依次测量四个阶段：批量挂载事务、100 次顺序能力发布（无命中，
 * 制造注册表复制放大与 binding impact 扫描成本）、10 次互不相关的 provide（每次触发
 * 一个真实 binding impact）、10 次全局快照。3 轮 warmup + 5 轮 measure，输出每阶段与
 * 单操作最大耗时的 median/min/max 到测试日志。</p>
 *
 * <p>断言策略刻意宽松：所有规模都校验 Kernel invariant 与数量；仅当 scale &ge; 1000
 * 时要求每个操作实例落在 60 秒预算内（防挂死护栏，不做百分比回归门槛，阶段总量只
 * 记录不断言）。顺序发布次数固定为 100 而不是 N，确保显式 1000 规模也能落在父 POM
 * surefire fork 600 秒超时内。运行方式：
 * {@code mvn -pl knotra-core test -Dtest=RuntimeScaleHarnessTest -Dknotra.perf.scale=1000}。</p>
 */
final class RuntimeScaleHarnessTest {

    private static final String SCALE_PROPERTY = "knotra.perf.scale";
    private static final int DEFAULT_SCALE = 100;
    private static final int MIN_SCALE = 10;
    private static final int MAX_SCALE = 20_000;
    private static final int WARMUP_ROUNDS = 3;
    private static final int MEASURE_ROUNDS = 5;
    private static final int IMPACT_PROVIDES = 10;
    private static final int SNAPSHOT_READS = 10;
    private static final int SEQUENTIAL_PUBLISHES = 100;
    private static final int PUBLISH_PROGRESS_STRIDE = 100;
    private static final int BUDGET_ASSERT_SCALE = 1000;
    private static final Duration SETTLE_BOUND = Duration.ofSeconds(60);
    private static final Duration CLOSE_BOUND = Duration.ofSeconds(120);
    private static final long OPERATION_BUDGET_NANOS = Duration.ofSeconds(60).toNanos();

    private static final int SCALE = resolveScale();

    private static int resolveScale() {
        int value = Integer.getInteger(SCALE_PROPERTY, DEFAULT_SCALE);
        if (value < MIN_SCALE || value > MAX_SCALE) {
            throw new IllegalArgumentException(SCALE_PROPERTY
                    + " must be within [" + MIN_SCALE + ", " + MAX_SCALE + "] but was " + value);
        }
        return value;
    }

    private record RoundTimings(
            long batchMountNanos,
            long sequentialPublishNanos,
            long maxSinglePublishNanos,
            long bindingImpactNanos,
            long maxSingleProvideNanos,
            long snapshotNanos,
            long maxSingleSnapshotNanos) {
    }

    @Test
    void runtimeScalePhasesReportBaselineAndStayWithinLooseBudget() throws Throwable {
        long[] batchMount = new long[MEASURE_ROUNDS];
        long[] sequentialPublish = new long[MEASURE_ROUNDS];
        long[] maxSinglePublish = new long[MEASURE_ROUNDS];
        long[] bindingImpact = new long[MEASURE_ROUNDS];
        long[] maxSingleProvide = new long[MEASURE_ROUNDS];
        long[] snapshot = new long[MEASURE_ROUNDS];
        long[] maxSingleSnapshot = new long[MEASURE_ROUNDS];

        System.out.printf("KNOTRA PERF harness=RuntimeScaleHarnessTest scale=%d warmup=%d measure=%d java=%s%n",
                SCALE, WARMUP_ROUNDS, MEASURE_ROUNDS, System.getProperty("java.version"));

        for (int round = 1; round <= WARMUP_ROUNDS + MEASURE_ROUNDS; round++) {
            RoundTimings timings = runRound(round);
            String label = round <= WARMUP_ROUNDS ? "warmup" : "measure";
            System.out.printf(
                    "KNOTRA PERF %s round=%d batch-mount-transaction=%.3fms"
                            + " sequential-publish=%.3fms(max-op=%.3fms)"
                            + " binding-impact-provide=%.3fms(max-op=%.3fms)"
                            + " snapshot=%.3fms(max-op=%.3fms)%n",
                    label, round,
                    millis(timings.batchMountNanos()),
                    millis(timings.sequentialPublishNanos()), millis(timings.maxSinglePublishNanos()),
                    millis(timings.bindingImpactNanos()), millis(timings.maxSingleProvideNanos()),
                    millis(timings.snapshotNanos()), millis(timings.maxSingleSnapshotNanos()));
            if (round > WARMUP_ROUNDS) {
                int sample = round - WARMUP_ROUNDS - 1;
                batchMount[sample] = timings.batchMountNanos();
                sequentialPublish[sample] = timings.sequentialPublishNanos();
                maxSinglePublish[sample] = timings.maxSinglePublishNanos();
                bindingImpact[sample] = timings.bindingImpactNanos();
                maxSingleProvide[sample] = timings.maxSingleProvideNanos();
                snapshot[sample] = timings.snapshotNanos();
                maxSingleSnapshot[sample] = timings.maxSingleSnapshotNanos();
            }
        }

        reportStats("batch-mount-transaction", batchMount);
        reportStats("sequential-publish", sequentialPublish);
        reportStats("sequential-publish-max-op", maxSinglePublish);
        reportStats("binding-impact-provide", bindingImpact);
        reportStats("binding-impact-provide-max-op", maxSingleProvide);
        reportStats("snapshot", snapshot);
        reportStats("snapshot-max-op", maxSingleSnapshot);

        if (SCALE >= BUDGET_ASSERT_SCALE) {
            assertLooseBudget("batch-mount-transaction", batchMount);
            assertLooseBudget("sequential-publish-op", maxSinglePublish);
            assertLooseBudget("binding-impact-provide-op", maxSingleProvide);
            assertLooseBudget("snapshot-op", maxSingleSnapshot);
        }
        System.out.println("KNOTRA PERF baseline-complete");
    }

    private RoundTimings runRound(int round) throws Throwable {
        KnotraRuntime runtime = KnotraRuntime.create();
        Throwable primary = null;
        try {
            return executeRound(round, runtime);
        } catch (Throwable failure) {
            primary = failure;
            throw failure;
        } finally {
            try {
                runtime.closeAsync().toCompletableFuture()
                        .get(CLOSE_BOUND.toMillis(), TimeUnit.MILLISECONDS);
            } catch (Throwable closeFailure) {
                if (primary == null) {
                    throw closeFailure;
                }
                primary.addSuppressed(closeFailure);
            }
        }
    }

    private RoundTimings executeRound(int round, KnotraRuntime runtime) throws Exception {
        System.out.printf("KNOTRA PERF round=%d start scale=%d%n", round, SCALE);
        DefaultKnotraRuntime internal = (DefaultKnotraRuntime) runtime;
        ContextHandle root = runtime.root();
        List<MountFactory> factories = waitingFactories();

        long begin = System.nanoTime();
        List<MountHandle> handles = runtime.advanced().transact(transaction -> {
            List<MountHandle> staged = new ArrayList<>(SCALE);
            for (int i = 0; i < SCALE; i++) {
                staged.add(transaction.mount(root, "mount-" + i, factories.get(i)));
            }
            return staged;
        }).value();
        long batchMountNanos = System.nanoTime() - begin;
        progress("batch-mount-transaction", batchMountNanos);

        begin = System.nanoTime();
        for (MountHandle handle : handles) {
            assertEquals(ComponentState.WAITING,
                    handle.whenSettled().toCompletableFuture().get(60, TimeUnit.SECONDS));
        }
        progress("waiting-verification", System.nanoTime() - begin);
        assertKernelState(internal, SCALE, 0);

        begin = System.nanoTime();
        long publishLoopStart = begin;
        long maxSinglePublishNanos = 0;
        for (int i = 0; i < SEQUENTIAL_PUBLISHES; i++) {
            long opStart = System.nanoTime();
            runtime.publish(CapabilityKey.of("unrelated-cap-" + i, String.class), "value-" + i)
                    .awaitSettled(SETTLE_BOUND);
            maxSinglePublishNanos = Math.max(maxSinglePublishNanos, System.nanoTime() - opStart);
            if ((i + 1) % PUBLISH_PROGRESS_STRIDE == 0) {
                System.out.printf("KNOTRA PERF progress phase=sequential-publish"
                                + " published=%d/%d loop=%.3fms max-op=%.3fms scale=%d%n",
                        i + 1, SEQUENTIAL_PUBLISHES, millis(System.nanoTime() - publishLoopStart),
                        millis(maxSinglePublishNanos), SCALE);
            }
        }
        long sequentialPublishNanos = System.nanoTime() - begin;
        progress("sequential-publish", sequentialPublishNanos);
        assertKernelState(internal, SCALE, SEQUENTIAL_PUBLISHES);

        List<MountHandle> impacted = new ArrayList<>(IMPACT_PROVIDES);
        begin = System.nanoTime();
        long maxSingleProvideNanos = 0;
        for (int k = 0; k < IMPACT_PROVIDES; k++) {
            int target = impactIndex(k);
            impacted.add(handles.get(target));
            long opStart = System.nanoTime();
            runtime.advanced().transact(transaction -> transaction.provide(
                    root,
                    CapabilityKey.of("missing-cap-" + target, String.class),
                    "impact-" + target)).awaitSettled(SETTLE_BOUND);
            maxSingleProvideNanos = Math.max(maxSingleProvideNanos, System.nanoTime() - opStart);
        }
        long bindingImpactNanos = System.nanoTime() - begin;
        progress("binding-impact-provide", bindingImpactNanos);
        for (MountHandle handle : impacted) {
            assertEquals(ComponentState.ACTIVE,
                    handle.whenSettled().toCompletableFuture().get(60, TimeUnit.SECONDS));
        }
        assertKernelState(internal, SCALE, SEQUENTIAL_PUBLISHES + IMPACT_PROVIDES);

        begin = System.nanoTime();
        long maxSingleSnapshotNanos = 0;
        RuntimeSnapshot last = null;
        for (int i = 0; i < SNAPSHOT_READS; i++) {
            long opStart = System.nanoTime();
            last = runtime.advanced().snapshot();
            maxSingleSnapshotNanos = Math.max(maxSingleSnapshotNanos, System.nanoTime() - opStart);
        }
        long snapshotNanos = System.nanoTime() - begin;
        progress("snapshot", snapshotNanos);

        assertKernelState(internal, SCALE, SEQUENTIAL_PUBLISHES + IMPACT_PROVIDES);
        assertEquals(SCALE, last.mounts().size());
        assertEquals(SEQUENTIAL_PUBLISHES + IMPACT_PROVIDES, last.registrations().size());
        long active = last.mounts().stream()
                .filter(mount -> mount.state() == ComponentState.ACTIVE).count();
        long waiting = last.mounts().stream()
                .filter(mount -> mount.state() == ComponentState.WAITING).count();
        assertEquals(IMPACT_PROVIDES, active);
        assertEquals(SCALE - IMPACT_PROVIDES, waiting);

        return new RoundTimings(batchMountNanos,
                sequentialPublishNanos, maxSinglePublishNanos,
                bindingImpactNanos, maxSingleProvideNanos,
                snapshotNanos, maxSingleSnapshotNanos);
    }

    private static List<MountFactory> waitingFactories() {
        List<MountFactory> factories = new ArrayList<>(SCALE);
        for (int i = 0; i < SCALE; i++) {
            CapabilityKey<String> missing = CapabilityKey.of("missing-cap-" + i, String.class);
            factories.add(MountFactory.of(
                    "factory-" + i,
                    ComponentDescriptor.named(
                            "component-" + i, CapabilityRequirement.required(missing)),
                    context -> { }));
        }
        return factories;
    }

    private static int impactIndex(int step) {
        return (int) Math.round((double) step * (SCALE - 1) / (IMPACT_PROVIDES - 1));
    }

    private static void assertKernelState(
            DefaultKnotraRuntime runtime,
            int expectedComponents,
            long expectedRegistrations) {
        PublishedKernelState state = runtime.publishedState();
        state.validateInvariants();
        assertEquals(expectedComponents, state.view.components.size(), "component count");
        assertEquals(expectedRegistrations, state.view.registrations.size(), "registration count");
    }

    private static void reportStats(String phase, long[] nanos) {
        long[] sorted = nanos.clone();
        Arrays.sort(sorted);
        System.out.printf("KNOTRA PERF phase=%s median=%.3fms min=%.3fms max=%.3fms%n",
                phase,
                millis(sorted[sorted.length / 2]),
                millis(sorted[0]),
                millis(sorted[sorted.length - 1]));
    }

    private static void assertLooseBudget(String phase, long[] nanos) {
        long worst = Arrays.stream(nanos).max().orElseThrow();
        assertTrue(worst <= OPERATION_BUDGET_NANOS,
                () -> phase + " exceeded loose budget: max=" + millis(worst)
                        + "ms budget=" + Duration.ofNanos(OPERATION_BUDGET_NANOS).toMillis()
                        + "ms scale=" + SCALE);
    }

    private static void progress(String phase, long nanos) {
        System.out.printf("KNOTRA PERF progress phase=%s elapsed=%.3fms scale=%d%n",
                phase, millis(nanos), SCALE);
    }

    private static double millis(long nanos) {
        return nanos / 1_000_000.0;
    }
}
