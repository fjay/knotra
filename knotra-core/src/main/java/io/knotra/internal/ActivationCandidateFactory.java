package io.knotra.internal;

import io.knotra.ActivationState;
import io.knotra.CapabilityRequirement;
import io.knotra.ComponentGoal;
import io.knotra.ComponentState;
import io.knotra.ContextState;
import io.knotra.FailureInfo;
import io.knotra.FailurePhase;
import io.knotra.KnotraConfig;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Pure prepublish construction for one Activation decision.
 *
 * <p>The factory consumes one published generation and frozen facts captured by the runtime
 * coordinator. It mutates only caller-owned Draft/Executable objects and never publishes kernel
 * state, applies live owner effects, retires leases, reserves transitions, executes user code,
 * or completes futures. Transition planning is deliberately left to the runtime so a completed
 * draft can still be discarded before any reservation exists.</p>
 */
final class ActivationCandidateFactory {
    private final KnotraConfig configuration;

    ActivationCandidateFactory(KnotraConfig configuration) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
    }

    CommitDecision validate(
            FrozenActivationInputs inputs,
            PublishedKernelState state) {
        Objects.requireNonNull(inputs, "inputs");
        Objects.requireNonNull(state, "state");
        requireSameBase(inputs, state);
        RuntimeView current = state.view;
        RuntimeView.ComponentData data =
                current.components.get(inputs.owner().handleId());
        if (data == null || data.goal() != ComponentGoal.RUNNING) {
            return CommitDecision.stale("component goal changed");
        }
        RuntimeView.ContextData context = current.contexts.get(data.contextId());
        if (context == null || context.state() != ContextState.ACTIVE) {
            return CommitDecision.stale("context changed");
        }
        if (data.configRevision() != inputs.activation().configRevision()
                || inputs.owner().desiredRevision()
                        != inputs.activation().configRevision()) {
            return CommitDecision.stale("configuration changed");
        }
        if (inputs.activation().stale()) {
            return CommitDecision.stale("activation became stale");
        }

        Map<String, RuntimeView.RegistrationData> stagedRegistrations =
                inputs.stagedRegistrations();
        RuntimeGraph currentGraph = RuntimeGraph.of(current, stagedRegistrations);
        RuntimeGraph.ResolutionCache resolutions = RuntimeGraph.resolutionCache();
        Map<String, RuntimeView.BindingData> effectiveBindings =
                currentGraph.effectiveBindings(
                        current, stagedRegistrations, resolutions, data);
        for (CapabilityRequirement requirement
                : data.descriptor().sortedRequirements()) {
            if (requirement.binding()
                    == CapabilityRequirement.CapabilityBinding.DYNAMIC) {
                if (requirement.mode() == CapabilityRequirement.Mode.REQUIRED) {
                    boolean initialPresence = inputs.activation()
                            .initialDynamicRequiredPresence()
                            .getOrDefault(requirement.key().name(), false);
                    boolean currentPresence = currentGraph.resolve(
                            current,
                            stagedRegistrations,
                            resolutions,
                            data.contextId(),
                            requirement.key())
                            .isPresent();
                    if (initialPresence != currentPresence) {
                        return CommitDecision.stale(
                                "dynamic binding presence changed: "
                                        + requirement.key().name());
                    }
                }
                continue;
            }
            RuntimeView.BindingData captured = inputs.activation()
                    .bindings()
                    .get(requirement.key().name());
            RuntimeView.BindingData effective =
                    effectiveBindings.get(requirement.key().name());
            if (!BindingImpactAnalyzer.bindingIdentityEqual(captured, effective)) {
                return CommitDecision.stale(
                        "binding changed: " + requirement.key().name());
            }
        }

        for (RuntimeView.RegistrationData staged : stagedRegistrations.values()) {
            Class<?> existing = inputs.capabilityTypes()
                    .get(staged.key().name());
            if (existing != null && existing != staged.key().type()) {
                return CommitDecision.startFailed(
                        "staged capability type conflict: " + staged.key().name());
            }
            boolean occupied = current.registrations.values().stream().anyMatch(
                    registration -> registration.contextId()
                                    .equals(staged.contextId())
                            && registration.key().name().equals(staged.key().name()));
            if (occupied) {
                return CommitDecision.startFailed(
                        "staged capability slot occupied: " + staged.key().name());
            }
        }

        String childConflict = childPlanConflict(
                current, data.contextId(), inputs);
        if (childConflict != null) {
            return CommitDecision.startFailed(childConflict);
        }
        if (RuntimeGraph.hasCycle(
                currentGraph.dependencyGraph(current, stagedRegistrations))) {
            return CommitDecision.cycleRejected(
                    "binding cycle rejected: " + inputs.owner().handleId());
        }
        if (inputs.startEvidence().failed()) {
            return CommitDecision.startFailed(inputs.startEvidence().summary());
        }
        return CommitDecision.success();
    }

    PreparedCandidate prepare(
            FrozenActivationInputs inputs,
            PublishedKernelState state,
            CommitDecision decision) {
        return prepareDecision(inputs, state, decision, false);
    }

    PreparedCandidate prepareAborted(
            FrozenActivationInputs inputs,
            PublishedKernelState state,
            CommitDecision decision) {
        return prepareDecision(inputs, state, decision, true);
    }

    private PreparedCandidate prepareDecision(
            FrozenActivationInputs inputs,
            PublishedKernelState state,
            CommitDecision decision,
            boolean abortedCandidate) {
        Objects.requireNonNull(inputs, "inputs");
        Objects.requireNonNull(state, "state");
        requireSameBase(inputs, state);
        Objects.requireNonNull(decision, "decision");
        RuntimeView.Draft draft = new RuntimeView.Draft(state.view);
        KernelStateDraft indexDraft = new KernelStateDraft(state);
        ExecutableCommitPlan executable = new ExecutableCommitPlan();
        Set<String> dirty = publishActivationDecision(
                draft, inputs, decision, executable);
        return new PreparedCandidate(
                draft,
                indexDraft,
                executable,
                dirty,
                ownerEffectFor(inputs.owner(), decision),
                abortedCandidate,
                "",
                true);
    }

    PreparedCandidate prepareEmergency(
            FrozenActivationInputs inputs,
            PublishedKernelState state,
            String message,
            boolean fatalPath) {
        Objects.requireNonNull(inputs, "inputs");
        Objects.requireNonNull(state, "state");
        requireSameBase(inputs, state);
        Objects.requireNonNull(message, "message");
        RuntimeView.Draft draft = new RuntimeView.Draft(state.view);
        KernelStateDraft indexDraft = new KernelStateDraft(state);
        RuntimeView.ComponentData data =
                draft.components.get(inputs.owner().handleId());
        if (data != null) {
            draft.components.put(
                    inputs.owner().handleId(),
                    data.withState(ComponentState.FAILED));
        }
        RuntimeView.ActivationData activationData =
                draft.activations.get(inputs.activation().activationId());
        if (activationData != null) {
            draft.activations.put(
                    inputs.activation().activationId(),
                    activationData.withState(ActivationState.FAILED));
        }
        ActivationOwnerEffect ownerEffect = new ActivationOwnerEffect(
                fatalPath,
                (fatalPath && inputs.startEvidence().failed())
                        || inputs.owner().pendingStartFailure(),
                inputs.owner().suppressAutoRestart(),
                true,
                fatalPath ? message : inputs.owner().lastStartError(),
                inputs.owner().lastStartFailure());
        return new PreparedCandidate(
                draft,
                indexDraft,
                new ExecutableCommitPlan(),
                Set.of(),
                ownerEffect,
                true,
                message,
                false);
    }

    private String childPlanConflict(
            RuntimeView current,
            String contextId,
            FrozenActivationInputs inputs) {
        Map<String, Class<?>> tentativeTypes = new LinkedHashMap<>(
                inputs.capabilityTypes());
        for (RuntimeView.RegistrationData staged
                : activationRegistrationsForValidation(current, inputs)
                        .values()) {
            Class<?> existing = tentativeTypes.putIfAbsent(
                    staged.key().name(), staged.key().type());
            if (existing != null && existing != staged.key().type()) {
                return "staged capability type conflict: " + staged.key().name();
            }
        }

        Set<String> batchIds = new LinkedHashSet<>();
        for (ChildMountPlan plan : inputs.plans()) {
            String identity = contextId + "/" + plan.mountId();
            if (!batchIds.add(identity)) {
                return "staged child mountId conflicts in transaction: "
                        + plan.mountId();
            }
            boolean occupied = current.components.values().stream().anyMatch(component ->
                    component.contextId().equals(contextId)
                            && component.mountId().equals(plan.mountId()));
            if (occupied) {
                return "staged child mountId conflicts latest view: "
                        + plan.mountId();
            }
            for (CapabilityRequirement requirement
                    : plan.prepared().descriptor().sortedRequirements()) {
                Class<?> existing = tentativeTypes.putIfAbsent(
                        requirement.key().name(), requirement.key().type());
                if (existing != null && existing != requirement.key().type()) {
                    return "staged child capability type conflict: "
                            + requirement.key().name();
                }
            }
        }
        return null;
    }

    private Map<String, RuntimeView.RegistrationData>
            activationRegistrationsForValidation(
            RuntimeView current,
            FrozenActivationInputs inputs) {
        Map<String, RuntimeView.RegistrationData> registrations = new LinkedHashMap<>();
        for (FrozenActivationInputs.PendingActivation pending
                : inputs.pendingActivations()) {
            RuntimeView.ActivationData data =
                    current.activations.get(pending.activationId());
            if (pending.stale()
                    || data == null
                    || data.state() == ActivationState.STOPPING) {
                continue;
            }
            registrations.putAll(pending.stagedRegistrations());
        }
        return registrations;
    }

    private Set<String> publishActivationDecision(
            RuntimeView.Draft draft,
            FrozenActivationInputs inputs,
            CommitDecision decision,
            ExecutableCommitPlan executable) {
        RuntimeView.ComponentData data =
                draft.components.get(inputs.owner().handleId());
        RuntimeView.ActivationData activationData =
                draft.activations.get(inputs.activation().activationId());
        if (data == null || activationData == null) {
            return Set.of();
        }
        Map<String, RuntimeView.RegistrationData> stagedRegistrations =
                inputs.stagedRegistrations();
        if (decision.successful()) {
            for (RuntimeView.RegistrationData staged : stagedRegistrations.values()) {
                draft.registrations.put(staged.registrationId(), staged);
                draft.capabilityTypes.putIfAbsent(
                        staged.key().name(), staged.key().type());
            }
            for (ChildMountPlan plan : inputs.plans()) {
                for (CapabilityRequirement requirement
                        : plan.prepared().descriptor().sortedRequirements()) {
                    draft.capabilityTypes.putIfAbsent(
                            requirement.key().name(), requirement.key().type());
                }
                String childId = plan.handle().handleId();
                draft.components.put(childId, new RuntimeView.ComponentData(
                        childId,
                        data.contextId(),
                        plan.mountId(),
                        plan.prepared().descriptor().componentId(),
                        plan.prepared().factoryId(),
                        plan.prepared().options().origin(),
                        inputs.activation().activationId(),
                        inputs.owner().handleId(),
                        ComponentState.WAITING,
                        ComponentGoal.RUNNING,
                        1,
                        null,
                        null,
                        plan.prepared().descriptor(),
                        plan.prepared().options()));
            }
            draft.activations.put(
                    inputs.activation().activationId(),
                    activationData.withState(ActivationState.ACTIVE));
            draft.components.put(
                    inputs.owner().handleId(),
                    data.withState(ComponentState.ACTIVE));

            Set<String> changed = new LinkedHashSet<>();
            RuntimeGraph effectiveGraph = RuntimeGraph.of(draft, stagedRegistrations);
            RuntimeGraph.ResolutionCache effectiveResolutions =
                    RuntimeGraph.resolutionCache();
            for (RuntimeView.ComponentData component : draft.components.values()) {
                if (component.currentActivationId() == null
                        || component.handleId().equals(inputs.owner().handleId())) {
                    continue;
                }
                RuntimeView.ActivationData other = draft.activations.get(
                        component.currentActivationId());
                if (other == null
                        || !RuntimeView.activationTracksGraph(other.state())) {
                    continue;
                }
                Map<String, RuntimeView.BindingData> effective =
                        effectiveGraph.effectiveBindings(
                                draft,
                                stagedRegistrations,
                                effectiveResolutions,
                                component);
                for (CapabilityRequirement requirement
                        : component.descriptor().sortedRequirements()) {
                    RuntimeView.BindingData old =
                            other.bindings().get(requirement.key().name());
                    RuntimeView.BindingData next =
                            effective.get(requirement.key().name());
                    if (!BindingImpactAnalyzer.bindingIdentityEqual(old, next)) {
                        changed.add(component.handleId());
                        break;
                    }
                }
            }

            Set<String> dirty = new LinkedHashSet<>();
            StructureGraphMutator.MutationResult impacted =
                    StructureGraphMutator.disposeOwnersAndDetach(
                            draft,
                            changed,
                            StructureGraphMutator.activePublicationSlotRefs(
                                    inputs.base().index, executable));
            impacted.applyTo(executable);
            dirty.addAll(impacted.dirty());
            return dirty;
        }

        draft.activations.put(
                inputs.activation().activationId(),
                activationData.detached());
        draft.components.put(
                inputs.owner().handleId(),
                data.withState(ComponentState.STOPPING));
        return new LinkedHashSet<>(Set.of(inputs.owner().handleId()));
    }

    private static void requireSameBase(
            FrozenActivationInputs inputs,
            PublishedKernelState state) {
        if (inputs.base() != state) {
            throw new IllegalStateException(
                    "activation facts are not based on caller state");
        }
    }

    private ActivationOwnerEffect ownerEffectFor(
            FrozenActivationInputs.OwnerFacts owner,
            CommitDecision decision) {
        boolean successful = decision.successful();
        boolean staleCandidate = decision.staleCandidate();
        String lastStartError;
        FailureInfo lastStartFailure;
        if (successful || staleCandidate) {
            lastStartError = "";
            lastStartFailure = FailureInfo.EMPTY;
        } else {
            lastStartError = decision.message();
            lastStartFailure = FailureInfo.EMPTY.equals(owner.lastStartFailure())
                    ? syntheticActivationFailure(decision.message())
                    : owner.lastStartFailure();
        }
        return new ActivationOwnerEffect(
                !successful || staleCandidate,
                !successful && !staleCandidate && !decision.suppressCycle(),
                decision.suppressCycle(),
                false,
                lastStartError,
                lastStartFailure);
    }

    private FailureInfo syntheticActivationFailure(String message) {
        int maxLength = configuration.failureDetailPolicy().maxTextLength();
        String safeMessage = message == null ? "" : message.trim();
        if (safeMessage.length() > maxLength) {
            safeMessage = safeMessage.substring(0, maxLength);
        }
        return new FailureInfo(
                FailurePhase.ACTIVATION,
                IllegalStateException.class.getName(),
                safeMessage,
                List.of(),
                List.of(),
                Instant.now());
    }

    static final class PreparedCandidate {
        private final RuntimeView.Draft draft;
        private final KernelStateDraft indexDraft;
        private final ExecutableCommitPlan executable;
        private final Set<String> dirty;
        private final ActivationOwnerEffect ownerEffect;
        private final boolean abortedCandidate;
        private final String emergencyMessage;
        private final boolean scheduleTransitions;

        private PreparedCandidate(
                RuntimeView.Draft draft,
                KernelStateDraft indexDraft,
                ExecutableCommitPlan executable,
                Set<String> dirty,
                ActivationOwnerEffect ownerEffect,
                boolean abortedCandidate,
                String emergencyMessage,
                boolean scheduleTransitions) {
            this.draft = Objects.requireNonNull(draft, "draft");
            this.indexDraft = Objects.requireNonNull(indexDraft, "indexDraft");
            this.executable = Objects.requireNonNull(executable, "executable");
            this.dirty = Set.copyOf(Objects.requireNonNull(dirty, "dirty"));
            this.ownerEffect = Objects.requireNonNull(ownerEffect, "ownerEffect");
            this.abortedCandidate = abortedCandidate;
            this.emergencyMessage = Objects.requireNonNull(
                    emergencyMessage, "emergencyMessage");
            this.scheduleTransitions = scheduleTransitions;
        }

        RuntimeView.Draft draft() {
            return draft;
        }

        KernelStateDraft indexDraft() {
            return indexDraft;
        }

        ExecutableCommitPlan executable() {
            return executable;
        }

        Set<String> dirty() {
            return dirty;
        }

        boolean scheduleTransitions() {
            return scheduleTransitions;
        }

        ActivationCommitCandidate toCandidate(TransitionPlan transitionPlan) {
            return new ActivationCommitCandidate(
                    draft,
                    indexDraft,
                    transitionPlan,
                    ownerEffect,
                    new ActivationPostCommitEffects(
                            dirty,
                            Map.copyOf(executable.retiredRegistrations)),
                    executable.staleActivations,
                    abortedCandidate,
                    emergencyMessage,
                    executable);
        }
    }

    static record FrozenActivationInputs(
            PublishedKernelState base,
            List<ChildMountPlan> plans,
            Map<String, RuntimeView.RegistrationData> stagedRegistrations,
            StartFailureEvidence startEvidence,
            Map<String, Class<?>> capabilityTypes,
            List<PendingActivation> pendingActivations,
            OwnerFacts owner,
            ActivationFacts activation) {

        FrozenActivationInputs {
            Objects.requireNonNull(base, "base");
            plans = List.copyOf(plans);
            stagedRegistrations = Map.copyOf(stagedRegistrations);
            Objects.requireNonNull(startEvidence, "startEvidence");
            capabilityTypes = Map.copyOf(capabilityTypes);
            pendingActivations = List.copyOf(pendingActivations);
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(activation, "activation");
        }


        record PendingActivation(
                String activationId,
                boolean stale,
                Map<String, RuntimeView.RegistrationData> stagedRegistrations) {
            PendingActivation {
                Objects.requireNonNull(activationId, "activationId");
                stagedRegistrations = Map.copyOf(stagedRegistrations);
            }
        }

        record OwnerFacts(
                String handleId,
                long desiredRevision,
                boolean pendingStartFailure,
                String lastStartError,
                FailureInfo lastStartFailure,
                boolean suppressAutoRestart) {
            OwnerFacts {
                Objects.requireNonNull(handleId, "handleId");
                Objects.requireNonNull(lastStartError, "lastStartError");
                Objects.requireNonNull(lastStartFailure, "lastStartFailure");
            }
        }

        record ActivationFacts(
                String activationId,
                long configRevision,
                Map<String, RuntimeView.BindingData> bindings,
                Map<String, Boolean> initialDynamicRequiredPresence,
                boolean stale) {
            ActivationFacts {
                Objects.requireNonNull(activationId, "activationId");
                bindings = Map.copyOf(bindings);
                initialDynamicRequiredPresence =
                        Map.copyOf(initialDynamicRequiredPresence);
            }
        }
    }
}
