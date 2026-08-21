package io.knotra.internal;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

final class ExecutableCommitPlan {
    final Map<String, DefaultKnotraRuntime.MountIntent<?>> mounts = new HashMap<>();
    final Map<String, ConfigUpdate> configs = new HashMap<>();
    final Set<String> staleActivations = new LinkedHashSet<>();
    final Set<String> removedComponents = new LinkedHashSet<>();
    final Set<String> resetAutoRestart = new LinkedHashSet<>();
    final Set<String> contextDisposals = new LinkedHashSet<>();
    record ConfigUpdate(Object config, long revision) {
    }
}
