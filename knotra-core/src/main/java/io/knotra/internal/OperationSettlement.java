package io.knotra.internal;

import io.knotra.ComponentState;
import io.knotra.SettlementReport;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.LinkedHashSet;

/** 操作范围的过渡聚合，包含所有权闭包并生成最终结算报告。 */
final class OperationSettlement {
    private final DefaultKnotraRuntime runtime;
    private final Set<String> affectedMounts = ConcurrentHashMap.newKeySet();
    private final Map<String, String> removedMountIds;

    OperationSettlement(
            DefaultKnotraRuntime runtime,
            Set<String> affectedMounts,
            Map<String, String> removedMountIds) {
        this.runtime = runtime;
        this.affectedMounts.addAll(affectedMounts);
        this.removedMountIds = Map.copyOf(removedMountIds);
    }

    CompletableFuture<Void> await(Set<String> initial) {
        return awaitTransitions(new ArrayList<>(runtime.schedule(initial)));
    }

    private CompletableFuture<Void> awaitTransitions(
            List<CompletionStage<ComponentState>> transitions) {
        if (transitions.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.allOf(transitions.stream()
                        .map(CompletionStage::toCompletableFuture)
                        .toArray(CompletableFuture[]::new))
                .thenComposeAsync(ignored -> {
                    Set<String> owned = ownedClosure();
                    Set<String> next = new LinkedHashSet<>(owned);
                    next.removeAll(affectedMounts);
                    if (next.isEmpty()) {
                        return CompletableFuture.completedFuture(null);
                    }
                    affectedMounts.addAll(next);
                    List<CompletionStage<ComponentState>> ownedTransitions =
                            new ArrayList<>(runtime.schedule(next));
                    next.forEach(handleId ->
                            ownedTransitions.add(runtime.whenSettled(handleId)));
                    return awaitTransitions(ownedTransitions);
                }, runtime.executor);
    }

    private Set<String> ownedClosure() {
        RuntimeView current = runtime.currentView();
        Set<String> result = new LinkedHashSet<>();
        for (String handleId : affectedMounts) {
            if (current.components.containsKey(handleId)) {
                result.addAll(current.ownershipDescendants(handleId));
            }
        }
        return result;
    }

    SettlementReport report(long generation) {
        return runtime.settlementReport(
                generation,
                new LinkedHashSet<>(affectedMounts),
                removedMountIds);
    }
}
