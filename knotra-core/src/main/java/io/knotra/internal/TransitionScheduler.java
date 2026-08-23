package io.knotra.internal;

import io.knotra.ComponentGoal;
import io.knotra.ComponentState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.IntConsumer;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;

/**
 * 组件过渡的预约、拓扑排序与锁外驱动器。
 *
 * <p>调度器只拥有协调器锁标识、执行器和 {@link TransitionDriver} 回调；不持有 runtime、
 * KernelStateStore 或已发布状态。结构草稿和执行索引仍由 runtime 构造/发布；本类在
 * coordinator 临界区内只创建可取消的 transition slot，用户状态机和 future 回调一律在锁外
 * 提交。锁顺序固定为 coordinator -> chainLock -> pendingLock。</p>
 */
final class TransitionScheduler {
    private final Object coordinatorLock;
    private final Executor executor;
    private final TransitionDriver driver;
    private final LongSupplier clock;

    // 包内测试探针：预约完成后、返回调用方前暂停或注入故障。
    volatile Runnable transitionReservationProbe;
    // 包内测试探针：在第 N 个过渡预约创建前注入故障；参数为调用方预约序号。
    volatile IntConsumer transitionReservationFaultProbe;

    TransitionScheduler(
            Object coordinatorLock,
            Executor executor,
            TransitionDriver driver,
            LongSupplier clock) {
        this.coordinatorLock = Objects.requireNonNull(coordinatorLock, "coordinatorLock");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.driver = Objects.requireNonNull(driver, "driver");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 在调用方持有的 coordinator 临界区内预约草稿过渡。
     *
     * <p>{@code reservationSink} 让 prepublish 中途失败时仍能取消部分预约；返回的 plan 只包含
     * 本次调用创建/合并的预约，不进入 Kernel snapshot，也不延长已移除组件的生命周期。</p>
     */
    TransitionPlan prepare(
            PublishedKernelState state,
            RuntimeView.Draft draft,
            Set<String> dirty,
            ExecutableCommitPlan executable,
            KernelStateDraft indexDraft,
            List<ComponentRuntime.Reservation> reservationSink) {
        assert Thread.holdsLock(coordinatorLock);
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(draft, "draft");
        Objects.requireNonNull(executable, "executable");
        Objects.requireNonNull(indexDraft, "indexDraft");
        Objects.requireNonNull(reservationSink, "reservationSink");
        if (indexDraft.base() != state) {
            throw new IllegalStateException("transition draft is not based on caller state");
        }

        precreateMountedRuntimes(draft, executable, indexDraft, coordinatorLock);
        Set<String> stopping = new LinkedHashSet<>();
        Set<String> starting = new LinkedHashSet<>();
        for (String handleId : dirty) {
            RuntimeView.ComponentData data = draft.components.get(handleId);
            if (data == null) {
                continue;
            }
            if (data.state() == ComponentState.STOPPING) {
                stopping.add(handleId);
            } else if ((data.state() == ComponentState.WAITING
                    || data.state() == ComponentState.FAILED)
                    && data.goal() == ComponentGoal.RUNNING) {
                starting.add(handleId);
            }
        }

        List<ComponentRuntime.Reservation> planned = new ArrayList<>();
        List<String> createdIds = new ArrayList<>();
        List<String> orderedIds = new ArrayList<>();
        for (String handleId : orderForStop(
                draft, stopping, indexDraft.activations())) {
            reserve(
                    handleId,
                    draft,
                    executable,
                    indexDraft,
                    reservationSink,
                    planned,
                    createdIds);
            orderedIds.add(handleId);
        }
        for (String handleId : starting.stream().sorted().toList()) {
            reserve(
                    handleId,
                    draft,
                    executable,
                    indexDraft,
                    reservationSink,
                    planned,
                    createdIds);
            orderedIds.add(handleId);
        }
        runReservationProbe();
        return TransitionPlan.of(planned, createdIds, orderedIds);
    }

    /**
     * 在调用方持有的 coordinator 临界区内为补调度生成最新草稿预约。
     * state 必须由 runtime 在同一临界区内读取；drive 由调用方离开锁后执行。
     */
    TransitionPlan schedule(
            PublishedKernelState state,
            Set<String> dirty,
            List<ComponentRuntime.Reservation> reservations) {
        assert Thread.holdsLock(coordinatorLock);
        RuntimeView.Draft draft = new RuntimeView.Draft(state.view);
        KernelStateDraft indexDraft = new KernelStateDraft(state);
        return prepare(
                state,
                draft,
                dirty,
                new ExecutableCommitPlan(),
                indexDraft,
                reservations);
    }

    /** 离开 coordinator 后提交已创建预约的唯一 driver；重复 drive 合并预约是 no-op。 */
    String drive(TransitionPlan plan) {
        assert !Thread.holdsLock(coordinatorLock);
        String failure = null;
        for (ComponentRuntime.Reservation reservation : plan.reservations()) {
            failure = appendFailure(
                    failure,
                    driveReservation(reservation));
        }
        return failure;
    }

    String driveReservation(ComponentRuntime.Reservation reservation) {
        assert !Thread.holdsLock(coordinatorLock);
        if (!reservation.created()) {
            return null;
        }
        try {
            reservation.component().executeReserved(
                    executor,
                    driver,
                    reservation.future());
        } catch (Throwable driveError) {
            String message = "postcommit transition drive failed at reservation execute: "
                    + LifecycleScopeImpl.safeError(driveError);
            try {
                reservation.component().failTransition(
                        reservation.future(),
                        new IllegalStateException(message)).run();
            } catch (Throwable completeError) {
                message += "; reservation failure completion failed: "
                        + LifecycleScopeImpl.safeError(completeError);
            }
            return message;
        }
        return null;
    }

    /** 清理本计划/调用方清单中由本次创建的预约槽；完成动作必须随后在锁外派发。 */
    List<CompletableFuture<ComponentState>> cancelCreated(TransitionPlan plan) {
        return cancelCreated(plan.reservations());
    }

    List<CompletableFuture<ComponentState>> cancelCreated(
            List<ComponentRuntime.Reservation> reservations) {
        List<CompletableFuture<ComponentState>> cancelled = new ArrayList<>();
        for (ComponentRuntime.Reservation reservation : reservations) {
            if (reservation.created()
                    && reservation.component().cancelTransition(reservation.future())) {
                cancelled.add(reservation.future());
            }
        }
        return cancelled;
    }

    void completeCancelled(
            List<CompletableFuture<ComponentState>> cancelled) {
        assert !Thread.holdsLock(coordinatorLock);
        for (CompletableFuture<ComponentState> future : cancelled) {
            future.completeExceptionally(new TransitionCancelledStateException());
        }
    }

    /** 独立请求（observer/retry/restart）使用组件自己的展示意图预约并立即驱动。 */
    CompletableFuture<ComponentState> enqueue(ComponentRuntime component) {
        return component.enqueue(this);
    }

    long pendingTime() {
        return clock.getAsLong();
    }

    Map<String, Set<String>> stopProviders(
            PublishedKernelState state,
            Set<String> handles) {
        return stopProviders(state.view, handles, state.index.activations);
    }

    private static void precreateMountedRuntimes(
            RuntimeView.Draft draft,
            ExecutableCommitPlan executable,
            KernelStateDraft indexDraft,
            Object coordinatorLock) {
        for (MountIntent mount : executable.mounts.values()) {
            String handleId = mount.handle().handleId();
            if (!draft.components.containsKey(handleId)
                    || indexDraft.components().containsKey(handleId)) {
                continue;
            }
            executable.componentRuntimes.computeIfAbsent(handleId, ignored ->
                    new ComponentRuntime(
                            handleId,
                            mount.context().contextId(),
                            mount.mountId(),
                            mount.prepared(),
                            coordinatorLock));
        }
    }

    private void reserve(
            String handleId,
            RuntimeView.Draft draft,
            ExecutableCommitPlan executable,
            KernelStateDraft indexDraft,
            List<ComponentRuntime.Reservation> reservationSink,
            List<ComponentRuntime.Reservation> planned,
            List<String> createdIds) {
        ComponentRuntime runtime = executable.componentRuntimes
                .computeIfAbsent(handleId, indexDraft.components()::get);
        if (runtime == null) {
            return;
        }
        RuntimeView.ComponentData data = draft.components.get(handleId);
        runReservationFaultProbe(reservationSink.size());
        ComponentRuntime.Reservation reservation =
                runtime.reserveTransition(pendingTime(), transitionDetail(data));
        reservationSink.add(reservation);
        planned.add(reservation);
        if (reservation.created()) {
            createdIds.add(handleId);
        }
    }

    private static String transitionDetail(RuntimeView.ComponentData data) {
        if (data == null) {
            return "component transition";
        }
        if (data.state() == ComponentState.STOPPING) {
            return data.goal() == ComponentGoal.DISPOSED
                    ? "component dispose"
                    : "component stop";
        }
        return data.state() == ComponentState.FAILED
                ? "component restart"
                : "component activation start";
    }

    private void runReservationFaultProbe(int reservationIndex) {
        IntConsumer probe = transitionReservationFaultProbe;
        if (probe != null) {
            probe.accept(reservationIndex);
        }
    }

    private void runReservationProbe() {
        Runnable probe = transitionReservationProbe;
        if (probe != null) {
            probe.run();
        }
    }

    private static String appendFailure(String current, String failure) {
        if (failure == null) {
            return current;
        }
        return current == null ? failure : current + "; " + failure;
    }

    // Kahn 拓扑排序先排无提供方，再整体反转，得到依赖方先于提供方的停止顺序。
    private static List<String> orderForStop(
            RuntimeViewReader current,
            Set<String> handles,
            Map<String, ActivationRuntime> activationRuntimes) {
        if (handles.isEmpty()) {
            return List.of();
        }
        Map<String, Set<String>> providers =
                stopProviders(current, handles, activationRuntimes);
        Map<String, Integer> degree = new TreeMap<>();
        Map<String, Set<String>> dependents = new TreeMap<>();
        for (String handleId : handles) {
            Set<String> targets = providers.getOrDefault(handleId, Set.of());
            degree.put(handleId, targets.size());
            for (String provider : targets) {
                dependents.computeIfAbsent(provider, ignored -> new LinkedHashSet<>())
                        .add(handleId);
            }
        }
        List<String> ready = degree.entrySet().stream()
                .filter(entry -> entry.getValue() == 0)
                .map(Map.Entry::getKey)
                .sorted()
                .collect(Collectors.toCollection(ArrayList::new));
        List<String> ordered = new ArrayList<>();
        Set<String> visited = new LinkedHashSet<>();
        while (!ready.isEmpty()) {
            String currentHandle = ready.removeFirst();
            if (!visited.add(currentHandle)) {
                continue;
            }
            ordered.add(currentHandle);
            for (String dependent : dependents.getOrDefault(currentHandle, Set.of())) {
                int next = degree.merge(dependent, -1, Integer::sum);
                if (next == 0) {
                    ready.add(dependent);
                }
            }
        }
        handles.stream()
                .filter(handleId -> !visited.contains(handleId))
                .sorted()
                .forEach(ordered::add);
        Collections.reverse(ordered);
        return ordered;
    }

    // 将注册归属还原为提供方 MountHandle，只保留本次也在停止集合内的内部依赖。
    private static Map<String, Set<String>> stopProviders(
            RuntimeViewReader current,
            Set<String> handles,
            Map<String, ActivationRuntime> activationRuntimes) {
        Map<String, String> activationOwners = new HashMap<>();
        current.activations().values().forEach(activation ->
                activationOwners.put(activation.activationId(), activation.handleId()));
        Map<String, Set<String>> result = new TreeMap<>();
        for (String handleId : handles) {
            RuntimeView.ComponentData component = current.components().get(handleId);
            if (component == null || component.currentActivationId() == null) {
                continue;
            }
            ActivationRuntime activation = activationRuntimes.get(
                    component.currentActivationId());
            if (activation == null) {
                continue;
            }
            Set<String> providerHandles = new LinkedHashSet<>();
            for (RuntimeView.BindingData binding : activation.bindings.values()) {
                if (!binding.present()) {
                    continue;
                }
                String providerHandle = providerHandleForRegistration(
                        current,
                        activationOwners,
                        activationRuntimes,
                        binding.registrationId());
                if (providerHandle != null
                        && handles.contains(providerHandle)
                        && !providerHandle.equals(handleId)) {
                    providerHandles.add(providerHandle);
                }
            }
            result.put(handleId, providerHandles);
        }
        return result;
    }

    private static String providerHandleForRegistration(
            RuntimeViewReader current,
            Map<String, String> activationOwners,
            Map<String, ActivationRuntime> activationRuntimes,
            String registrationId) {
        RuntimeView.RegistrationData registration =
                current.registrations().get(registrationId);
        String ownerActivationId = null;
        if (registration != null
                && registration.owner() instanceof RuntimeView.OwnerData.Activation owner) {
            ownerActivationId = owner.activationId();
        } else {
            // 正在启动的提供方可能仍未发布注册；用暂存表识别它，避免新依赖方与提供方重叠清理。
            for (ActivationRuntime activation : activationRuntimes.values()) {
                boolean owns = activation.stagedRegistrations.values().stream()
                        .map(RuntimeView.RegistrationData::registrationId)
                        .anyMatch(registrationId::equals);
                if (owns) {
                    ownerActivationId = activation.activationId;
                    break;
                }
            }
        }
        return ownerActivationId == null ? null : activationOwners.get(ownerActivationId);
    }
}
