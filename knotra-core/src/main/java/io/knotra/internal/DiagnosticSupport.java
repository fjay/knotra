package io.knotra.internal;

import io.knotra.ComponentState;
import io.knotra.DiagnosticCode;
import io.knotra.RuntimeDiagnostic;

import java.util.List;

/** requireActive 失败路径使用的只读诊断快照。 */
final class DiagnosticSupport {
    private final DefaultKnotraRuntime runtime;

    DiagnosticSupport(DefaultKnotraRuntime runtime) {
        this.runtime = runtime;
    }

    FailureSnapshot failureSnapshot(String handleId) {
        synchronized (runtime.coordinator) {
            RuntimeView current = runtime.currentView();
            List<RuntimeDiagnostic> diagnostics = current.diagnostics.stream()
                    .filter(diagnostic -> handleId.equals(diagnostic.targetId()))
                    .toList();
            return new FailureSnapshot(componentState(current, handleId), diagnostics);
        }
    }

    RuntimeDiagnostic failureDetail(
            String handleId,
            boolean interrupted,
            Throwable settlementError) {
        if (interrupted) {
            return new RuntimeDiagnostic(
                    DiagnosticCode.INVALID_LIFECYCLE_OPERATION,
                    handleId,
                    "wait interrupted before settlement");
        }
        if (settlementError != null) {
            return new RuntimeDiagnostic(
                    DiagnosticCode.ROLLBACK_FAILED,
                    handleId,
                    "settlement failed: " + AwaitSupport.stableError(settlementError));
        }
        return null;
    }

    private ComponentState componentState(RuntimeView current, String handleId) {
        RuntimeView.ComponentData data = current.components.get(handleId);
        return data == null ? ComponentState.DISPOSED : data.state();
    }

    void refresh(RuntimeView.Draft draft) {
        java.util.List<RuntimeDiagnostic> diagnostics = new java.util.ArrayList<>();
        ExecutionIndex index = runtime.publishedState().index;
        RuntimeGraph graph = draft.graph();
        RuntimeGraph.ResolutionCache resolutions = RuntimeGraph.resolutionCache();
        for (RuntimeView.ComponentData component : draft.components.values()) {
            collectComponentDiagnostics(
                    draft, graph, resolutions, component, diagnostics, index);
        }
        draft.diagnostics.clear();
        draft.diagnostics.addAll(diagnostics.stream().sorted().toList());
    }

    private void collectComponentDiagnostics(
            RuntimeView.Draft draft,
            RuntimeGraph graph,
            RuntimeGraph.ResolutionCache resolutions,
            RuntimeView.ComponentData component,
            java.util.List<RuntimeDiagnostic> diagnostics,
            ExecutionIndex index) {
        if (component.state() == io.knotra.ComponentState.WAITING
                && component.goal() == io.knotra.ComponentGoal.RUNNING) {
            collectWaitingDiagnostics(
                    draft, graph, resolutions, component, diagnostics, index);
        }
        if (component.state() == io.knotra.ComponentState.FAILED) {
            collectFailureDiagnostics(component, diagnostics, index);
        }
    }

    private void collectWaitingDiagnostics(
            RuntimeView.Draft draft,
            RuntimeGraph graph,
            RuntimeGraph.ResolutionCache resolutions,
            RuntimeView.ComponentData component,
            java.util.List<RuntimeDiagnostic> diagnostics,
            ExecutionIndex index) {
        for (io.knotra.CapabilityRequirement requirement
                : component.descriptor().sortedRequirements()) {
            if (requirement.mode() != io.knotra.CapabilityRequirement.Mode.REQUIRED) {
                continue;
            }
            boolean present = graph.resolve(
                    draft,
                    java.util.Map.of(),
                    resolutions,
                    component.contextId(),
                    requirement.key()).isPresent();
            if (!present) {
                diagnostics.add(new RuntimeDiagnostic(
                        DiagnosticCode.MISSING_CAPABILITY,
                        component.handleId(),
                        "missing required capability "
                                + requirement.key().name()));
            }
        }
        ComponentRuntime runtimeComponent = index.components.get(component.handleId());
        if (runtimeComponent != null && runtimeComponent.blockedNonConvergent()) {
            diagnostics.add(new RuntimeDiagnostic(
                    DiagnosticCode.NON_CONVERGENT_RECONCILE,
                    component.handleId(),
                    "reconcile did not converge after "
                            + runtime.configuration.maxReconcileIterations()
                            + " attempts"));
        }
        if (runtimeComponent != null && runtimeComponent.suppressAutoRestart()
                && runtimeComponent.lastStartError().startsWith("binding cycle")) {
            diagnostics.add(new RuntimeDiagnostic(
                    DiagnosticCode.BINDING_CYCLE,
                    component.handleId(),
                    runtimeComponent.lastStartError()));
        }
    }

    private void collectFailureDiagnostics(
            RuntimeView.ComponentData component,
            java.util.List<RuntimeDiagnostic> diagnostics,
            ExecutionIndex index) {
        ComponentRuntime runtimeComponent = index.components.get(component.handleId());
        String startError = runtimeComponent == null ? "" : runtimeComponent.lastStartError();
        String cleanupError = runtimeComponent == null ? "" : runtimeComponent.lastCleanupError();
        if (!startError.isBlank()) {
            diagnostics.add(new RuntimeDiagnostic(
                    DiagnosticCode.ACTIVATION_FAILED,
                    component.handleId(),
                    startError,
                    runtimeComponent.lastStartFailure()));
        }
        if (!cleanupError.isBlank()) {
            diagnostics.add(new RuntimeDiagnostic(
                    DiagnosticCode.CLEANUP_FAILED,
                    component.handleId(),
                    cleanupError,
                    runtimeComponent.lastCleanupFailure()));
        }
        if (startError.isBlank() && cleanupError.isBlank()) {
            diagnostics.add(new RuntimeDiagnostic(
                    DiagnosticCode.ACTIVATION_FAILED,
                    component.handleId(),
                    "component failed",
                    runtimeComponent == null
                            ? io.knotra.FailureInfo.EMPTY
                            : runtimeComponent.lastStartFailure()));
        }
    }

    record FailureSnapshot(
            ComponentState state,
            List<RuntimeDiagnostic> diagnostics) {
    }
}
