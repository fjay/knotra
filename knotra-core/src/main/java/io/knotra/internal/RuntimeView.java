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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Immutable committed Runtime state snapshot; the generation identifies its lineage.
 *
 * <p>DefaultKnotraRuntime replaces the whole view through {@link Draft#publishOnce}
 * inside the coordinator lock. Lock-free readers that retain an older snapshot therefore
 * still see one self-consistent structural combination. Tentative capabilities, child
 * mounts, and state transitions in a Draft never affect an older generation.</p>
 */
final class RuntimeView implements RuntimeViewReader {
    // Every successful publish advances the whole value; rejected transactions reuse the
    // old view and do not create a generation.
    final long generation;
    final Map<String, ContextData> contexts;
    final Map<String, RegistrationData> registrations;
    final Map<String, ComponentData> components;
    final Map<String, ActivationData> activations;
    final List<RuntimeDiagnostic> diagnostics;

    private RuntimeView(
            long generation,
            Map<String, ContextData> contexts,
            Map<String, RegistrationData> registrations,
            Map<String, ComponentData> components,
            Map<String, ActivationData> activations,
            List<RuntimeDiagnostic> diagnostics) {
        this.generation = generation;
        this.contexts = Map.copyOf(contexts);
        this.registrations = Map.copyOf(registrations);
        this.components = Map.copyOf(components);
        this.activations = Map.copyOf(activations);
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
                List.of());
    }

    public long generation() {
        return generation;
    }

    @Override
    public Map<String, ContextData> contexts() {
        return contexts;
    }

    @Override
    public Map<String, RegistrationData> registrations() {
        return registrations;
    }

    @Override
    public Map<String, ComponentData> components() {
        return components;
    }

    @Override
    public Map<String, ActivationData> activations() {
        return activations;
    }

    public List<RuntimeDiagnostic> diagnostics() {
        return diagnostics;
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

    // Resolution walks the current Context to its root; child registrations shadow parent
    // registrations, while tentative registrations remain level-local.
    Optional<RegistrationData> resolve(
            String contextId,
            CapabilityKey<?> key,
            Map<String, RegistrationData> tentative) {
        return RuntimeGraph.resolveDirect(this, tentative, contextId, key);
    }

    Map<String, BindingData> effectiveBindings(
            ComponentData component,
            Map<String, RegistrationData> tentative) {
        return RuntimeGraph.effectiveBindingsDirect(this, tentative, component);
    }

    Map<String, Set<String>> dependencyGraph(Map<String, RegistrationData> tentative) {
        return RuntimeGraph.of(this, tentative).dependencyGraph(this, tentative);
    }

    Set<String> contextSubtree(String contextId) {
        return RuntimeGraph.of(this).contextSubtree(this, contextId);
    }

    boolean isInSubtree(String candidate, String root) {
        return RuntimeGraph.of(this).isInSubtree(candidate, root);
    }

    Set<String> ownershipDescendants(String handleId) {
        return RuntimeGraph.of(this).ownershipDescendants(this, handleId);
    }

    Set<String> ownershipDescendantsForActivation(
            String handleId,
            String ownerActivationId) {
        return RuntimeGraph.of(this)
                .ownershipDescendantsForActivation(this, handleId, ownerActivationId);
    }

    Set<String> dependentsClosure(Set<String> initial) {
        return RuntimeGraph.of(this).dependentsClosure(this, initial);
    }

    List<String> dependentsBeforeProviders(Set<String> handles) {
        return RuntimeGraph.of(this).dependentsBeforeProviders(this, Map.of(), handles);
    }

    List<String> registrationsOwnedBy(Set<String> handleIds) {
        return RuntimeGraph.of(this).registrationsOwnedBy(this, handleIds);
    }

    String canonicalPath(String contextId) {
        return RuntimeGraph.of(this).canonicalPath(this, contextId);
    }

    static boolean activationTracksGraph(ActivationState state) {
        return RuntimeGraph.activationTracksGraph(state);
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
     * Private mutable Draft owned by the coordinator critical section.
     *
     * <p>A Draft starts as a shallow copy of the current view and may repeatedly evaluate
     * bindings, ownership, and dependency closures. Any validation failure discards it;
     * only {@link #publishOnce()} creates a new generation and replaces the Runtime's
     * volatile view.</p>
     */
    static final class Draft implements RuntimeViewReader {
        final long generation;
        final Map<String, ContextData> contexts;
        final Map<String, RegistrationData> registrations;
        final Map<String, ComponentData> components;
        final Map<String, ActivationData> activations;
        // Capability name/type identity remains fixed while a live registration or
        // requirement occupies it; after all release, another ClassLoader may reuse the
        // same type name. Drafts are short-lived coordinator objects and may temporarily
        // retain Class; published RuntimeViews must not retain Class or ClassLoader.
        final Map<String, Class<?>> capabilityTypes;
        final List<RuntimeDiagnostic> diagnostics;

        Draft(RuntimeView view) {
            this.generation = view.generation;
            this.contexts = new HashMap<>(view.contexts);
            this.registrations = new HashMap<>(view.registrations);
            this.components = new HashMap<>(view.components);
            this.activations = new HashMap<>(view.activations);
            this.capabilityTypes = liveCapabilityTypes(view);
            this.diagnostics = new ArrayList<>(view.diagnostics);
        }

        private static Map<String, Class<?>> liveCapabilityTypes(RuntimeView view) {
            Map<String, Class<?>> result = new HashMap<>();
            view.registrations.values().forEach(registration ->
                    result.putIfAbsent(
                            registration.key().name(),
                            registration.key().type()));
            view.components.values().forEach(component ->
                    component.descriptor().sortedRequirements().forEach(requirement ->
                            result.putIfAbsent(
                                    requirement.key().name(),
                                    requirement.key().type())));
            return result;
        }

        public long generation() {
            return generation;
        }

        @Override
        public Map<String, ContextData> contexts() {
            return contexts;
        }

        @Override
        public Map<String, RegistrationData> registrations() {
            return registrations;
        }

        @Override
        public Map<String, ComponentData> components() {
            return components;
        }

        @Override
        public Map<String, ActivationData> activations() {
            return activations;
        }

        public List<RuntimeDiagnostic> diagnostics() {
            return diagnostics;
        }

        /**
         * Builds a graph for the Draft's current stable phase.
         *
         * <p>Drafts do not cache graphs. Any mutation makes every previously returned graph
         * stale; callers must drop it and call this method again. The returned graph and
         * its caller-supplied tentative map must also be discarded together.</p>
         */
        RuntimeGraph graph() {
            return RuntimeGraph.of(this);
        }

        Optional<RegistrationData> resolve(String contextId, CapabilityKey<?> key) {
            return RuntimeGraph.resolveDirect(this, Map.of(), contextId, key);
        }

        Optional<RegistrationData> resolve(
                String contextId,
                CapabilityKey<?> key,
                Map<String, RegistrationData> tentative) {
            return RuntimeGraph.resolveDirect(this, tentative, contextId, key);
        }

        Map<String, BindingData> effectiveBindings(
                ComponentData component,
                Map<String, RegistrationData> tentative) {
            return RuntimeGraph.effectiveBindingsDirect(this, tentative, component);
        }

        Set<String> contextSubtree(String contextId) {
            return graph().contextSubtree(this, contextId);
        }

        String canonicalPath(String contextId) {
            return graph().canonicalPath(this, contextId);
        }

        Set<String> ownershipDescendants(String handleId) {
            return graph().ownershipDescendants(this, handleId);
        }

        Set<String> ownershipDescendantsForActivation(
                String handleId,
                String ownerActivationId) {
            return graph().ownershipDescendantsForActivation(
                    this,
                    handleId,
                    ownerActivationId);
        }

        Set<String> dependentsClosure(Set<String> initial) {
            return graph().dependentsClosure(this, initial);
        }

        List<String> dependentsBeforeProviders(Set<String> handles) {
            return graph().dependentsBeforeProviders(this, Map.of(), handles);
        }

        List<String> registrationsOwnedBy(Set<String> handleIds) {
            return graph().registrationsOwnedBy(this, handleIds);
        }

        // The single publish point constructs immutable copies and advances the generation;
        // the caller then replaces the volatile reference in the same coordinator section.
        RuntimeView publishOnce() {
            return new RuntimeView(
                    generation + 1,
                    contexts,
                    registrations,
                    components,
                    activations,
                    diagnostics);
        }
    }
}
