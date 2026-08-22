package io.knotra.internal;

import io.knotra.ActivationState;
import io.knotra.CapabilityKey;
import io.knotra.CapabilityRequirement;
import io.knotra.ComponentDescriptor;
import io.knotra.ComponentGoal;
import io.knotra.ComponentOrigin;
import io.knotra.ComponentState;
import io.knotra.ContextState;
import io.knotra.MountOptions;
import io.knotra.RuntimeDiagnostic;
import io.knotra.RuntimeSnapshot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;


/**
 * 已提交 Runtime 状态的不可变快照，代际由 {@code generation} 标识。
 *
 * <p>{@link DefaultKnotraRuntime} 只在协调器锁内通过 {@link Draft#publishOnce} 替换整份视图，
 * 无锁读取方拿到旧快照时仍能看到一个自洽的结构组合。草稿中的暂存 Capability、子挂载和状态迁移
 * 不会影响旧代际。视图持有 Capability 值和组件合约数据，但不持有 LifecycleScope 或释放器。</p>
 */
final class RuntimeView {
    // 每次成功发布整体递增；拒绝的事务复用旧视图，不产生代际。
    final long generation;
    final Map<String, ContextData> contexts;
    final Map<String, RegistrationData> registrations;
    final Map<String, ComponentData> components;
    final Map<String, ActivationData> activations;
    final Map<String, Class<?>> capabilityTypes;
    final List<RuntimeDiagnostic> diagnostics;

    private RuntimeView(
            long generation,
            Map<String, ContextData> contexts,
            Map<String, RegistrationData> registrations,
            Map<String, ComponentData> components,
            Map<String, ActivationData> activations,
            Map<String, Class<?>> capabilityTypes,
            List<RuntimeDiagnostic> diagnostics) {
        this.generation = generation;
        this.contexts = Map.copyOf(contexts);
        this.registrations = Map.copyOf(registrations);
        this.components = Map.copyOf(components);
        this.activations = Map.copyOf(activations);
        this.capabilityTypes = Map.copyOf(capabilityTypes);
        this.diagnostics = List.copyOf(diagnostics);
    }

    static RuntimeView initial() {
        Map<String, ContextData> contexts = new HashMap<>();
        contexts.put(
                "ctx-root",
                new ContextData("ctx-root", null, "root", ContextState.ACTIVE, "/root"));
        return new RuntimeView(
                0,
                contexts,
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                List.of());
    }

    RuntimeSnapshot snapshotWithoutScopes() {
        List<RuntimeSnapshot.ContextSnapshot> contextSnapshots = contexts.values().stream()
                .map(context -> new RuntimeSnapshot.ContextSnapshot(
                        context.contextId(),
                        context.parentId(),
                        context.name(),
                        context.state(),
                        context.canonicalPath()))
                .sorted(Comparator.comparing(RuntimeSnapshot.ContextSnapshot::contextId))
                .toList();

        List<RuntimeSnapshot.MountSnapshot> mountSnapshots = components.values().stream()
                .map(component -> new RuntimeSnapshot.MountSnapshot(
                        component.handleId(),
                        component.contextId(),
                        component.mountId(),
                        component.componentId(),
                        component.factoryId(),
                        component.origin(),
                        component.ownerActivationId(),
                        component.parentHandleId(),
                        component.state(),
                        component.goal(),
                        component.configRevision(),
                        component.currentActivationId(),
                        component.lastActivationId(),
                        component.options(),
                        component.descriptor().sortedRequirements().stream()
                                .map(requirement -> new RuntimeSnapshot.RequirementSnapshot(
                                        capabilitySnapshot(requirement.key()),
                                        requirement.mode(),
                                        requirement.binding()))
                                .toList()))
                .sorted(Comparator.comparing(RuntimeSnapshot.MountSnapshot::handleId))
                .toList();

        List<RuntimeSnapshot.ActivationSnapshot> activationSnapshots = activations.values().stream()
                .map(activation -> new RuntimeSnapshot.ActivationSnapshot(
                        activation.activationId(),
                        activation.handleId(),
                        activation.state(),
                        activation.configRevision(),
                        activation.descriptor.sortedRequirements().stream()
                                .map(requirement -> bindingSnapshot(activation, requirement))
                                .toList(),
                        activation.scopeId()))
                .sorted(Comparator.comparing(RuntimeSnapshot.ActivationSnapshot::activationId))
                .toList();

        List<RuntimeSnapshot.RegistrationSnapshot> registrationSnapshots = registrations.values().stream()
                .map(registration -> new RuntimeSnapshot.RegistrationSnapshot(
                        registration.registrationId(),
                        capabilitySnapshot(registration.key()),
                        registration.contextId(),
                        ownerSnapshot(registration.owner())))
                .sorted(Comparator.comparing(RuntimeSnapshot.RegistrationSnapshot::registrationId))
                .toList();

        return new RuntimeSnapshot(
                generation,
                contextSnapshots,
                mountSnapshots,
                activationSnapshots,
                registrationSnapshots,
                List.of(),
                diagnostics.stream().sorted().toList());
    }

    private RuntimeSnapshot.BindingSnapshot bindingSnapshot(
            ActivationData activation,
            CapabilityRequirement requirement) {
        BindingData binding = activation.bindings.get(requirement.key().name());
        return new RuntimeSnapshot.BindingSnapshot(
                capabilitySnapshot(requirement.key()),
                binding == null ? null : binding.registrationId(),
                binding != null && binding.present(),
                requirement.mode(),
                requirement.binding());
    }

    private RuntimeSnapshot.RegistrationOwnerSnapshot ownerSnapshot(OwnerData owner) {
        if (owner instanceof OwnerData.Activation activation) {
            return new RuntimeSnapshot.RegistrationOwnerSnapshot(
                    RuntimeSnapshot.RegistrationOwnerKind.ACTIVATION,
                    activation.activationId());
        }
        return new RuntimeSnapshot.RegistrationOwnerSnapshot(
                RuntimeSnapshot.RegistrationOwnerKind.HOST,
                "host");
    }

    private RuntimeSnapshot.CapabilitySnapshot capabilitySnapshot(CapabilityKey<?> key) {
        return new RuntimeSnapshot.CapabilitySnapshot(key.name(), key.typeName());
    }

    Optional<RegistrationData> resolve(String contextId, CapabilityKey<?> key) {
        return resolve(contextId, key, Map.of());
    }

    // 从当前 Context 向根解析；子 Context 中的注册先于父注册被采纳，从而形成遮蔽。
    Optional<RegistrationData> resolve(
            String contextId,
            CapabilityKey<?> key,
            Map<String, RegistrationData> tentative) {
        String current = contextId;
        while (current != null) {
            // 提交前验证允许暂存注册参与解析；仍按当前层级过滤，不能跨过更近的提交注册。
            for (RegistrationData registration : tentative.values()) {
                if (registration.contextId().equals(current)
                        && registration.key().equals(key)) {
                    return Optional.of(registration);
                }
            }
            RegistrationData committed = null;
            // 事务阶段保证同一 Context 的 Capability 槽位唯一；这里保留首个候选作为已提交代际。
            for (RegistrationData candidate : registrations.values()) {
                if (candidate.contextId().equals(current)
                        && candidate.key().equals(key)) {
                    committed = candidate;
                    break;
                }
            }
            if (committed != null) {
                return Optional.of(committed);
            }
            ContextData context = contexts.get(current);
            current = context == null ? null : context.parentId();
        }
        return Optional.empty();
    }

    Map<String, BindingData> effectiveBindings(
            ComponentData component,
            Map<String, RegistrationData> tentative) {
        Map<String, BindingData> bindings = new TreeMap<>();
        for (CapabilityRequirement requirement : component.descriptor().sortedRequirements()) {
            RegistrationData registration = resolve(
                    component.contextId(),
                    requirement.key(),
                    tentative)
                    .orElse(null);
            bindings.put(
                    requirement.key().name(),
                    requirement.binding().equals(
                            CapabilityRequirement.CapabilityBinding.DYNAMIC)
                            ? new BindingData(null, false, requirement.mode(), requirement.binding())
                            : new BindingData(
                                    registration == null ? null : registration.registrationId(),
                                    registration != null,
                                    requirement.mode(),
                                    requirement.binding()));
        }
        return bindings;
    }

    // 只有仍在跟踪依赖图的 Activation 参与环检测；STOPPING 的绑定已被脱离，不能继续约束新图。
    Map<String, Set<String>> dependencyGraph(Map<String, RegistrationData> tentative) {
        Map<String, Set<String>> graph = new TreeMap<>();
        Map<String, String> activationOwner = new HashMap<>();
        activations.values().forEach(activation ->
                activationOwner.put(activation.activationId(), activation.handleId()));

        for (ComponentData component : components.values()) {
            if (component.currentActivationId == null) {
                continue;
            }
            ActivationData activation = activations.get(component.currentActivationId);
            if (activation == null || !activationTracksGraph(activation.state())) {
                continue;
            }
            Set<String> providers = new LinkedHashSet<>();
            Map<String, BindingData> source = tentative.isEmpty()
                    ? activation.bindings()
                    : effectiveBindings(component, tentative);
            for (CapabilityRequirement requirement :
                    component.descriptor().sortedRequirements()) {
                RegistrationData registration;
                if (requirement.binding() ==
                        CapabilityRequirement.CapabilityBinding.DYNAMIC) {
                    registration = resolve(
                            component.contextId(),
                            requirement.key(),
                            tentative)
                            .orElse(null);
                } else {
                    BindingData binding = source.get(requirement.key().name());
                    if (binding == null || !binding.present()) {
                        continue;
                    }
                    registration = registrations.get(binding.registrationId());
                    if (registration == null) {
                        registration = tentative.values().stream()
                                .filter(candidate -> candidate.registrationId()
                                        .equals(binding.registrationId()))
                                .findFirst()
                                .orElse(null);
                    }
                }
                if (registration != null
                        && registration.owner() instanceof OwnerData.Activation owner) {
                    String provider = activationOwner.get(owner.activationId());
                    if (provider != null) {
                        providers.add(provider);
                    }
                }
            }
            graph.put(component.handleId(), providers);
        }
        return graph;
    }

    // Tarjan 强连通分量检测；非候选图外节点忽略，因此只判定本次提交可能形成的依赖环。
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
            if (size > 1 || graph.getOrDefault(node, Set.of()).contains(node)) {
                return true;
            }
        }
        return false;
    }

    Set<String> contextSubtree(String contextId) {
        return contexts.values().stream()
                .map(ContextData::contextId)
                .filter(id -> id.equals(contextId) || isInSubtree(id, contextId))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    boolean isInSubtree(String candidate, String root) {
        String parent = contexts.get(candidate).parentId();
        while (parent != null) {
            if (parent.equals(root)) {
                return true;
            }
            parent = contexts.get(parent).parentId();
        }
        return false;
    }

    // 父 Activation 拥有其提交的整棵子挂载树；换代时必须一起处置，不能只断开直接子节点。
    Set<String> ownershipDescendants(String handleId) {
        Set<String> result = new LinkedHashSet<>();
        collectOwnershipDescendants(handleId, null, result);
        return result;
    }

    Set<String> ownershipDescendantsForActivation(
            String handleId,
            String ownerActivationId) {
        Set<String> result = new LinkedHashSet<>();
        collectOwnershipDescendants(handleId, ownerActivationId, result);
        return result;
    }

    private void collectOwnershipDescendants(
            String handleId,
            String ownerActivationId,
            Set<String> result) {
        if (!result.add(handleId)) {
            return;
        }
        ComponentData parent = components.get(handleId);
        if (parent == null) {
            return;
        }
        for (ComponentData component : components.values()) {
            if (handleId.equals(component.parentHandleId())
                    && (ownerActivationId == null
                            || Objects.equals(
                                    ownerActivationId,
                                    component.ownerActivationId()))) {
                collectOwnershipDescendants(
                        component.handleId(),
                        component.currentActivationId(),
                        result);
            }
        }
    }

    // 从直接受影响组件闭包到全部传递依赖方，保证提供方释放前所有依赖方都已脱离。
    Set<String> dependentsClosure(Set<String> initial) {
        Set<String> result = new LinkedHashSet<>(initial);
        boolean changed = true;
        while (changed) {
            changed = false;
            for (ComponentData component : components.values()) {
                if (result.contains(component.handleId())
                        || component.currentActivationId == null) {
                    continue;
                }
                ActivationData activation = activations.get(component.currentActivationId);
                if (activation == null || !activationTracksGraph(activation.state())) {
                    continue;
                }
                boolean depends = activation.bindings.values().stream()
                        .filter(binding -> binding.present()
                                && binding.binding() ==
                                        CapabilityRequirement.CapabilityBinding.PINNED)
                        .map(BindingData::registrationId)
                        .map(registrations::get)
                        .filter(Objects::nonNull)
                        .anyMatch(registration -> {
                            if (!(registration.owner()
                                    instanceof OwnerData.Activation owner)) {
                                return false;
                            }
                            ActivationData ownerActivation =
                                    activations.get(owner.activationId());
                            return ownerActivation != null
                                    && result.contains(ownerActivation.handleId());
                        });
                if (depends) {
                    result.add(component.handleId());
                    changed = true;
                }
            }
        }
        return result;
    }

    List<String> dependentsBeforeProviders(Set<String> handles) {
        Map<String, Set<String>> graph = dependencyGraph(Map.of());
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

    List<String> registrationsOwnedBy(Set<String> handleIds) {
        Set<String> activationIds = activations.values().stream()
                .filter(activation -> handleIds.contains(activation.handleId()))
                .map(ActivationData::activationId)
                .collect(Collectors.toSet());
        return registrations.values().stream()
                .filter(registration -> registration.owner()
                        instanceof OwnerData.Activation owner
                        && activationIds.contains(owner.activationId()))
                .map(RegistrationData::registrationId)
                .sorted()
                .toList();
    }

    String canonicalPath(String contextId) {
        List<String> segments = new ArrayList<>();
        String current = contextId;
        while (current != null) {
            ContextData data = contexts.get(current);
            if (data == null) {
                break;
            }
            segments.add(data.name());
            current = data.parentId();
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

    static boolean activationTracksGraph(ActivationState state) {
        return state == ActivationState.STARTING || state == ActivationState.ACTIVE;
    }

    record ContextData(
            String contextId,
            String parentId,
            String name,
            ContextState state,
            String canonicalPath) {
        ContextData withState(ContextState next) {
            return new ContextData(
                    contextId, parentId, name, next, canonicalPath);
        }
    }

    sealed interface OwnerData {
        record Host() implements OwnerData {
            static final Host INSTANCE = new Host();
        }

        record Activation(String activationId) implements OwnerData {
        }
    }

    record RegistrationData(
            String registrationId,
            CapabilityKey<?> key,
            String contextId,
            OwnerData owner,
            Object value,
            ProviderLeaseRuntime leases) {
    }

    record ComponentData(
            String handleId,
            String contextId,
            String mountId,
            String componentId,
            String factoryId,
            ComponentOrigin origin,
            String ownerActivationId,
            String parentHandleId,
            ComponentState state,
            ComponentGoal goal,
            long configRevision,
            String currentActivationId,
            String lastActivationId,
            ComponentDescriptor descriptor,
            MountOptions options) {

        ComponentData withState(ComponentState next) {
            return new ComponentData(
                    handleId, contextId, mountId, componentId, factoryId, origin,
                    ownerActivationId, parentHandleId, next, goal, configRevision,
                    currentActivationId, lastActivationId, descriptor, options);
        }

        ComponentData withGoal(ComponentGoal next) {
            return new ComponentData(
                    handleId, contextId, mountId, componentId, factoryId, origin,
                    ownerActivationId, parentHandleId, state, next, configRevision,
                    currentActivationId, lastActivationId, descriptor, options);
        }

        ComponentData withActivation(String activationId) {
            return new ComponentData(
                    handleId, contextId, mountId, componentId, factoryId, origin,
                    ownerActivationId, parentHandleId, state, goal, configRevision,
                    activationId, activationId, descriptor, options);
        }

        ComponentData clearActivation() {
            return new ComponentData(
                    handleId, contextId, mountId, componentId, factoryId, origin,
                    ownerActivationId, parentHandleId, state, goal, configRevision,
                    null, lastActivationId, descriptor, options);
        }

        ComponentData withConfigRevision(long revision) {
            return new ComponentData(
                    handleId, contextId, mountId, componentId, factoryId, origin,
                    ownerActivationId, parentHandleId, state, goal, revision,
                    currentActivationId, lastActivationId, descriptor, options);
        }
    }

    record BindingData(
            String registrationId,
            boolean present,
            CapabilityRequirement.Mode mode,
            CapabilityRequirement.CapabilityBinding binding) {
    }

    record ActivationData(
            String activationId,
            String handleId,
            ActivationState state,
            long configRevision,
            Map<String, BindingData> bindings,
            ComponentDescriptor descriptor,
            String scopeId) {

        ActivationData withState(ActivationState next) {
            return new ActivationData(
                    activationId, handleId, next, configRevision,
                    bindings, descriptor, scopeId);
        }

        ActivationData detached() {
            return new ActivationData(
                    activationId, handleId, ActivationState.STOPPING, configRevision,
                    Map.of(), descriptor, scopeId);
        }
    }


    /**
     * 协调器临界区内的私有可变草稿。
     *
     * <p>草稿复制当前视图后可反复试算绑定、所有权和依赖闭包；任意校验失败都直接丢弃，
     * 只有 {@link #publishOnce()} 才会生成新代际并替换 Runtime 的 volatile 视图。</p>
     */
    static final class Draft {
        final long generation;
        final Map<String, ContextData> contexts;
        final Map<String, RegistrationData> registrations;
        final Map<String, ComponentData> components;
        final Map<String, ActivationData> activations;
        final Map<String, Class<?>> capabilityTypes;
        final List<RuntimeDiagnostic> diagnostics;

        Draft(RuntimeView view) {
            this.generation = view.generation;
            this.contexts = new HashMap<>(view.contexts);
            this.registrations = new HashMap<>(view.registrations);
            this.components = new HashMap<>(view.components);
            this.activations = new HashMap<>(view.activations);
            this.capabilityTypes = new HashMap<>(view.capabilityTypes);
            this.diagnostics = new ArrayList<>(view.diagnostics);
        }

        // 复用 RuntimeView 的不可变快照语义做图计算，保证草稿查询与最终发布使用同一解析规则。
        private RuntimeView asView() {
            return new RuntimeView(
                    generation,
                    contexts,
                    registrations,
                    components,
                    activations,
                    capabilityTypes,
                    diagnostics);
        }

        Optional<RegistrationData> resolve(String contextId, CapabilityKey<?> key) {
            return asView().resolve(contextId, key, Map.of());
        }

        Optional<RegistrationData> resolve(
                String contextId,
                CapabilityKey<?> key,
                Map<String, RegistrationData> tentative) {
            return asView().resolve(contextId, key, tentative);
        }

        Map<String, BindingData> effectiveBindings(
                ComponentData component,
                Map<String, RegistrationData> tentative) {
            return asView().effectiveBindings(component, tentative);
        }

        Set<String> contextSubtree(String contextId) {
            return asView().contextSubtree(contextId);
        }

        String canonicalPath(String contextId) {
            return asView().canonicalPath(contextId);
        }

        Set<String> ownershipDescendants(String handleId) {
            return asView().ownershipDescendants(handleId);
        }

        Set<String> ownershipDescendantsForActivation(
                String handleId,
                String ownerActivationId) {
            return asView().ownershipDescendantsForActivation(
                    handleId,
                    ownerActivationId);
        }

        Set<String> dependentsClosure(Set<String> initial) {
            return asView().dependentsClosure(initial);
        }

        List<String> dependentsBeforeProviders(Set<String> handles) {
            return asView().dependentsBeforeProviders(handles);
        }

        List<String> registrationsOwnedBy(Set<String> handleIds) {
            return asView().registrationsOwnedBy(handleIds);
        }

        // 唯一发布点：构造不可变拷贝并推进代际；调用方随后在同一个协调器临界区替换 volatile 引用。
        RuntimeView publishOnce() {
            return new RuntimeView(
                    generation + 1,
                    contexts,
                    registrations,
                    components,
                    activations,
                    capabilityTypes,
                    diagnostics);
        }
    }
}
