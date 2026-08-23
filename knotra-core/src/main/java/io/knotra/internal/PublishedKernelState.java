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
                Map.of("ctx-root", root));
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
