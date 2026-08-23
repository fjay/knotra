package io.knotra.internal;

import io.knotra.CapabilityRequirement;
import io.knotra.ComponentGoal;
import io.knotra.ComponentState;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 分析注册身份与 OPTIONAL 出现/消失对既有 BindingSet 的影响。 */
final class BindingImpactAnalyzer {
    // 注册身份或 OPTIONAL 出现/消失都会改变 BindingSet；这里统一把受影响 Activation 标记 stale。
    void markBindingImpacts(
            PublishedKernelState state,
            RuntimeView.Draft draft,
            Set<String> dirty,
            ExecutableCommitPlan executable) {
        Phase oldPhase = Phase.of(state.view);
        Phase nextPhase = Phase.of(draft);
        Set<String> impacted = findChangedBindings(draft, state, nextPhase, executable);

        if (!impacted.isEmpty()) {
            StructureGraphMutator.MutationResult detached =
                    StructureGraphMutator.disposeOwnersAndDetach(
                            draft,
                            impacted,
                            StructureGraphMutator.activePublicationSlotRefs(
                                    state.index, executable));
            detached.applyTo(executable);
            dirty.addAll(detached.dirty());
        }

        resetSuppressedReconcile(draft, state, oldPhase, dirty, executable);
    }

    private Set<String> findChangedBindings(
            RuntimeView.Draft draft,
            PublishedKernelState state,
            Phase phase,
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
                    state.index.components.get(component.handleId());
            boolean cleaningForRestart =
                    component.goal() == ComponentGoal.RUNNING
                            && (component.state() == ComponentState.STOPPING
                                || (component.state() == ComponentState.FAILED
                                    && executableRuntime != null
                                    && executableRuntime.failedCleanup() != null));
            if (!tracksGraph && !cleaningForRestart) {
                continue;
            }
            ActivationRuntime executableActivation =
                    state.index.activations.get(component.currentActivationId());
            Map<String, RuntimeView.BindingData> previousBindings = phase.previousBindings(
                    activation,
                    executableActivation,
                    tracksGraph);
            Map<String, RuntimeView.BindingData> effective =
                    phase.effectiveBindings(draft, component);
            if (hasBindingIdentityChange(component, previousBindings, effective)) {
                impacted.add(component.handleId());
                executable.staleActivations.add(activation.activationId());
            }
        }
        return impacted;
    }

    private void resetSuppressedReconcile(
            RuntimeView.Draft draft,
            PublishedKernelState state,
            Phase oldPhase,
            Set<String> dirty,
            ExecutableCommitPlan executable) {
        RuntimeView old = state.view;
        // detachInView is another mutation. Rebuild once for this final read-only phase.
        Phase resetPhase = Phase.of(draft);
        boolean dynamicTopologyChanged = !oldPhase.dynamicDependencyEdges(old)
                .equals(resetPhase.dynamicDependencyEdges(draft));
        for (RuntimeView.ComponentData component : draft.components.values()) {
            if (component.state() == ComponentState.WAITING
                    && component.goal() == ComponentGoal.RUNNING) {
                RuntimeView.ComponentData previous = old.components.get(component.handleId());
                boolean topologyChanged = previous == null;
                if (!topologyChanged) {
                    Map<String, RuntimeView.BindingData> before =
                            oldPhase.effectiveBindings(old, previous);
                    Map<String, RuntimeView.BindingData> after =
                            resetPhase.effectiveBindings(draft, component);
                    topologyChanged = !bindingsEqual(before, after) || dynamicTopologyChanged;
                }
                if (topologyChanged) {
                    executable.resetAutoRestart.add(component.handleId());
                    dirty.add(component.handleId());
                }
            }
        }
    }

    private static boolean hasBindingIdentityChange(
            RuntimeView.ComponentData component,
            Map<String, RuntimeView.BindingData> previousBindings,
            Map<String, RuntimeView.BindingData> effective) {
        for (CapabilityRequirement requirement
                : component.descriptor().sortedRequirements()) {
            RuntimeView.BindingData old =
                    previousBindings.get(requirement.key().name());
            RuntimeView.BindingData next =
                    effective.get(requirement.key().name());
            if (!bindingIdentityEqual(old, next)) {
                return true;
            }
        }
        return false;
    }

    /**
     * One stable Draft phase. A Phase and its caches are method-local and must be discarded
     * after every structural mutation to the reader that produced it.
     */
    static final class Phase {
        private final RuntimeGraph graph;
        private final RuntimeGraph.ResolutionCache resolutions =
                RuntimeGraph.resolutionCache();
        private final Map<String, Map<String, RuntimeView.BindingData>> effectiveByHandle =
                new HashMap<>();
        private final Map<String, Map<String, RuntimeView.BindingData>> bindingsByActivation =
                new HashMap<>();
        private Set<RuntimeGraph.DynamicEdge> dynamicEdges;

        private Phase(RuntimeViewReader reader) {
            this.graph = RuntimeGraph.of(reader);
        }

        static Phase of(RuntimeViewReader reader) {
            return new Phase(reader);
        }

        RuntimeGraph graph() {
            return graph;
        }

        Map<String, RuntimeView.BindingData> effectiveBindings(
                RuntimeViewReader reader,
                RuntimeView.ComponentData component) {
            return effectiveByHandle.computeIfAbsent(
                    component.handleId(),
                    ignored -> graph.effectiveBindings(
                            reader, Map.of(), resolutions, component));
        }

        Map<String, RuntimeView.BindingData> previousBindings(
                RuntimeView.ActivationData activation,
                ActivationRuntime executableActivation,
                boolean tracksGraph) {
            if (tracksGraph || executableActivation == null) {
                return activation.bindings();
            }
            return bindingsByActivation.computeIfAbsent(
                    activation.activationId(),
                    ignored -> executableActivation.bindings);
        }

        Set<RuntimeGraph.DynamicEdge> dynamicDependencyEdges(RuntimeViewReader reader) {
            Set<RuntimeGraph.DynamicEdge> result = dynamicEdges;
            if (result == null) {
                result = graph.dynamicDependencyEdges(reader);
                dynamicEdges = result;
            }
            return result;
        }
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
