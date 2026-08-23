package io.knotra.internal;

import io.knotra.ComponentState;
import io.knotra.DiagnosticCode;
import io.knotra.KnotraConfig;
import io.knotra.RuntimeDiagnostic;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Pure input-only diagnostic helpers used by coordinator commit paths. */
final class DiagnosticSupport {
    private DiagnosticSupport() {
    }

    static FailureSnapshot failureSnapshot(
            PublishedKernelState state,
            String handleId) {
        RuntimeView current = state.view;
        List<RuntimeDiagnostic> diagnostics = current.diagnostics.stream()
                .filter(diagnostic -> handleId.equals(diagnostic.targetId()))
                .toList();
        return new FailureSnapshot(componentState(current, handleId), diagnostics);
    }

    static RuntimeDiagnostic failureDetail(
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

    private static ComponentState componentState(
            RuntimeView current,
            String handleId) {
        RuntimeView.ComponentData data = current.components.get(handleId);
        return data == null ? ComponentState.DISPOSED : data.state();
    }

    static void refresh(
            RuntimeView.Draft draft,
            ExecutionIndex index,
            KnotraConfig configuration) {
        List<RuntimeDiagnostic> diagnostics = new ArrayList<>();
        RuntimeGraph graph = draft.graph();
        RuntimeGraph.ResolutionCache resolutions = RuntimeGraph.resolutionCache();
        for (RuntimeView.ComponentData component : draft.components.values()) {
            collectComponentDiagnostics(
                    draft, graph, resolutions, component, diagnostics, index, configuration);
        }
        draft.diagnostics.clear();
        draft.diagnostics.addAll(diagnostics.stream().sorted().toList());
    }

    private static void collectComponentDiagnostics(
            RuntimeView.Draft draft,
            RuntimeGraph graph,
            RuntimeGraph.ResolutionCache resolutions,
            RuntimeView.ComponentData component,
            List<RuntimeDiagnostic> diagnostics,
            ExecutionIndex index,
            KnotraConfig configuration) {
        if (component.state() == ComponentState.WAITING
                && component.goal() == io.knotra.ComponentGoal.RUNNING) {
            collectWaitingDiagnostics(
                    draft, graph, resolutions, component, diagnostics, index, configuration);
        }
        if (component.state() == ComponentState.FAILED) {
            collectFailureDiagnostics(component, diagnostics, index);
        }
    }

    private static void collectWaitingDiagnostics(
            RuntimeView.Draft draft,
            RuntimeGraph graph,
            RuntimeGraph.ResolutionCache resolutions,
            RuntimeView.ComponentData component,
            List<RuntimeDiagnostic> diagnostics,
            ExecutionIndex index,
            KnotraConfig configuration) {
        for (io.knotra.CapabilityRequirement requirement
                : component.descriptor().sortedRequirements()) {
            if (requirement.mode() != io.knotra.CapabilityRequirement.Mode.REQUIRED) {
                continue;
            }
            boolean present = graph.resolve(
                    draft,
                    Map.of(),
                    resolutions,
                    component.contextId(),
                    requirement.key()).isPresent();
            if (!present) {
                diagnostics.add(new RuntimeDiagnostic(
                        DiagnosticCode.MISSING_CAPABILITY,
                        component.handleId(),
                        "missing required capability " + requirement.key().name()));
            }
        }
        ComponentRuntime runtimeComponent = index.components.get(component.handleId());
        if (runtimeComponent != null && runtimeComponent.blockedNonConvergent()) {
            diagnostics.add(new RuntimeDiagnostic(
                    DiagnosticCode.NON_CONVERGENT_RECONCILE,
                    component.handleId(),
                    "reconcile did not converge after "
                            + configuration.maxReconcileIterations()
                            + " attempts"));
        }
        if (runtimeComponent != null
                && runtimeComponent.suppressAutoRestart()
                && runtimeComponent.lastStartError().startsWith("binding cycle")) {
            diagnostics.add(new RuntimeDiagnostic(
                    DiagnosticCode.BINDING_CYCLE,
                    component.handleId(),
                    runtimeComponent.lastStartError()));
        }
    }

    private static void collectFailureDiagnostics(
            RuntimeView.ComponentData component,
            List<RuntimeDiagnostic> diagnostics,
            ExecutionIndex index) {
        ComponentRuntime runtimeComponent = index.components.get(component.handleId());
        String startError = runtimeComponent == null ? "" : runtimeComponent.lastStartError();
        String cleanupError =
                runtimeComponent == null ? "" : runtimeComponent.lastCleanupError();
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
