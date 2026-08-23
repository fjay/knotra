package io.knotra.internal;

import io.knotra.ComponentGoal;
import io.knotra.ComponentState;
import io.knotra.ContextState;
import io.knotra.PublicationState;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Pure structural mutations over one stable {@link RuntimeView.Draft} phase.
 *
 * <p>Every method mutates only the caller-owned Draft and returns ID/effect values. The caller
 * decides whether those values are merged into an {@link ExecutableCommitPlan}, synchronized into
 * a {@link KernelStateDraft}, or discarded with a rejected transaction. This class never touches
 * live runtimes, handles, provider leases, coordinator state, futures, or user callbacks.</p>
 *
 * <p>RuntimeGraph instances are read-only phase inputs. A graph passed to a mutation is stale when
 * the method returns and is never returned for reuse; a caller that needs another closure must
 * build a new graph from the mutated Draft.</p>
 */
final class StructureGraphMutator {
    private StructureGraphMutator() {
    }

    /** Active-slot ref lookup for one Draft phase, including slots created by this transaction. */
    static Map<String, PublicationSlotTerminalRef> activePublicationSlotRefs(
            ExecutionIndex index,
            ExecutableCommitPlan executable) {
        Map<String, PublicationSlotTerminalRef> refs =
                new HashMap<>(index.publicationSlotRefs);
        executable.createdPublicationSlots.forEach(refs::putIfAbsent);
        return Map.copyOf(refs);
    }

    /**
     * Disposes only the child tree owned by the supplied Activation. Other Activations may keep
     * similarly named descendants alive; WAITING children without an Activation are removed now.
     */
    static MutationResult disposeOwnershipForActivation(
            RuntimeView.Draft draft,
            String parentHandleId,
            String ownerActivationId) {
        RuntimeGraph graph = draft.graph();
        Set<String> ownership =
                graph.ownershipDescendantsForActivation(draft, parentHandleId, ownerActivationId);
        Builder result = new Builder();
        for (String handleId : ownership) {
            RuntimeView.ComponentData component = draft.components.get(handleId);
            if (component == null) {
                continue;
            }
            if (handleId.equals(parentHandleId)) {
                result.live.add(handleId);
                continue;
            }
            draft.components.put(handleId, component.withGoal(ComponentGoal.DISPOSED));
            result.reportedRemovedMounts.put(
                    handleId, new ExecutableCommitPlan.RemovedMount(component.mountId()));
            if (component.currentActivationId() == null) {
                result.merge(removeComponent(draft, handleId));
            } else {
                result.live.add(handleId);
            }
        }
        return result.build();
    }

    /**
     * Disposes the ownership trees of impacted owners, then detaches their dependent closure.
     * This is the common mutation used by reconfigure, revoke, BindingImpact and Activation
     * publication when a provider identity changes.
     */
    static MutationResult disposeOwnersAndDetach(
            RuntimeView.Draft draft,
            Set<String> impacted,
            Map<String, PublicationSlotTerminalRef> publicationSlotRefs) {
        Builder result = new Builder();
        Set<String> detachTargets = new LinkedHashSet<>();
        for (String handleId : impacted) {
            RuntimeView.ComponentData component = draft.components.get(handleId);
            if (component == null || component.currentActivationId() == null) {
                detachTargets.add(handleId);
                continue;
            }
            MutationResult disposal = disposeOwnershipForActivation(
                    draft, handleId, component.currentActivationId());
            result.merge(disposal);
            detachTargets.addAll(disposal.live());
        }
        result.merge(detach(draft, detachTargets, publicationSlotRefs));
        return result.build();
    }

    /**
     * Removes a registration, finds components whose pinned BindingSet still names it, and
     * detaches their ownership/dependent closure. Used by raw revoke, Publication UPDATE, and
     * Publication terminal paths so provider loss has one structural consequence.
     */
    static MutationResult detachConsumersOfRegistration(
            RuntimeView.Draft draft,
            String registrationId,
            Map<String, PublicationSlotTerminalRef> publicationSlotRefs,
            PublicationState displacementReason) {
        Builder result = new Builder();
        result.merge(removeRegistration(
                draft, registrationId, publicationSlotRefs, displacementReason));
        Set<String> direct =
                BindingImpactAnalyzer.componentsWithBinding(draft, Set.of(registrationId));
        result.merge(disposeOwnersAndDetach(draft, direct, publicationSlotRefs));
        return result.build();
    }

    /** Detaches bindings and marks the transitive dependent closure STOPPING. */
    static MutationResult detach(
            RuntimeView.Draft draft,
            Set<String> handles,
            Map<String, PublicationSlotTerminalRef> publicationSlotRefs) {
        return detach(draft, handles, draft.graph(), publicationSlotRefs);
    }

    /**
     * Detaches using a graph built for the current Draft phase. The supplied graph is invalid
     * when this method returns and must not be reused or returned to the caller.
     */
    static MutationResult detach(
            RuntimeView.Draft draft,
            Set<String> handles,
            RuntimeGraph stableGraph,
            Map<String, PublicationSlotTerminalRef> publicationSlotRefs) {
        Objects.requireNonNull(stableGraph, "stableGraph");
        Set<String> closure = stableGraph.dependentsClosure(draft, handles);
        List<String> ownedRegistrations = stableGraph.registrationsOwnedBy(draft, closure);
        Builder result = new Builder();
        for (String registrationId : ownedRegistrations) {
            result.merge(removeRegistration(
                    draft, registrationId, publicationSlotRefs, PublicationState.DISPLACED));
        }
        for (String handleId : closure) {
            RuntimeView.ComponentData component = draft.components.get(handleId);
            if (component == null) {
                continue;
            }
            if (component.currentActivationId() != null) {
                RuntimeView.ActivationData activation =
                        draft.activations.get(component.currentActivationId());
                if (activation != null) {
                    draft.activations.put(
                            component.currentActivationId(), activation.detached());
                }
                result.staleActivations.add(component.currentActivationId());
                draft.components.put(handleId, component.withState(ComponentState.STOPPING));
            }
            result.dirty.add(handleId);
        }
        return result.build();
    }

    /** Removes a component and its current Activation from the structural Draft. Idempotent. */
    static MutationResult removeComponent(RuntimeView.Draft draft, String handleId) {
        RuntimeView.ComponentData data = draft.components.remove(handleId);
        Builder result = new Builder();
        if (data == null) {
            return result.build();
        }
        if (data.currentActivationId() != null) {
            draft.activations.remove(data.currentActivationId());
        }
        result.removedComponents.put(
                handleId, new ExecutableCommitPlan.RemovedMount(data.mountId()));
        return result.build();
    }

    /**
     * Removes one registration from the Draft and records its lease retirement.
     *
     * <p>A non-null terminal reason also removes the active Publication slot and records the
     * terminal effect. A null reason is the Publication UPDATE slot swap: the old registration
     * retires, while the slot remains active for the next current registration.</p>
     */
    static MutationResult removeRegistration(
            RuntimeView.Draft draft,
            String registrationId,
            Map<String, PublicationSlotTerminalRef> publicationSlotRefs,
            PublicationState terminalReason) {
        RuntimeView.RegistrationData registration =
                draft.registrations.remove(registrationId);
        Builder result = new Builder();
        if (registration == null) {
            return result.build();
        }
        result.retiredRegistrations.put(registrationId, registration.leases());
        String slotId = registration.publicationSlotId();
        if (slotId == null || terminalReason == null) {
            return result.build();
        }

        RuntimeView.PublicationSlotData slot = draft.publicationSlots.get(slotId);
        if (slot == null) {
            return result.build();
        }
        PublicationSlotTerminalRef terminalRef = publicationSlotRefs.get(slotId);
        if (terminalRef == null) {
            throw new IllegalStateException(
                    "active publication slot missing terminal ref: " + slotId);
        }
        draft.publicationSlots.remove(slotId);
        draft.activePublicationSlots.remove(new RuntimeView.PublicationSlotKey(
                slot.contextId(), slot.capabilityName()));
        result.terminalPublicationSlots.put(slotId,
                new ExecutableCommitPlan.PublicationTerminalEffect(
                        terminalRef,
                        new PublicationSlotTerminalRef.TerminalData(
                                terminalReason, draft.generation + 1)));
        return result.build();
    }

    /** Common component disposal mutation shared by direct disposal and structural transactions. */
    static MutationResult disposeComponent(
            RuntimeView.Draft draft,
            String handleId,
            Map<String, PublicationSlotTerminalRef> publicationSlotRefs) {
        Builder result = new Builder();
        RuntimeView.ComponentData parent = draft.components.get(handleId);
        if (parent != null) {
            draft.components.put(handleId, parent.withGoal(ComponentGoal.DISPOSED));
            result.reportedRemovedMounts.put(
                    handleId, new ExecutableCommitPlan.RemovedMount(parent.mountId()));
        }
        MutationResult ownership = disposeOwnershipForActivation(
                draft, handleId, parent == null ? null : parent.currentActivationId());
        result.merge(ownership);
        result.merge(disposeOwnersAndDetach(
                draft, ownership.live(), publicationSlotRefs));
        RuntimeView.ComponentData latest = draft.components.get(handleId);
        if (latest != null && latest.currentActivationId() == null) {
            result.merge(removeComponent(draft, handleId));
        } else {
            result.dirty.addAll(ownership.live());
        }
        return result.build();
    }

    /**
     * Publishes the DISPOSING stop graph for a Context subtree and all ownership descendants.
     *
     * <p>Context namespace finalization and failed-cleanup retry intents remain caller concerns;
     * this method only mutates the structural Draft and returns the subtree/handle IDs needed by
     * those phases.</p>
     */
    static MutationResult disposeContext(
            RuntimeView.Draft draft,
            String contextId,
            Map<String, PublicationSlotTerminalRef> publicationSlotRefs) {
        Builder result = new Builder();
        result.contextDisposals.add(contextId);
        result.subtree.addAll(draft.contextSubtree(contextId));
        for (String childId : result.subtree) {
            RuntimeView.ContextData child = draft.contexts.get(childId);
            if (child != null) {
                draft.contexts.put(childId, child.withState(ContextState.DISPOSING));
            }
        }

        Set<String> hostRegistrations = new LinkedHashSet<>();
        for (RuntimeView.RegistrationData registration : draft.registrations.values()) {
            if (result.subtree.contains(registration.contextId())
                    && registration.owner() instanceof RuntimeView.OwnerData.Host) {
                hostRegistrations.add(registration.registrationId());
            }
        }
        for (String registrationId : hostRegistrations) {
            result.merge(removeRegistration(
                    draft,
                    registrationId,
                    publicationSlotRefs,
                    PublicationState.DISPLACED));
        }
        Set<String> externalConsumers = BindingImpactAnalyzer.componentsWithBinding(
                draft, hostRegistrations);

        RuntimeGraph ownershipGraph = draft.graph();
        Set<String> handles = new LinkedHashSet<>();
        for (RuntimeView.ComponentData component : draft.components.values()) {
            if (result.subtree.contains(component.contextId())) {
                handles.addAll(ownershipGraph.ownershipDescendants(
                        draft, component.handleId()));
            }
        }
        for (String handleId : handles) {
            RuntimeView.ComponentData component = draft.components.get(handleId);
            if (component == null) {
                continue;
            }
            draft.components.put(handleId, component.withGoal(ComponentGoal.DISPOSED));
            result.reportedRemovedMounts.put(
                    handleId, new ExecutableCommitPlan.RemovedMount(component.mountId()));
        }
        Set<String> live = new LinkedHashSet<>();
        for (String handleId : handles) {
            RuntimeView.ComponentData component = draft.components.get(handleId);
            if (component != null && component.currentActivationId() != null) {
                live.add(handleId);
            }
        }
        Set<String> impacted = new LinkedHashSet<>(live);
        impacted.addAll(externalConsumers);
        result.merge(disposeOwnersAndDetach(draft, impacted, publicationSlotRefs));

        for (String handleId : handles) {
            RuntimeView.ComponentData component = draft.components.get(handleId);
            if (component != null && component.currentActivationId() == null) {
                result.merge(removeComponent(draft, handleId));
            } else if (component != null) {
                result.dirty.add(handleId);
            }
        }
        return result.build();
    }

    static final class MutationResult {
        private final Set<String> dirty;
        private final Set<String> live;
        private final Set<String> subtree;
        private final Set<String> staleActivations;
        private final Set<String> contextDisposals;
        private final Map<String, ExecutableCommitPlan.RemovedMount> removedComponents;
        private final Map<String, ExecutableCommitPlan.RemovedMount> reportedRemovedMounts;
        private final Map<String, ProviderLeaseRuntime> retiredRegistrations;
        private final Map<String, ExecutableCommitPlan.PublicationTerminalEffect>
                terminalPublicationSlots;

        private MutationResult(
                Set<String> dirty,
                Set<String> live,
                Set<String> subtree,
                Set<String> staleActivations,
                Set<String> contextDisposals,
                Map<String, ExecutableCommitPlan.RemovedMount> removedComponents,
                Map<String, ExecutableCommitPlan.RemovedMount> reportedRemovedMounts,
                Map<String, ProviderLeaseRuntime> retiredRegistrations,
                Map<String, ExecutableCommitPlan.PublicationTerminalEffect>
                        terminalPublicationSlots) {
            this.dirty = Collections.unmodifiableSet(new LinkedHashSet<>(dirty));
            this.live = Collections.unmodifiableSet(new LinkedHashSet<>(live));
            this.subtree = Collections.unmodifiableSet(new LinkedHashSet<>(subtree));
            this.staleActivations =
                    Collections.unmodifiableSet(new LinkedHashSet<>(staleActivations));
            this.contextDisposals =
                    Collections.unmodifiableSet(new LinkedHashSet<>(contextDisposals));
            this.removedComponents =
                    Collections.unmodifiableMap(new LinkedHashMap<>(removedComponents));
            this.reportedRemovedMounts =
                    Collections.unmodifiableMap(new LinkedHashMap<>(reportedRemovedMounts));
            this.retiredRegistrations =
                    Collections.unmodifiableMap(new LinkedHashMap<>(retiredRegistrations));
            this.terminalPublicationSlots = Collections.unmodifiableMap(
                    new LinkedHashMap<>(terminalPublicationSlots));
        }

        Set<String> dirty() {
            return dirty;
        }

        Set<String> live() {
            return live;
        }

        Set<String> subtree() {
            return subtree;
        }


        Set<String> contextDisposals() {
            return contextDisposals;
        }
        Set<String> staleActivations() {
            return staleActivations;
        }

        Map<String, ExecutableCommitPlan.RemovedMount> removedComponents() {
            return removedComponents;
        }

        Map<String, ExecutableCommitPlan.RemovedMount> reportedRemovedMounts() {
            return reportedRemovedMounts;
        }

        Map<String, ProviderLeaseRuntime> retiredRegistrations() {
            return retiredRegistrations;
        }

        Map<String, ExecutableCommitPlan.PublicationTerminalEffect>
        terminalPublicationSlots() {
            return terminalPublicationSlots;
        }

        /** Merges pure effect IDs into the caller-owned executable plan. */
        void applyTo(ExecutableCommitPlan executable) {
            reportedRemovedMounts.forEach(executable.reportedRemovedMounts::putIfAbsent);
            removedComponents.forEach(executable.removedComponents::putIfAbsent);
            staleActivations.forEach(executable.staleActivations::add);
            contextDisposals.forEach(executable.contextDisposals::add);
            retiredRegistrations.forEach(executable.retiredRegistrations::putIfAbsent);
            terminalPublicationSlots.forEach(
                    executable.terminalPublicationSlots::putIfAbsent);
        }
    }

    private static final class Builder {
        private final Set<String> dirty = new LinkedHashSet<>();
        private final Set<String> live = new LinkedHashSet<>();
        private final Set<String> subtree = new LinkedHashSet<>();
        private final Set<String> staleActivations = new LinkedHashSet<>();
        private final Set<String> contextDisposals = new LinkedHashSet<>();
        private final Map<String, ExecutableCommitPlan.RemovedMount> removedComponents =
                new LinkedHashMap<>();
        private final Map<String, ExecutableCommitPlan.RemovedMount> reportedRemovedMounts =
                new LinkedHashMap<>();
        private final Map<String, ProviderLeaseRuntime> retiredRegistrations =
                new HashMap<>();
        private final Map<String, ExecutableCommitPlan.PublicationTerminalEffect>
                terminalPublicationSlots = new LinkedHashMap<>();

        private void merge(MutationResult result) {
            dirty.addAll(result.dirty());
            live.addAll(result.live());
            subtree.addAll(result.subtree());
            staleActivations.addAll(result.staleActivations());
            contextDisposals.addAll(result.contextDisposals());
            removedComponents.putAll(result.removedComponents());
            reportedRemovedMounts.putAll(result.reportedRemovedMounts());
            retiredRegistrations.putAll(result.retiredRegistrations());
            terminalPublicationSlots.putAll(result.terminalPublicationSlots());
        }

        private MutationResult build() {
            return new MutationResult(
                    dirty,
                    live,
                    subtree,
                    staleActivations,
                    contextDisposals,
                    removedComponents,
                    reportedRemovedMounts,
                    retiredRegistrations,
                    terminalPublicationSlots);
        }
    }
}
