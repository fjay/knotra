package io.knotra.internal;

import io.knotra.CapabilityRequirement;
import io.knotra.ComponentGoal;
import io.knotra.ComponentState;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 分析注册身份与 OPTIONAL 出现/消失对既有 BindingSet 的影响。 */
final class BindingImpactAnalyzer {
    private final DefaultKnotraRuntime runtime;

    BindingImpactAnalyzer(DefaultKnotraRuntime runtime) {
        this.runtime = runtime;
    }

    // 注册身份或 OPTIONAL 出现/消失都会改变 BindingSet；这里统一把受影响 Activation 标记 stale。
    void markBindingImpacts(
            RuntimeView.Draft draft,
            Set<String> dirty,
            ExecutableCommitPlan executable) {
        Set<String> impacted = new LinkedHashSet<>();
        for (RuntimeView.ComponentData component : draft.components.values()) {
            if (component.currentActivationId() == null) {
                continue;
            }
            RuntimeView.ActivationData activation =
                    draft.activations.get(component.currentActivationId());
            if (activation == null) {
                continue;
            }
            boolean tracksGraph =
                    RuntimeView.activationTracksGraph(activation.state());
            ComponentRuntime executableRuntime =
                    runtime.components.get(component.handleId());
            boolean cleaningForRestart =
                    component.goal() == ComponentGoal.RUNNING
                            && (component.state() == ComponentState.STOPPING
                                || (component.state() == ComponentState.FAILED
                                    && executableRuntime != null
                                    && executableRuntime.failedCleanup != null));
            if (!tracksGraph && !cleaningForRestart) {
                continue;
            }
            ActivationRuntime executableActivation =
                    runtime.activations.get(component.currentActivationId());
            Map<String, RuntimeView.BindingData> previousBindings =
                    tracksGraph || executableActivation == null
                            ? activation.bindings()
                            : executableActivation.bindings;
            Map<String, RuntimeView.BindingData> effective =
                    draft.effectiveBindings(component, Map.of());
            for (CapabilityRequirement requirement
                    : component.descriptor().sortedRequirements()) {
                RuntimeView.BindingData old =
                        previousBindings.get(requirement.key().name());
                RuntimeView.BindingData next =
                        effective.get(requirement.key().name());
                if (!bindingIdentityEqual(old, next)) {
                    impacted.add(component.handleId());
                    executable.staleActivations.add(activation.activationId());
                    break;
                }
            }
        }
        if (!impacted.isEmpty()) {
            Set<String> detachTargets = new LinkedHashSet<>();
            for (String handleId : impacted) {
                RuntimeView.ComponentData component =
                        draft.components.get(handleId);
                if (component == null || component.currentActivationId() == null) {
                    detachTargets.add(handleId);
                    continue;
                }
                detachTargets.addAll(runtime.disposeOwnershipForActivation(
                        draft,
                        handleId,
                        component.currentActivationId(),
                        executable));
            }
            Set<String> closure = draft.dependentsClosure(detachTargets);
            runtime.detachInView(draft, closure, dirty, executable);
        }

        // 曾因拓扑失败而压制的 WAITING 组件，只有在相关拓扑确实变化后才能重置重试预算。
        RuntimeView old = runtime.currentView();
        boolean dynamicTopologyChanged = !globalDynamicDependencyFingerprint(old)
                .equals(globalDynamicDependencyFingerprint(draft.asView()));
        for (RuntimeView.ComponentData component : draft.components.values()) {
            if (component.state() == ComponentState.WAITING
                    && component.goal() == ComponentGoal.RUNNING) {
                RuntimeView.ComponentData previous = old.components.get(
                        component.handleId());
                boolean topologyChanged = previous == null;
                if (!topologyChanged) {
                    Map<String, RuntimeView.BindingData> before =
                            old.effectiveBindings(previous, Map.of());
                    Map<String, RuntimeView.BindingData> after =
                            draft.effectiveBindings(component, Map.of());
                    topologyChanged = !bindingsEqual(before, after)
                            || dynamicTopologyChanged;
                }
                if (topologyChanged) {
                    executable.resetAutoRestart.add(component.handleId());
                    dirty.add(component.handleId());
                }
            }
        }
    }

    // 环可能由其他组件的 DYNAMIC 边触发；指纹只记录存在的 provider owner 边。
    private static String globalDynamicDependencyFingerprint(RuntimeView view) {
        return view.components.values().stream()
                .flatMap(component -> component.descriptor().sortedRequirements().stream()
                        .filter(requirement -> requirement.binding()
                                == CapabilityRequirement.CapabilityBinding.DYNAMIC)
                        .map(requirement -> dynamicDependencyEdge(view, component, requirement)))
                .filter(edge -> edge != null)
                .sorted()
                .collect(java.util.stream.Collectors.joining(";"));
    }

    private static String dynamicDependencyEdge(
            RuntimeView view,
            RuntimeView.ComponentData component,
            CapabilityRequirement requirement) {
        RuntimeView.RegistrationData registration =
                view.resolve(component.contextId(), requirement.key()).orElse(null);
        if (registration == null) {
            return null;
        }
        String owner;
        if (registration.owner() instanceof RuntimeView.OwnerData.Activation activationOwner) {
            RuntimeView.ActivationData activation =
                    view.activations.get(activationOwner.activationId());
            owner = activation == null
                    ? "activation:" + activationOwner.activationId()
                    : "mount:" + activation.handleId();
        } else {
            owner = "host:" + registration.contextId();
        }
        return requirement.key().name() + "=" + owner;
    }

    static boolean bindingsEqual(
            Map<String, RuntimeView.BindingData> left,
            Map<String, RuntimeView.BindingData> right) {
        if (!left.keySet().equals(right.keySet())) {
            return false;
        }
        for (String name : left.keySet()) {
            if (!bindingIdentityEqual(left.get(name), right.get(name))) {
                return false;
            }
        }
        return true;
    }

    // 只比较注册身份和存在性；值 equals 不构成新 BindingSet，但新注册 ID 会构成新代际。
    static boolean bindingIdentityEqual(
            RuntimeView.BindingData left,
            RuntimeView.BindingData right) {
        if (left == null || right == null) {
            return left == right;
        }
        return left.present() == right.present()
                && Objects.equals(left.registrationId(), right.registrationId());
    }

    static Set<String> componentsWithBinding(
            RuntimeView.Draft draft,
            Set<String> registrationIds) {
        Set<String> result = new LinkedHashSet<>();
        for (RuntimeView.ComponentData component : draft.components.values()) {
            if (component.currentActivationId() == null) {
                continue;
            }
            RuntimeView.ActivationData activation =
                    draft.activations.get(component.currentActivationId());
            if (activation == null
                    || !RuntimeView.activationTracksGraph(activation.state())) {
                continue;
            }
            boolean bound = activation.bindings().values().stream()
                    .filter(RuntimeView.BindingData::present)
                    .map(RuntimeView.BindingData::registrationId)
                    .anyMatch(registrationIds::contains);
            if (bound) {
                result.add(component.handleId());
            }
        }
        return result;
    }
}
