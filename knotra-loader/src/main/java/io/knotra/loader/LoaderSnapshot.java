package io.knotra.loader;

import java.util.List;
import java.util.Optional;

import io.knotra.ComponentGoal;
import io.knotra.ComponentState;

public record LoaderSnapshot(
        String loaderId,
        boolean owned,
        String baseContextId,
        boolean closed,
        List<EntrySnapshot> entries,
        List<LoaderDiagnostic> diagnostics) {

    public LoaderSnapshot {
        if (loaderId == null || loaderId.isBlank()) {
            throw new IllegalArgumentException("loaderId must not be blank");
        }
        if (baseContextId == null || baseContextId.isBlank()) {
            throw new IllegalArgumentException("baseContextId must not be blank");
        }
        entries = List.copyOf(entries).stream()
                .map(entry -> new EntrySnapshot(
                        entry.path(),
                        entry.contextId(),
                        entry.contextPath(),
                        entry.handleId(),
                        entry.mountId(),
                        entry.componentId(),
                        entry.factoryIdentity(),
                        entry.configRevision(),
                        entry.state(),
                        entry.goal()))
                .sorted()
                .toList();
        diagnostics = List.copyOf(diagnostics).stream().sorted().toList();
    }

    public Optional<EntrySnapshot> entry(String path) {
        return entries.stream()
                .filter(entry -> entry.path().equals(path))
                .findFirst();
    }

    public record EntrySnapshot(
            String path,
            String contextId,
            String contextPath,
            String handleId,
            String mountId,
            String componentId,
            FactoryIdentity factoryIdentity,
            long configRevision,
            ComponentState state,
            ComponentGoal goal) implements Comparable<EntrySnapshot> {

        @Override
        public int compareTo(EntrySnapshot other) {
            return path.compareTo(other.path);
        }
    }
}
