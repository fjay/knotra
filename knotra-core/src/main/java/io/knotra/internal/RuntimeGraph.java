package io.knotra.internal;

import io.knotra.ActivationState;
import io.knotra.CapabilityKey;
import io.knotra.CapabilityRequirement;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * One-shot structural index over a {@link RuntimeViewReader}.
 *
 * <p>The graph deliberately stores only IDs, enums and immutable binding identities. Real
 * data is fetched back from the reader by ID. A graph is bound to one stable reader and
 * overlay phase: after any Draft mutation callers must discard it and build a new graph.</p>
 */
final class RuntimeGraph {
    private final Map<String, ContextNode> contexts;
    private final Map<String, RegistrationSlot> registrationsById;
    private final Map<String, RegistrationSlot> overlayRegistrationsById;
    private final Map<RegistrationLookup, List<String>> registrationsBySlot;
    private final Map<RegistrationLookup, List<String>> overlayRegistrationsBySlot;
    private final Map<String, ComponentNode> components;
    private final Map<String, ActivationNode> activations;
    private final Map<String, List<String>> childrenByParentHandle;

    private RuntimeGraph(RuntimeViewReader reader, Map<String, RuntimeView.RegistrationData> tentative) {
        contexts = new LinkedHashMap<>();
        for (RuntimeView.ContextData context : reader.contexts().values()) {
            contexts.put(context.contextId(), new ContextNode(
                    context.contextId(), context.parentId(), context.name()));
        }

        registrationsById = new LinkedHashMap<>();
        registrationsBySlot = new LinkedHashMap<>();
        indexRegistration(reader);
        overlayRegistrationsById = new LinkedHashMap<>();
        overlayRegistrationsBySlot = new LinkedHashMap<>();
        for (RuntimeView.RegistrationData registration : tentative.values()) {
            indexRegistration(registration, true);
        }

        components = new LinkedHashMap<>();
        childrenByParentHandle = new LinkedHashMap<>();
        for (RuntimeView.ComponentData component : reader.components().values()) {
            List<RequirementNode> requirements = component.descriptor().sortedRequirements()
                    .stream()
                    .map(requirement -> new RequirementNode(
                            requirement.key().name(),
                            requirement.mode(),
                            requirement.binding()))
                    .toList();
            components.put(component.handleId(), new ComponentNode(
                    component.handleId(),
                    component.contextId(),
                    component.currentActivationId(),
                    component.parentHandleId(),
                    component.ownerActivationId(),
                    requirements));
            if (component.parentHandleId() != null) {
                childrenByParentHandle
                        .computeIfAbsent(component.parentHandleId(), ignored -> new ArrayList<>())
                        .add(component.handleId());
            }
        }

        activations = new LinkedHashMap<>();
        for (RuntimeView.ActivationData activation : reader.activations().values()) {
            activations.put(activation.activationId(), new ActivationNode(
                    activation.activationId(),
                    activation.handleId(),
                    activation.state()));
        }
    }

    static RuntimeGraph of(RuntimeViewReader reader) {
        return of(reader, Map.of());
    }

    static RuntimeGraph of(
            RuntimeViewReader reader,
            Map<String, RuntimeView.RegistrationData> tentative) {
        Objects.requireNonNull(reader, "reader");
        Objects.requireNonNull(tentative, "tentative");
        return new RuntimeGraph(reader, tentative);
    }

    static ResolutionCache resolutionCache() {
        return new ResolutionCache();
    }

    /** Caller-local resolution cache; never stored on Runtime or RuntimeGraph. */
    static final class ResolutionCache {
        private final Map<ResolveKey, Optional<RuntimeView.RegistrationData>> values =
                new HashMap<>();
    }


    Optional<RuntimeView.RegistrationData> resolve(
            RuntimeViewReader reader,
            Map<String, RuntimeView.RegistrationData> tentative,
            String contextId,
            CapabilityKey<?> key) {
        ResolutionCache cache = resolutionCache();
        return resolve(reader, tentative, cache, contextId, key);
    }

    Optional<RuntimeView.RegistrationData> resolve(
            RuntimeViewReader reader,
            Map<String, RuntimeView.RegistrationData> tentative,
            ResolutionCache cache,
            String contextId,
            CapabilityKey<?> key) {
        ResolveKey identity = new ResolveKey(
                contextId, key.name(), key.typeName());
        return cache.values.computeIfAbsent(identity, ignored -> resolveUncached(
                reader, tentative, contextId, key));
    }

    static Optional<RuntimeView.RegistrationData> resolveDirect(
            RuntimeViewReader reader,
            Map<String, RuntimeView.RegistrationData> tentative,
            String contextId,
            CapabilityKey<?> key) {
        return resolveDirect(
                reader, tentative, resolutionCache(), contextId, key);
    }

    static Optional<RuntimeView.RegistrationData> resolveDirect(
            RuntimeViewReader reader,
            Map<String, RuntimeView.RegistrationData> tentative,
            ResolutionCache cache,
            String contextId,
            CapabilityKey<?> key) {
        ResolveKey identity = new ResolveKey(
                contextId, key.name(), key.typeName());
        return cache.values.computeIfAbsent(
                identity, ignored -> resolveDirectUncached(
                        reader, tentative, contextId, key));
    }

    private static Optional<RuntimeView.RegistrationData> resolveDirectUncached(
            RuntimeViewReader reader,
            Map<String, RuntimeView.RegistrationData> tentative,
            String contextId,
            CapabilityKey<?> key) {
        String current = contextId;
        while (current != null) {
            for (RuntimeView.RegistrationData registration : tentative.values()) {
                if (registration.contextId().equals(current)
                        && registration.key().equals(key)) {
                    return Optional.of(registration);
                }
            }
            for (RuntimeView.RegistrationData registration : reader.registrations().values()) {
                if (registration.contextId().equals(current)
                        && registration.key().equals(key)) {
                    return Optional.of(registration);
                }
            }
            RuntimeView.ContextData context = reader.contexts().get(current);
            current = context == null ? null : context.parentId();
        }
        return Optional.empty();
    }

    private Optional<RuntimeView.RegistrationData> resolveUncached(
            RuntimeViewReader reader,
            Map<String, RuntimeView.RegistrationData> tentative,
            String contextId,
            CapabilityKey<?> key) {
        String current = contextId;
        while (current != null) {
            // Pre-commit validation lets tentative registrations participate, but only
            // at their own level; a closer committed registration still wins over a parent.
            for (String registrationId : overlayCandidates(current, key.name())) {
                RuntimeView.RegistrationData registration =
                        tentativeRegistration(tentative, registrationId);
                if (registration != null && registration.key().equals(key)) {
                    return Optional.of(registration);
                }
            }
            for (String registrationId : committedCandidates(current, key.name())) {
                RuntimeView.RegistrationData registration =
                        reader.registrations().get(registrationId);
                if (registration != null && registration.key().equals(key)) {
                    return Optional.of(registration);
                }
            }
            ContextNode context = contexts.get(current);
            current = context == null ? null : context.parentId();
        }
        return Optional.empty();
    }

    Map<String, RuntimeView.BindingData> effectiveBindings(
            RuntimeViewReader reader,
            Map<String, RuntimeView.RegistrationData> tentative,
            RuntimeView.ComponentData component) {
        return effectiveBindings(reader, tentative, resolutionCache(), component);
    }

    Map<String, RuntimeView.BindingData> effectiveBindings(
            RuntimeViewReader reader,
            Map<String, RuntimeView.RegistrationData> tentative,
            ResolutionCache cache,
            RuntimeView.ComponentData component) {
        return buildBindings(
                component,
                key -> resolve(reader, tentative, cache, component.contextId(), key));
    }

    static Map<String, RuntimeView.BindingData> effectiveBindingsDirect(
            RuntimeViewReader reader,
            Map<String, RuntimeView.RegistrationData> tentative,
            RuntimeView.ComponentData component) {
        return effectiveBindingsDirect(
                reader, tentative, resolutionCache(), component);
    }

    static Map<String, RuntimeView.BindingData> effectiveBindingsDirect(
            RuntimeViewReader reader,
            Map<String, RuntimeView.RegistrationData> tentative,
            ResolutionCache cache,
            RuntimeView.ComponentData component) {
        return buildBindings(
                component,
                key -> resolveDirect(reader, tentative, cache, component.contextId(), key));
    }

    // Single-source binding construction: indexed/direct differ only in resolution
    // strategy; mode/binding/presence identity must never drift between the two paths.
    private static Map<String, RuntimeView.BindingData> buildBindings(
            RuntimeView.ComponentData component,
            CapabilityResolver resolver) {
        Map<String, RuntimeView.BindingData> bindings = new TreeMap<>();
        for (CapabilityRequirement requirement : component.descriptor().sortedRequirements()) {
            RuntimeView.RegistrationData registration =
                    resolver.resolve(requirement.key()).orElse(null);
            bindings.put(requirement.key().name(), bindingFor(requirement, registration));
        }
        return bindings;
    }

    private static RuntimeView.BindingData bindingFor(
            CapabilityRequirement requirement,
            RuntimeView.RegistrationData registration) {
        if (requirement.binding() == CapabilityRequirement.CapabilityBinding.DYNAMIC) {
            return new RuntimeView.BindingData(
                    null, false, requirement.mode(), requirement.binding());
        }
        return new RuntimeView.BindingData(
                registration == null ? null : registration.registrationId(),
                registration != null,
                requirement.mode(),
                requirement.binding());
    }

    @FunctionalInterface
    private interface CapabilityResolver {
        Optional<RuntimeView.RegistrationData> resolve(CapabilityKey<?> key);
    }

    // Only Activations that still track the dependency graph participate in cycle checks;
    // STOPPING bindings have detached and must not constrain the next graph.
    Map<String, Set<String>> dependencyGraph(
            RuntimeViewReader reader,
            Map<String, RuntimeView.RegistrationData> tentative) {
        Map<String, Set<String>> graph = new TreeMap<>();
        Map<String, String> activationOwner = new HashMap<>();
        ResolutionCache resolutionCache = resolutionCache();
        activations.values().forEach(activation ->
                activationOwner.put(activation.activationId(), activation.handleId()));

        for (ComponentNode node : components.values()) {
            RuntimeView.ComponentData component = reader.components().get(node.handleId());
            if (component == null || node.currentActivationId() == null) {
                continue;
            }
            ActivationNode activation = activations.get(node.currentActivationId());
            if (activation == null || !activationTracksGraph(activation.state())) {
                continue;
            }
            Set<String> providers = new LinkedHashSet<>();
            RuntimeView.ActivationData activationData =
                    reader.activations().get(node.currentActivationId());
            if (activationData == null) {
                continue;
            }
            Map<String, RuntimeView.BindingData> source = tentative.isEmpty()
                    ? activationData.bindings()
                    : effectiveBindings(reader, tentative, resolutionCache, component);
            for (RequirementNode requirement : node.requirements()) {
                String registrationId;
                if (requirement.binding() == CapabilityRequirement.CapabilityBinding.DYNAMIC) {
                    registrationId = resolveMatchingRegistrationId(
                            reader,
                            tentative,
                            resolutionCache,
                            node.contextId(),
                            component,
                            requirement)
                            .orElse(null);
                } else {
                    RuntimeView.BindingData binding = source.get(requirement.capabilityName());
                    if (binding == null || !binding.present()) {
                        continue;
                    }
                    registrationId = binding.registrationId();
                }
                RegistrationSlot registration = registrationId == null
                        ? null
                        : registrationById(registrationId);
                if (registration != null && registration.ownerActivationId() != null) {
                    String provider = activationOwner.get(registration.ownerActivationId());
                    if (provider != null) {
                        providers.add(provider);
                    }
                }
            }
            graph.put(node.handleId(), providers);
        }
        return graph;
    }

    // Tarjan strongly-connected-component detection; nodes outside the candidate graph are
    // ignored, so only a cycle possible in this commit is rejected.
    static boolean hasCycle(Map<String, Set<String>> graph) {
        Map<String, Integer> index = new HashMap<>();
        Map<String, Integer> low = new HashMap<>();
        Map<String, Boolean> onStack = new HashMap<>();
        List<String> stack = new ArrayList<>();
        int[] counter = new int[1];
        for (String node : graph.keySet()) {
            if (!index.containsKey(node) && strongConnect(
                    node,
                    graph,
                    index,
                    low,
                    onStack,
                    stack,
                    counter)) {
                return true;
            }
        }
        return false;
    }

    private static boolean strongConnect(
            String node,
            Map<String, Set<String>> graph,
            Map<String, Integer> index,
            Map<String, Integer> low,
            Map<String, Boolean> onStack,
            List<String> stack,
            int[] counter) {
        index.put(node, counter[0]);
        low.put(node, counter[0]);
        counter[0]++;
        stack.add(node);
        onStack.put(node, true);
        for (String provider : graph.getOrDefault(node, Set.of())) {
            if (!graph.containsKey(provider)) {
                continue;
            }
            if (!index.containsKey(provider)) {
                if (strongConnect(provider, graph, index, low, onStack, stack, counter)) {
                    return true;
                }
                low.put(node, Math.min(low.get(node), low.get(provider)));
            } else if (Boolean.TRUE.equals(onStack.get(provider))) {
                low.put(node, Math.min(low.get(node), index.get(provider)));
            }
        }
        if (low.get(node).equals(index.get(node))) {
            int size = 0;
            String member;
            do {
                member = stack.removeLast();
                onStack.put(member, false);
                size++;
            } while (!member.equals(node));
            return size > 1 || graph.getOrDefault(node, Set.of()).contains(node);
        }
        return false;
    }

    Set<String> contextSubtree(RuntimeViewReader reader, String contextId) {
        return reader.contexts().values().stream()
                .map(RuntimeView.ContextData::contextId)
                .filter(id -> id.equals(contextId) || isInSubtree(id, contextId))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    boolean isInSubtree(String candidate, String root) {
        ContextNode context = contexts.get(candidate);
        String parent = context == null ? null : context.parentId();
        while (parent != null) {
            if (parent.equals(root)) {
                return true;
            }
            parent = contexts.containsKey(parent)
                    ? contexts.get(parent).parentId()
                    : null;
        }
        return false;
    }

    // A parent Activation owns the complete committed child tree; replacement must dispose
    // that tree together instead of unlinking only direct children.
    Set<String> ownershipDescendants(RuntimeViewReader reader, String handleId) {
        Set<String> result = new LinkedHashSet<>();
        collectOwnershipDescendants(reader, handleId, null, result);
        return result;
    }

    Set<String> ownershipDescendantsForActivation(
            RuntimeViewReader reader,
            String handleId,
            String ownerActivationId) {
        Set<String> result = new LinkedHashSet<>();
        collectOwnershipDescendants(reader, handleId, ownerActivationId, result);
        return result;
    }

    private void collectOwnershipDescendants(
            RuntimeViewReader reader,
            String handleId,
            String ownerActivationId,
            Set<String> result) {
        if (!result.add(handleId)) {
            return;
        }
        ComponentNode parent = components.get(handleId);
        if (parent == null) {
            return;
        }
        for (String childId : childrenByParentHandle.getOrDefault(handleId, List.of())) {
            ComponentNode child = components.get(childId);
            if (child == null) {
                continue;
            }
            if (ownerActivationId == null
                    || Objects.equals(ownerActivationId, child.ownerActivationId())) {
                collectOwnershipDescendants(
                        reader,
                        child.handleId(),
                        child.currentActivationId(),
                        result);
            }
        }
    }

    // Grow from directly impacted components to every transitive dependent, ensuring all
    // dependents detach before their providers are released.
    Set<String> dependentsClosure(RuntimeViewReader reader, Set<String> initial) {
        Set<String> result = new LinkedHashSet<>(initial);
        boolean changed = true;
        while (changed) {
            changed = false;
            for (ComponentNode node : components.values()) {
                if (result.contains(node.handleId()) || node.currentActivationId() == null) {
                    continue;
                }
                ActivationNode activation = activations.get(node.currentActivationId());
                if (activation == null || !activationTracksGraph(activation.state())) {
                    continue;
                }
                RuntimeView.ActivationData activationData =
                        reader.activations().get(node.currentActivationId());
                if (activationData == null) {
                    continue;
                }
                boolean depends = activationData.bindings().values().stream()
                        .filter(binding -> binding.present()
                                && binding.binding()
                                == CapabilityRequirement.CapabilityBinding.PINNED)
                        .map(RuntimeView.BindingData::registrationId)
                        .map(this::registrationById)
                        .filter(Objects::nonNull)
                        .anyMatch(registration -> {
                            ActivationNode owner = registration.ownerActivationId() == null
                                    ? null
                                    : activations.get(registration.ownerActivationId());
                            return owner != null && result.contains(owner.handleId());
                        });
                if (depends) {
                    result.add(node.handleId());
                    changed = true;
                }
            }
        }
        return result;
    }

    List<String> dependentsBeforeProviders(
            RuntimeViewReader reader,
            Map<String, RuntimeView.RegistrationData> tentative,
            Set<String> handles) {
        Map<String, Set<String>> graph = dependencyGraph(reader, tentative);
        List<String> ordered = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        for (String handle : handles.stream().sorted().toList()) {
            visitDependentsFirst(handle, graph, handles, visited, ordered);
        }
        handles.stream()
                .filter(handle -> !visited.contains(handle))
                .sorted()
                .forEach(ordered::add);
        return ordered.stream().distinct().toList();
    }

    private void visitDependentsFirst(
            String handle,
            Map<String, Set<String>> graph,
            Set<String> handles,
            Set<String> visited,
            List<String> ordered) {
        if (!visited.add(handle)) {
            return;
        }
        for (String provider : graph.getOrDefault(handle, Set.of())) {
            if (!provider.equals(handle) && handles.contains(provider)) {
                visitDependentsFirst(provider, graph, handles, visited, ordered);
            }
        }
        ordered.add(handle);
    }

    List<String> registrationsOwnedBy(RuntimeViewReader reader, Set<String> handleIds) {
        Set<String> activationIds = activations.values().stream()
                .filter(activation -> handleIds.contains(activation.handleId()))
                .map(ActivationNode::activationId)
                .collect(Collectors.toSet());
        return registrationsById.values().stream()
                .filter(registration -> registration.ownerActivationId() != null
                        && activationIds.contains(registration.ownerActivationId()))
                .map(RegistrationSlot::registrationId)
                .sorted()
                .toList();
    }

    String canonicalPath(RuntimeViewReader reader, String contextId) {
        List<String> segments = new ArrayList<>();
        String current = contextId;
        while (current != null) {
            ContextNode context = contexts.get(current);
            if (context == null) {
                break;
            }
            segments.add(context.name());
            current = context.parentId();
        }
        if (segments.isEmpty()) {
            return "/" + contextId;
        }
        StringBuilder path = new StringBuilder();
        for (int i = segments.size() - 1; i >= 0; i--) {
            path.append('/').append(segments.get(i));
        }
        return path.toString();
    }

    // A cycle can be introduced by another component's DYNAMIC edge. Edges compare
    // capability/mode/provider-owner identity, not registration IDs; replacing a provider
    // registration within the same mount therefore leaves dynamic topology unchanged.
    Set<DynamicEdge> dynamicDependencyEdges(RuntimeViewReader reader) {
        Set<DynamicEdge> edges = new LinkedHashSet<>();
        ResolutionCache resolutionCache = resolutionCache();
        for (ComponentNode node : components.values()) {
            RuntimeView.ComponentData component = reader.components().get(node.handleId());
            if (component == null) {
                continue;
            }
            for (RequirementNode requirement : node.requirements()) {
                if (requirement.binding() != CapabilityRequirement.CapabilityBinding.DYNAMIC) {
                    continue;
                }
                for (CapabilityRequirement candidate
                        : component.descriptor().sortedRequirements()) {
                    if (!requirement.capabilityName().equals(candidate.key().name())) {
                        continue;
                    }
                    String registrationId = resolve(
                            reader,
                            Map.of(),
                            resolutionCache,
                            node.contextId(),
                            candidate.key())
                            .map(RuntimeView.RegistrationData::registrationId)
                            .orElse(null);
                    RegistrationSlot registration = registrationId == null
                            ? null
                            : registrationById(registrationId);
                    if (registration != null) {
                        edges.add(new DynamicEdge(
                                requirement.capabilityName(),
                                providerOwnerIdentity(registration),
                                requirement.mode()));
                    }
                }
            }
        }
        return Set.copyOf(edges);
    }

    private ProviderOwnerIdentity providerOwnerIdentity(
            RegistrationSlot registration) {
        if (registration.ownerActivationId() == null) {
            return new ProviderOwnerIdentity(
                    ProviderOwnerKind.HOST, registration.contextId());
        }
        ActivationNode activation = activations.get(registration.ownerActivationId());
        return new ProviderOwnerIdentity(
                ProviderOwnerKind.ACTIVATION,
                activation == null ? registration.ownerActivationId() : activation.handleId());
    }

    private void indexRegistration(RuntimeViewReader reader) {
        for (RuntimeView.RegistrationData registration : reader.registrations().values()) {
            indexRegistration(registration, false);
        }
    }

    private void indexRegistration(
            RuntimeView.RegistrationData registration,
            boolean tentative) {
        RegistrationSlot slot = new RegistrationSlot(
                registration.registrationId(),
                registration.contextId(),
                registration.key().name(),
                registration.owner() instanceof RuntimeView.OwnerData.Activation owner
                        ? owner.activationId()
                        : null);
        RegistrationLookup lookup = new RegistrationLookup(
                slot.contextId(), slot.capabilityName());
        if (tentative) {
            overlayRegistrationsById.put(slot.registrationId(), slot);
            overlayRegistrationsBySlot
                    .computeIfAbsent(lookup, ignored -> new ArrayList<>())
                    .add(slot.registrationId());
        } else {
            registrationsById.put(slot.registrationId(), slot);
            registrationsBySlot
                    .computeIfAbsent(lookup, ignored -> new ArrayList<>())
                    .add(slot.registrationId());
        }
    }

    private List<String> overlayCandidates(String contextId, String capabilityName) {
        return overlayRegistrationsBySlot.getOrDefault(
                new RegistrationLookup(contextId, capabilityName), List.of());
    }

    private List<String> committedCandidates(String contextId, String capabilityName) {
        return registrationsBySlot.getOrDefault(
                new RegistrationLookup(contextId, capabilityName), List.of());
    }

    private Optional<String> resolveMatchingRegistrationId(
            RuntimeViewReader reader,
            Map<String, RuntimeView.RegistrationData> tentative,
            ResolutionCache cache,
            String contextId,
            RuntimeView.ComponentData component,
            RequirementNode requirement) {
        for (CapabilityRequirement candidate : component.descriptor().sortedRequirements()) {
            if (candidate.key().name().equals(requirement.capabilityName())) {
                return resolve(reader, tentative, cache, contextId, candidate.key())
                        .map(RuntimeView.RegistrationData::registrationId);
            }
        }
        return Optional.empty();
    }


    private static RuntimeView.RegistrationData tentativeRegistration(
            Map<String, RuntimeView.RegistrationData> tentative,
            String registrationId) {
        // Tentative maps may be keyed by capability name; identity always comes from values.
        for (RuntimeView.RegistrationData registration : tentative.values()) {
            if (registration.registrationId().equals(registrationId)) {
                return registration;
            }
        }
        return null;
    }

    private RegistrationSlot registrationById(String registrationId) {
        RegistrationSlot committed = registrationsById.get(registrationId);
        return committed != null ? committed : overlayRegistrationsById.get(registrationId);
    }

    static boolean activationTracksGraph(ActivationState state) {
        return state == ActivationState.STARTING || state == ActivationState.ACTIVE;
    }

    enum ProviderOwnerKind {
        HOST,
        ACTIVATION
    }

    record ProviderOwnerIdentity(ProviderOwnerKind kind, String ownerId) {
    }

    record DynamicEdge(
            String capabilityName,
            ProviderOwnerIdentity providerOwnerIdentity,
            CapabilityRequirement.Mode mode) {
    }
    private record ResolveKey(
            String contextId,
            String capabilityName,
            String typeName) {
    }

    private record RegistrationLookup(String contextId, String capabilityName) {
    }
    private record ContextNode(
            String contextId,
            String parentId,
            String name) {
    }

    private record RegistrationSlot(
            String registrationId,
            String contextId,
            String capabilityName,
            String ownerActivationId) {
    }

    private record RequirementNode(
            String capabilityName,
            CapabilityRequirement.Mode mode,
            CapabilityRequirement.CapabilityBinding binding) {
    }

    private record ComponentNode(
            String handleId,
            String contextId,
            String currentActivationId,
            String parentHandleId,
            String ownerActivationId,
            List<RequirementNode> requirements) {
    }

    private record ActivationNode(
            String activationId,
            String handleId,
            ActivationState state) {
    }
}
