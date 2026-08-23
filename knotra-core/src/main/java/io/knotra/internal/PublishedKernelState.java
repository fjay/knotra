package io.knotra.internal;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** One atomically published RuntimeView plus its same-generation execution index. */
final class PublishedKernelState {
    // 全量校验是 O(视图规模) 的二次扫描；生产热路径默认关闭，仅在 -ea 断言启用时执行。
    // 结构一致性由协调器内单一发布点 + KernelStateDraft 的同键拷贝纪律保证；
    // KernelStateInvariantTest 仍对每个观察到的代际显式调用 validateInvariants()。
    private static final boolean VALIDATE_ON_PUBLISH =
            PublishedKernelState.class.desiredAssertionStatus();

    final RuntimeView view;
    final ExecutionIndex index;
    final long generation;

    PublishedKernelState(RuntimeView view, ExecutionIndex index) {
        this.view = Objects.requireNonNull(view, "view");
        this.index = Objects.requireNonNull(index, "index");
        this.generation = view.generation;
        if (VALIDATE_ON_PUBLISH) {
            validateInvariants();
        }
    }

    static PublishedKernelState initial(ContextHandleImpl root) {
        RuntimeView view = RuntimeView.initial();
        ExecutionIndex index = new ExecutionIndex(
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of("ctx-root", root),
                Map.of());
        return new PublishedKernelState(view, index);
    }

    /**
     * 全量验证 view 与执行索引的 membership/identity 同代一致。
     *
     * <p>取舍：发布是协调器内的热路径，无条件 O(N) 二次扫描会把每次结构提交的成本
     * 放大一倍以上，因此生产默认只依赖单一发布点与草稿同键拷贝纪律，断言启用时才全量验证；
     * 测试通过显式调用本方法对每个代际做完整校验。</p>
     */
    void validateInvariants() {
        if (generation != view.generation) {
            throw new IllegalStateException(
                    "published kernel state generation mismatch: " + generation
                            + " != " + view.generation);
        }
        requireSameKeys(
                "components", view.components.keySet(), index.components.keySet());
        requireSameKeys(
                "activations", view.activations.keySet(), index.activations.keySet());
        requireSameKeys(
                "contexts", view.contexts.keySet(), index.contextHandles.keySet());

        DefaultKnotraRuntime owner =
                index.contextHandles.get("ctx-root").runtime;
        Set<String> hostRegistrations = new HashSet<>();
        view.registrations.forEach((registrationId, registration) -> {
            if (registration.owner() instanceof RuntimeView.OwnerData.Host) {
                hostRegistrations.add(registrationId);
            }
        });
        requireSameKeys(
                "host registration handles",
                hostRegistrations,
                index.registrationHandles.keySet());
        index.registrationHandles.forEach((registrationId, handle) -> {
            if (!registrationId.equals(handle.registrationId())
                    || handle.runtime != owner) {
                throw new IllegalStateException(
                        "registration handle identity mismatch: " + registrationId);
            }
        });
        requireSameKeys(
                "provider leases",
                view.registrations.keySet(),
                index.providerLeases.keySet());
        view.registrations.forEach((registrationId, registration) -> {
            if (index.providerLeases.get(registrationId) != registration.leases()) {
                throw new IllegalStateException(
                        "provider lease identity mismatch: " + registrationId);
            }
        });
        view.components.forEach((handleId, data) -> {
            ComponentRuntime runtime = index.components.get(handleId);
            if (runtime == null
                    || !handleId.equals(runtime.handleId())
                    || !data.contextId().equals(runtime.contextId())
                    || !data.mountId().equals(runtime.mountId())) {
                throw new IllegalStateException(
                        "component runtime identity mismatch: " + handleId);
            }
            MountHandleImpl handle = index.componentHandles.get(handleId);
            if (handle == null
                    || !handleId.equals(handle.handleId())
                    || handle.runtime != owner) {
                throw new IllegalStateException(
                        "component handle identity mismatch: " + handleId);
            }
        });

        view.activations.forEach((activationId, data) -> {
            ActivationRuntime runtime = index.activations.get(activationId);
            if (runtime == null
                    || !activationId.equals(runtime.activationId)
                    || !data.handleId().equals(runtime.owner.handleId())
                    || index.components.get(data.handleId()) != runtime.owner) {
                throw new IllegalStateException(
                        "activation runtime identity mismatch: " + activationId);
            }
        });

        view.contexts.forEach((contextId, ignored) -> {
            ContextHandleImpl handle = index.contextHandles.get(contextId);
            if (handle == null
                    || !contextId.equals(handle.contextId())
                    || handle.runtime != owner) {
                throw new IllegalStateException("context handle identity mismatch: " + contextId);
            }
        });

        if (!view.contexts.containsKey("ctx-root")
                || index.contextHandles.get("ctx-root") == null) {
            throw new IllegalStateException("root context must remain published");
        }
        validatePublicationSlots(view, index);
    }

    /**
     * 校验 Publication 槽位与注册/ref 的同代一致：
     * 视图与坐标索引只含活跃（PUBLISHED）槽位且一一对应；
     * 活跃槽位的 current 注册必须存在于本代视图且 slotId/context/name/type 双向匹配；
     * ExecutionIndex 的 publicationSlotRefs 与活跃槽位 ID 一一对应（同 slotId 同 ref 实例）；
     * raw 注册（slotId null）不进入槽位结构。
     */
    private static void validatePublicationSlots(
            RuntimeView view, ExecutionIndex index) {
        Set<RuntimeView.PublicationSlotKey> activeKeys = new HashSet<>();
        for (RuntimeView.PublicationSlotData slot : view.publicationSlots.values()) {
            activeKeys.add(new RuntimeView.PublicationSlotKey(
                    slot.contextId(), slot.capabilityName()));
            RuntimeView.RegistrationData current =
                    view.registrations.get(slot.currentRegistrationId());
            if (current == null
                    || !slot.slotId().equals(current.publicationSlotId())
                    || !slot.contextId().equals(current.contextId())
                    || !slot.capabilityName().equals(current.key().name())
                    || !slot.typeName().equals(current.key().typeName())) {
                throw new IllegalStateException(
                        "published slot current registration mismatch: "
                                + slot.slotId());
            }
        }
        if (!activeKeys.equals(view.activePublicationSlots.keySet())) {
            throw new IllegalStateException(
                    "active publication slot coordinates differ from published slots");
        }
        view.activePublicationSlots.forEach((key, slot) -> {
            if (view.publicationSlots.get(slot.slotId()) != slot
                    || !key.equals(new RuntimeView.PublicationSlotKey(
                            slot.contextId(), slot.capabilityName()))) {
                throw new IllegalStateException(
                        "active publication slot identity mismatch: " + slot.slotId());
            }
        });
        view.registrations.forEach((registrationId, registration) -> {
            String slotId = registration.publicationSlotId();
            if (slotId == null) {
                return;
            }
            RuntimeView.PublicationSlotData slot = view.publicationSlots.get(slotId);
            if (slot == null
                    || !registrationId.equals(slot.currentRegistrationId())) {
                throw new IllegalStateException(
                        "registration references a non-current publication slot: "
                                + registrationId);
            }
        });
        requireSameKeys(
                "publication slot refs",
                view.publicationSlots.keySet(),
                index.publicationSlotRefs.keySet());
        index.publicationSlotRefs.forEach((slotId, ref) -> {
            if (!slotId.equals(ref.slotId)) {
                throw new IllegalStateException(
                        "publication slot ref identity mismatch: " + slotId);
            }
        });
    }

    private static void requireSameKeys(String name, Set<?> first, Set<?> second) {
        if (!first.equals(second)) {
            Map<Object, Boolean> diagnostic = new HashMap<>();
            first.forEach(key -> diagnostic.put(key, true));
            second.forEach(key -> diagnostic.merge(key, true, Boolean::equals));
            throw new IllegalStateException(
                    name + " membership differs between view and index: " + diagnostic);
        }
    }
}
