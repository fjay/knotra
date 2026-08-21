package io.knotra.internal;

import io.knotra.ActivationContext;
import io.knotra.ActivationState;
import io.knotra.CapabilityKey;
import io.knotra.CapabilityRequirement;
import io.knotra.ComponentGoal;
import io.knotra.ComponentHandle;
import io.knotra.ComponentFactory;
import io.knotra.ComponentOrigin;
import io.knotra.ComponentState;
import io.knotra.ConfigSchema;
import io.knotra.ContextHandle;
import io.knotra.ContextInfo;
import io.knotra.ContextState;
import io.knotra.DiagnosticCode;
import io.knotra.KnotraConfig;
import io.knotra.KnotraRuntime;
import io.knotra.LifecycleState;
import io.knotra.MountOptions;
import io.knotra.MutationResult;
import io.knotra.RegistrationHandle;
import io.knotra.RuntimeDiagnostic;
import io.knotra.RuntimeMutation;
import io.knotra.RuntimeSnapshot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class DefaultKnotraRuntime implements KnotraRuntime {
    private final KnotraConfig configuration;
    private final Object coordinator = new Object();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private volatile RuntimeView view = RuntimeView.initial();

    private final Map<String, ComponentRuntime> components = new ConcurrentHashMap<>();
    private final Map<String, ComponentHandleImpl<?>> componentHandles =
            new ConcurrentHashMap<>();
    private final Map<String, ComponentState> terminalComponents = new ConcurrentHashMap<>();
    private final Map<String, ActivationRuntime> activations = new ConcurrentHashMap<>();
    private final Map<String, RegistrationHandleImpl> registrationHandles =
            new ConcurrentHashMap<>();
    private final Map<String, ContextHandleImpl> contextHandles = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<Void>> contextFutures =
            new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<ComponentState>> disposeRequests =
            new ConcurrentHashMap<>();
    private final AtomicBoolean closing = new AtomicBoolean();
    private final AtomicReference<CompletableFuture<Void>> closeFuture =
            new AtomicReference<>();

    public DefaultKnotraRuntime(KnotraConfig configuration) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        contextHandles.put("ctx-root", new ContextHandleImpl(this, "ctx-root"));
    }

    @Override
    public String runtimeId() {
        return configuration.runtimeId();
    }

    @Override
    public ContextHandle rootContext() {
        return contextHandles.get("ctx-root");
    }

    @Override
    public RuntimeContextImpl context() {
        return new RuntimeContextImpl(this, "ctx-root");
    }

    @Override
    public RuntimeSnapshot snapshot() {
        RuntimeSnapshot partial;
        Map<String, ActivationRuntime> activationCopy;
        synchronized (coordinator) {
            partial = view.snapshotWithoutScopes();
            activationCopy = new HashMap<>(activations);
        }
        List<RuntimeSnapshot.LifecycleScopeSnapshot> scopes = activationCopy.values().stream()
                .flatMap(activation ->
                        activation.scope.snapshots(activation.activationId).stream())
                .sorted(Comparator.comparing(
                        RuntimeSnapshot.LifecycleScopeSnapshot::scopeId))
                .toList();
        return new RuntimeSnapshot(
                partial.generation(),
                partial.contexts(),
                partial.components(),
                partial.activations(),
                partial.registrations(),
                scopes,
                partial.diagnostics());
    }

    @Override
    public <R> MutationResult<R> mutate(Function<RuntimeMutation, R> action) {
        Objects.requireNonNull(action, "action");
        MutationRecorder recorder = new MutationRecorder();
        R callbackValue;
        try {
            callbackValue = action.apply(recorder);
        } catch (Reject rejection) {
            return MutationResult.rejected(List.of(rejection.diagnostic()));
        } catch (RuntimeException error) {
            return MutationResult.rejected(List.of(new RuntimeDiagnostic(
                    DiagnosticCode.INVALID_LIFECYCLE_OPERATION,
                    configuration.runtimeId(),
                    LifecycleScopeImpl.safeError(error))));
        }

        long committedGeneration = -1;
        Set<String> postCommitDirty = new LinkedHashSet<>();
        Set<String> contextDisposals = new LinkedHashSet<>();
        synchronized (coordinator) {
            if (closing.get()) {
                return MutationResult.rejected(List.of(new RuntimeDiagnostic(
                        DiagnosticCode.INVALID_LIFECYCLE_OPERATION,
                        configuration.runtimeId(),
                        "runtime is closing")));
            }
            if (recorder.intents.isEmpty()) {
                return MutationResult.committed(
                        callbackValue,
                        view.generation,
                        CompletableFuture.completedFuture(null));
            }

            RuntimeView.Draft draft = new RuntimeView.Draft(view);
            ExecutableCommitPlan executable = new ExecutableCommitPlan();
            Set<String> dirty = new LinkedHashSet<>();
            boolean viewChanged = false;
            try {
                for (Intent intent : recorder.intents) {
                    viewChanged |= applyIntent(
                            draft,
                            intent,
                            dirty,
                            executable);
                }
                markBindingImpacts(draft, dirty, executable);
                refreshDiagnostics(draft);
                if (!viewChanged) {
                    return MutationResult.committed(
                            callbackValue,
                            view.generation,
                            CompletableFuture.completedFuture(null));
                }
                RuntimeView next = draft.publishOnce();
                view = next;
                commitExecutable(next, recorder.intents, executable);
                committedGeneration = next.generation;
                postCommitDirty.addAll(dirty);
                contextDisposals.addAll(executable.contextDisposals);
            } catch (Reject rejection) {
                return MutationResult.rejected(List.of(rejection.diagnostic()));
            }
        }

        List<CompletableFuture<?>> componentSettlements =
                new ArrayList<>(schedule(postCommitDirty));
        CompletableFuture<Void> componentSettlement = componentSettlements.isEmpty()
                ? CompletableFuture.completedFuture(null)
                : CompletableFuture.allOf(componentSettlements.toArray(
                        new CompletableFuture[0]));
        List<CompletableFuture<?>> settlements = new ArrayList<>(componentSettlements);
        for (String contextId : outermostContextDisposals(contextDisposals)) {
            settlements.add(settleContextDisposal(contextId, componentSettlement));
        }
        CompletableFuture<Void> settlement = settlements.isEmpty()
                ? CompletableFuture.completedFuture(null)
                : CompletableFuture.allOf(settlements.toArray(new CompletableFuture[0]));
        return MutationResult.committed(
                callbackValue,
                committedGeneration,
                settlement);
    }

    @Override
    public CompletionStage<Void> closeAsync() {
        CompletableFuture<Void> created = closeFuture.updateAndGet(existing ->
                existing != null && !existing.isCompletedExceptionally()
                        ? existing
                        : new CompletableFuture<>());
        closing.set(true);
        ContextHandleImpl root = contextHandles.get("ctx-root");
        disposeContextInView(root, true).whenComplete((ignored, error) -> {
            if (error == null) {
                executor.shutdown();
                created.complete(null);
            } else {
                created.completeExceptionally(error);
            }
        });
        return created;
    }

    <T> Optional<T> findInContext(String contextId, CapabilityKey<T> key) {
        Objects.requireNonNull(key, "key");
        RuntimeView current = view;
        RuntimeView.RegistrationData registration =
                current.resolve(contextId, key).orElse(null);
        if (registration == null) {
            return Optional.empty();
        }
        if (!key.type().isInstance(registration.value())) {
            throw new IllegalStateException(
                    "capability registration type mismatch: " + key.name());
        }
        return Optional.of(key.type().cast(registration.value()));
    }

    <T> T requireInContext(String contextId, CapabilityKey<T> key) {
        return findInContext(contextId, key).orElseThrow(() ->
                new IllegalStateException("capability is not available: " + key.name()));
    }

    ContextInfo contextInfo(String contextId) {
        RuntimeView current = view;
        RuntimeView.ContextData data = current.contexts.get(contextId);
        if (data == null) {
            return new ContextInfo(
                    contextId,
                    null,
                    "unknown",
                    ContextState.DISPOSED,
                    "/" + contextId);
        }
        return new ContextInfo(
                data.contextId(),
                data.parentId(),
                data.name(),
                data.state(),
                current.canonicalPath(contextId));
    }

    ContextState contextState(String contextId) {
        RuntimeView.ContextData data = view.contexts.get(contextId);
        return data == null ? ContextState.DISPOSED : data.state();
    }

    String componentMountId(String handleId) {
        RuntimeView.ComponentData data = view.components.get(handleId);
        return data == null ? "" : data.mountId();
    }

    String componentField(
            String handleId,
            Function<RuntimeView.ComponentData, String> field) {
        RuntimeView.ComponentData data = view.components.get(handleId);
        return data == null ? "" : field.apply(data);
    }

    ComponentState componentState(String handleId) {
        RuntimeView.ComponentData data = view.components.get(handleId);
        if (data != null) {
            return data.state();
        }
        ComponentState terminal = terminalComponents.get(handleId);
        return terminal == null ? ComponentState.DISPOSED : terminal;
    }

    ComponentGoal componentGoal(String handleId) {
        RuntimeView.ComponentData data = view.components.get(handleId);
        return data == null ? ComponentGoal.DISPOSED : data.goal();
    }

    long componentConfigRevision(String handleId) {
        RuntimeView.ComponentData data = view.components.get(handleId);
        return data == null ? 0 : data.configRevision();
    }

    CompletionStage<ComponentState> whenSettled(String handleId) {
        ComponentRuntime runtime = components.get(handleId);
        if (runtime == null) {
            return CompletableFuture.completedFuture(componentState(handleId));
        }
        return runtime.enqueue(this, executor).thenCompose(state ->
                state == ComponentState.STOPPING
                        ? whenSettled(handleId)
                        : CompletableFuture.completedFuture(state));
    }

    <C> CompletionStage<ComponentState> reconfigure(
            ComponentHandleImpl<C> handle,
            C config) {
        MutationResult<Void> result = mutate(mutation -> {
            mutation.reconfigure(handle, config);
            return null;
        });
        if (!result.committed()) {
            CompletableFuture<ComponentState> rejected = new CompletableFuture<>();
            rejected.completeExceptionally(new IllegalStateException(
                    result.diagnostics().getFirst().message()));
            return rejected;
        }
        return whenSettled(handle.handleId());
    }

    <C> CompletionStage<ComponentState> retry(ComponentHandleImpl<C> handle) {
        ComponentRuntime component = components.get(handle.handleId());
        if (component == null || componentHandles.get(handle.handleId()) != handle) {
            return failedFuture("handle does not belong to this runtime");
        }
        synchronized (coordinator) {
            RuntimeView.ComponentData data = view.components.get(handle.handleId());
            if (data == null || data.state() != ComponentState.FAILED) {
                return failedFuture("retry is only valid for a failed component");
            }
        }
        return component.enqueue(this, executor);
    }

    <C> CompletionStage<ComponentState> dispose(ComponentHandleImpl<C> handle) {
        if (handle.runtime == this
                && componentState(handle.handleId()) == ComponentState.DISPOSED
                && componentGoal(handle.handleId()) == ComponentGoal.DISPOSED) {
            return CompletableFuture.completedFuture(ComponentState.DISPOSED);
        }
        synchronized (disposeRequests) {
            CompletableFuture<ComponentState> existing =
                    disposeRequests.get(handle.handleId());
            if (existing != null && !existing.isCompletedExceptionally()) {
                return existing;
            }
            CompletableFuture<ComponentState> request = new CompletableFuture<>();
            disposeRequests.put(handle.handleId(), request);
            MutationResult<Void> result = mutate(mutation -> {
                mutation.dispose(handle);
                return null;
            });
            if (!result.committed()) {
                disposeRequests.remove(handle.handleId(), request);
                request.completeExceptionally(new IllegalStateException(
                        result.diagnostics().getFirst().message()));
                return request;
            }
            settleDisposeRequest(handle.handleId(), request);
            return request;
        }
    }

    private void settleDisposeRequest(
            String handleId,
            CompletableFuture<ComponentState> request) {
        whenSettled(handleId).whenComplete((state, error) -> {
            if (state == ComponentState.STOPPING) {
                executor.execute(() -> settleDisposeRequest(handleId, request));
                return;
            }
            if (error != null || state == ComponentState.DISPOSED) {
                disposeRequests.remove(handleId, request);
            }
            if (error != null) {
                request.completeExceptionally(error);
            } else {
                request.complete(state);
            }
        });
    }

    CompletionStage<Void> disposeContext(ContextHandleImpl handle) {
        if (!contextHandles.containsValue(handle)) {
            CompletableFuture<Void> rejected = new CompletableFuture<>();
            rejected.completeExceptionally(new IllegalStateException(
                    "context handle does not belong to this runtime"));
            return rejected;
        }
        return disposeContextInView(handle, false);
    }

    Object registrationValue(String registrationId) {
        RuntimeView.RegistrationData committed =
                view.registrations.get(registrationId);
        if (committed != null) {
            return committed.value();
        }
        for (ActivationRuntime activation : activations.values()) {
            RuntimeView.RegistrationData staged =
                    activation.stagedRegistrations.get(registrationId);
            if (staged != null) {
                return staged.value();
            }
        }
        return null;
    }

    void validateCapabilityType(CapabilityKey<?> key) {
        RuntimeView current = view;
        Class<?> existing = current.capabilityTypes.get(key.name());
        if (existing != null && existing != key.type()) {
            throw new IllegalArgumentException(
                    "capability name already has type " + existing.getName());
        }
    }

    boolean mountIdReserved(String contextId, String mountId) {
        RuntimeView current = view;
        return current.components.values().stream().anyMatch(component ->
                component.contextId().equals(contextId)
                        && component.mountId().equals(mountId));
    }

    <C> ComponentHandleImpl<C> createProvisionalHandle() {
        return new ComponentHandleImpl<>(this, Sequences.handle());
    }

    private boolean applyIntent(
            RuntimeView.Draft draft,
            Intent intent,
            Set<String> dirty,
            ExecutableCommitPlan executable) {
        return switch (intent) {
            case ProvideIntent provide -> applyProvide(draft, provide);
            case RevokeIntent revoke -> applyRevoke(draft, revoke, dirty, executable);
            case ChildContextIntent child -> applyChildContext(draft, child);
            case MountIntent<?> mount -> applyMount(draft, mount, dirty, executable);
            case ReconfigureIntent<?> reconfigure ->
                    applyReconfigure(draft, reconfigure, dirty, executable);
            case DisposeIntent dispose ->
                    applyDispose(draft, dispose, dirty, executable);
            case ContextDisposeIntent dispose ->
                    applyContextDispose(draft, dispose, dirty, executable);
        };
    }

    private boolean applyProvide(RuntimeView.Draft draft, ProvideIntent intent) {
        ContextHandleImpl context = requireContext(draft, intent.context());
        ensureActiveContext(draft, context.contextId());
        CapabilityKey<?> key = intent.key();
        if (!key.type().isInstance(intent.value())) {
            throw reject(
                    DiagnosticCode.CAPABILITY_TYPE_CONFLICT,
                    key.name(),
                    "value is not an instance of " + key.typeName());
        }
        validateDraftCapabilityType(draft, key);
        boolean occupied = draft.registrations.values().stream().anyMatch(registration ->
                registration.contextId().equals(context.contextId())
                        && registration.key().name().equals(key.name()));
        if (occupied) {
            throw reject(
                    DiagnosticCode.CAPABILITY_SLOT_OCCUPIED,
                    key.name(),
                    "context capability slot is already occupied");
        }
        draft.registrations.put(
                intent.handle().registrationId(),
                new RuntimeView.RegistrationData(
                        intent.handle().registrationId(),
                        key,
                        context.contextId(),
                        RuntimeView.OwnerData.Host.INSTANCE,
                        intent.value()));
        draft.capabilityTypes.putIfAbsent(key.name(), key.type());
        return true;
    }

    private boolean applyRevoke(
            RuntimeView.Draft draft,
            RevokeIntent intent,
            Set<String> dirty,
            ExecutableCommitPlan executable) {
        RegistrationHandleImpl handle = requireRegistration(draft, intent.handle());
        RuntimeView.RegistrationData registration =
                draft.registrations.get(handle.registrationId());
        if (registration == null) {
            return false;
        }
        if (!(registration.owner() instanceof RuntimeView.OwnerData.Host)) {
            throw reject(
                    DiagnosticCode.INVALID_LIFECYCLE_OPERATION,
                    handle.registrationId(),
                    "component registration must be revoked through its component handle");
        }
        draft.registrations.remove(handle.registrationId());
        Set<String> direct = componentsWithBinding(
                draft,
                Set.of(handle.registrationId()));
        Set<String> impacted = new LinkedHashSet<>();
        for (String handleId : direct) {
            RuntimeView.ComponentData component = draft.components.get(handleId);
            if (component == null || component.currentActivationId() == null) {
                impacted.add(handleId);
                continue;
            }
            impacted.addAll(disposeOwnershipForActivation(
                    draft,
                    handleId,
                    component.currentActivationId(),
                    executable));
        }
        detachInView(draft, impacted, dirty, executable);
        return true;
    }

    private boolean applyChildContext(
            RuntimeView.Draft draft,
            ChildContextIntent intent) {
        ContextHandleImpl parent = requireContext(draft, intent.parent());
        ensureActiveContext(draft, parent.contextId());
        if (intent.name() == null || intent.name().isBlank()
                || intent.name().equals(".") || intent.name().equals("..")
                || intent.name().chars().anyMatch(character -> character == '/'
                        || character == '\\'
                        || Character.isISOControl(character))) {
            throw reject(
                    DiagnosticCode.INVALID_LIFECYCLE_OPERATION,
                    parent.contextId(),
                    "context name must be a non-empty path segment");
        }
        String id = intent.handle().contextId();
        String path = draft.canonicalPath(parent.contextId()) + "/" + intent.name();
        boolean duplicateName = draft.contexts.values().stream().anyMatch(candidate ->
                parent.contextId().equals(candidate.parentId())
                        && intent.name().equals(candidate.name()));
        boolean pathCollision = draft.contexts.values().stream().anyMatch(candidate ->
                path.equals(draft.canonicalPath(candidate.contextId()))
                        || path.equals(candidate.canonicalPath()));
        if (duplicateName || pathCollision) {
            throw reject(
                    DiagnosticCode.INVALID_LIFECYCLE_OPERATION,
                    path,
                    "context canonical path is already in use");
        }
        draft.contexts.put(id, new RuntimeView.ContextData(
                id,
                parent.contextId(),
                intent.name(),
                ContextState.ACTIVE,
                path));
        return true;
    }

    private boolean applyMount(
            RuntimeView.Draft draft,
            MountIntent<?> intent,
            Set<String> dirty,
            ExecutableCommitPlan executable) {
        ContextHandleImpl context = requireContext(draft, intent.context());
        ensureActiveContext(draft, context.contextId());
        if (intent.mountId() == null || intent.mountId().isBlank()) {
            throw reject(
                    DiagnosticCode.INVALID_MOUNT_ID,
                    context.contextId(),
                    "mountId must not be blank");
        }
        boolean occupied = draft.components.values().stream().anyMatch(component ->
                component.contextId().equals(context.contextId())
                        && component.mountId().equals(intent.mountId()));
        if (occupied) {
            throw reject(
                    DiagnosticCode.INVALID_MOUNT_ID,
                    context.contextId() + "/" + intent.mountId(),
                    "mountId is already in use in this context");
        }
        for (CapabilityRequirement requirement
                : intent.prepared().descriptor().sortedRequirements()) {
            validateDraftCapabilityType(draft, requirement.key());
            draft.capabilityTypes.putIfAbsent(
                    requirement.key().name(),
                    requirement.key().type());
        }
        String handleId = intent.handle().handleId();
        draft.components.put(handleId, new RuntimeView.ComponentData(
                handleId,
                context.contextId(),
                intent.mountId(),
                intent.prepared().descriptor().componentId(),
                intent.prepared().factoryId(),
                intent.prepared().options().origin(),
                null,
                null,
                ComponentState.WAITING,
                ComponentGoal.RUNNING,
                1,
                null,
                null,
                intent.prepared().descriptor(),
                intent.prepared().options()));
        executable.mounts.put(handleId, intent);
        dirty.add(handleId);
        return true;
    }

    private boolean applyReconfigure(
            RuntimeView.Draft draft,
            ReconfigureIntent<?> intent,
            Set<String> dirty,
            ExecutableCommitPlan executable) {
        ComponentHandleImpl<?> handle = requireComponent(draft, intent.handle());
        RuntimeView.ComponentData data = draft.components.get(handle.handleId());
        if (data == null || data.goal() == ComponentGoal.DISPOSED) {
            throw reject(
                    DiagnosticCode.INVALID_LIFECYCLE_OPERATION,
                    handle.handleId(),
                    "disposed component cannot be reconfigured");
        }
        ensureActiveContext(draft, data.contextId());
        if (data.configRevision() != intent.expectedRevision()) {
            throw reject(
                    DiagnosticCode.INVALID_LIFECYCLE_OPERATION,
                    handle.handleId(),
                    "component configuration changed before transaction commit");
        }
        if (intent.equivalent()) {
            return false;
        }
        long nextRevision = data.configRevision() + 1;
        draft.components.put(
                handle.handleId(),
                data.withConfigRevision(nextRevision));
        executable.configs.put(
                handle.handleId(),
                new ExecutableCommitPlan.ConfigUpdate(
                        intent.config(),
                        nextRevision));
        if (data.currentActivationId() != null) {
            executable.staleActivations.add(data.currentActivationId());
            Set<String> impacted = disposeOwnershipForActivation(
                    draft,
                    handle.handleId(),
                    data.currentActivationId(),
                    executable);
            detachInView(draft, impacted, dirty, executable);
        }
        dirty.add(handle.handleId());
        return true;
    }

    private boolean applyDispose(
            RuntimeView.Draft draft,
            DisposeIntent intent,
            Set<String> dirty,
            ExecutableCommitPlan executable) {
        ComponentHandleImpl<?> handle = requireComponent(draft, intent.handle());
        RuntimeView.ComponentData parent = draft.components.get(handle.handleId());
        if (parent != null) {
            draft.components.put(
                    handle.handleId(),
                    parent.withGoal(ComponentGoal.DISPOSED));
        }
        Set<String> live = disposeOwnershipForActivation(
                draft,
                handle.handleId(),
                parent == null ? null : parent.currentActivationId(),
                executable);
        Set<String> impacted = draft.dependentsClosure(live);
        detachInView(draft, impacted, dirty, executable);
        RuntimeView.ComponentData latest = draft.components.get(handle.handleId());
        if (latest != null && latest.currentActivationId() == null) {
            removeComponentInView(draft, handle.handleId());
            executable.removedComponents.add(handle.handleId());
            return true;
        }
        dirty.addAll(live);
        return true;
    }

    private boolean applyContextDispose(
            RuntimeView.Draft draft,
            ContextDisposeIntent intent,
            Set<String> dirty,
            ExecutableCommitPlan executable) {
        ContextHandleImpl handle = requireContext(draft, intent.handle());
        if (handle.contextId().equals("ctx-root")) {
            throw reject(
                    DiagnosticCode.INVALID_LIFECYCLE_OPERATION,
                    handle.contextId(),
                    "root context must be disposed through runtime close");
        }
        executable.contextDisposals.add(handle.contextId());
        Set<String> subtree = draft.contextSubtree(handle.contextId());
        for (String contextId : subtree) {
            RuntimeView.ContextData data = draft.contexts.get(contextId);
            draft.contexts.put(contextId, data.withState(ContextState.DISPOSING));
        }
        draft.registrations.values().removeIf(registration ->
                subtree.contains(registration.contextId())
                        && registration.owner() instanceof RuntimeView.OwnerData.Host);

        Set<String> handles = draft.components.values().stream()
                .filter(component -> subtree.contains(component.contextId()))
                .flatMap(component ->
                        draft.ownershipDescendants(component.handleId()).stream())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        for (String handleId : handles) {
            RuntimeView.ComponentData component = draft.components.get(handleId);
            if (component == null) {
                continue;
            }
            draft.components.put(
                    handleId,
                    component.withGoal(ComponentGoal.DISPOSED));
        }
        Set<String> live = handles.stream()
                .filter(handleId -> draft.components.get(handleId) != null
                        && draft.components.get(handleId).currentActivationId() != null)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> closure = draft.dependentsClosure(live);
        detachInView(draft, closure, dirty, executable);
        for (String handleId : handles) {
            RuntimeView.ComponentData component = draft.components.get(handleId);
            if (component != null && component.currentActivationId() == null) {
                removeComponentInView(draft, handleId);
                executable.removedComponents.add(handleId);
            } else if (component != null) {
                dirty.add(handleId);
            }
        }
        return true;
    }

    private void markBindingImpacts(
            RuntimeView.Draft draft,
            Set<String> dirty,
            ExecutableCommitPlan executable) {
        Set<String> impacted = new LinkedHashSet<>();
        for (RuntimeView.ComponentData component : draft.components.values()) {
            if (component.currentActivationId() == null) {
                continue;
            }
            RuntimeView.ActivationData activation =
                    draft.activations.get(component.currentActivationId());
            if (activation == null
                    || !RuntimeView.activationTracksGraph(activation.state())) {
                continue;
            }
            Map<String, RuntimeView.BindingData> effective =
                    draft.effectiveBindings(component, Map.of());
            for (CapabilityRequirement requirement
                    : component.descriptor().sortedRequirements()) {
                RuntimeView.BindingData old =
                        activation.bindings().get(requirement.key().name());
                RuntimeView.BindingData next =
                        effective.get(requirement.key().name());
                if (!bindingIdentityEqual(old, next)) {
                    impacted.add(component.handleId());
                    executable.staleActivations.add(activation.activationId());
                    break;
                }
            }
        }
        if (!impacted.isEmpty()) {
            Set<String> detachTargets = new LinkedHashSet<>();
            for (String handleId : impacted) {
                RuntimeView.ComponentData component =
                        draft.components.get(handleId);
                if (component == null || component.currentActivationId() == null) {
                    detachTargets.add(handleId);
                    continue;
                }
                detachTargets.addAll(disposeOwnershipForActivation(
                        draft,
                        handleId,
                        component.currentActivationId(),
                        executable));
            }
            Set<String> closure = draft.dependentsClosure(detachTargets);
            detachInView(draft, closure, dirty, executable);
        }

        RuntimeView old = view;
        for (RuntimeView.ComponentData component : draft.components.values()) {
            if (component.state() == ComponentState.WAITING
                    && component.goal() == ComponentGoal.RUNNING) {
                RuntimeView.ComponentData previous = old.components.get(
                        component.handleId());
                boolean topologyChanged = previous == null;
                if (!topologyChanged) {
                    Map<String, RuntimeView.BindingData> before =
                            old.effectiveBindings(previous, Map.of());
                    Map<String, RuntimeView.BindingData> after =
                            draft.effectiveBindings(component, Map.of());
                    topologyChanged = !bindingsEqual(before, after);
                }
                if (topologyChanged) {
                    executable.resetAutoRestart.add(component.handleId());
                }
                dirty.add(component.handleId());
            }
        }
    }

    private boolean bindingsEqual(
            Map<String, RuntimeView.BindingData> left,
            Map<String, RuntimeView.BindingData> right) {
        if (!left.keySet().equals(right.keySet())) {
            return false;
        }
        for (String name : left.keySet()) {
            if (!bindingIdentityEqual(left.get(name), right.get(name))) {
                return false;
            }
        }
        return true;
    }

    private boolean bindingIdentityEqual(
            RuntimeView.BindingData left,
            RuntimeView.BindingData right) {
        if (left == null || right == null) {
            return left == right;
        }
        return left.present() == right.present()
                && Objects.equals(left.registrationId(), right.registrationId());
    }

    private Set<String> componentsWithBinding(
            RuntimeView.Draft draft,
            Set<String> registrationIds) {
        Set<String> result = new LinkedHashSet<>();
        for (RuntimeView.ComponentData component : draft.components.values()) {
            if (component.currentActivationId() == null) {
                continue;
            }
            RuntimeView.ActivationData activation =
                    draft.activations.get(component.currentActivationId());
            if (activation == null
                    || !RuntimeView.activationTracksGraph(activation.state())) {
                continue;
            }
            boolean bound = activation.bindings().values().stream()
                    .filter(RuntimeView.BindingData::present)
                    .map(RuntimeView.BindingData::registrationId)
                    .anyMatch(registrationIds::contains);
            if (bound) {
                result.add(component.handleId());
            }
        }
        return result;
    }
    private Set<String> disposeOwnershipForActivation(
            RuntimeView.Draft draft,
            String parentHandleId,
            String ownerActivationId,
            ExecutableCommitPlan executable) {
        Set<String> ownership = draft.ownershipDescendantsForActivation(
                parentHandleId,
                ownerActivationId);
        Set<String> live = new LinkedHashSet<>();
        for (String handleId : ownership) {
            RuntimeView.ComponentData component = draft.components.get(handleId);
            if (component == null) {
                continue;
            }
            if (handleId.equals(parentHandleId)) {
                live.add(handleId);
                continue;
            }
            draft.components.put(handleId, component.withGoal(ComponentGoal.DISPOSED));
            if (component.currentActivationId() == null) {
                removeComponentInView(draft, handleId);
                executable.removedComponents.add(handleId);
            } else {
                live.add(handleId);
            }
        }
        return live;
    }

    private void detachInView(
            RuntimeView.Draft draft,
            Set<String> handles,
            Set<String> dirty,
            ExecutableCommitPlan executable) {
        Set<String> closure = draft.dependentsClosure(handles);
        List<String> ownedRegistrations = draft.registrationsOwnedBy(closure);
        for (String registrationId : ownedRegistrations) {
            draft.registrations.remove(registrationId);
        }
        for (String handleId : closure) {
            RuntimeView.ComponentData component = draft.components.get(handleId);
            if (component == null) {
                continue;
            }
            if (component.currentActivationId() != null) {
                RuntimeView.ActivationData activation = draft.activations.get(
                        component.currentActivationId());
                if (activation != null) {
                    draft.activations.put(
                            component.currentActivationId(),
                            activation.detached());
                }
                executable.staleActivations.add(component.currentActivationId());
                draft.components.put(
                        handleId,
                        component.withState(ComponentState.STOPPING));
            }
            dirty.add(handleId);
        }
    }

    private void removeComponentInView(RuntimeView.Draft draft, String handleId) {
        RuntimeView.ComponentData data = draft.components.remove(handleId);
        if (data != null && data.currentActivationId() != null) {
            draft.activations.remove(data.currentActivationId());
        }
    }

    private void validateDraftCapabilityType(
            RuntimeView.Draft draft,
            CapabilityKey<?> key) {
        Class<?> existing = draft.capabilityTypes.get(key.name());
        if (existing != null && existing != key.type()) {
            throw reject(
                    DiagnosticCode.CAPABILITY_TYPE_CONFLICT,
                    key.name(),
                    "capability name already has Java type " + existing.getName());
        }
    }

    private ContextHandleImpl requireContext(
            RuntimeView.Draft draft,
            ContextHandle candidate) {
        if (!(candidate instanceof ContextHandleImpl handle)
                || handle.runtime != this
                || !draft.contexts.containsKey(handle.contextId())) {
            throw reject(
                    DiagnosticCode.INVALID_LIFECYCLE_OPERATION,
                    candidate == null ? "unknown" : candidate.contextId(),
                    "context handle does not belong to an active transaction entity");
        }
        return handle;
    }

    private void ensureActiveContext(RuntimeView.Draft draft, String contextId) {
        RuntimeView.ContextData data = draft.contexts.get(contextId);
        if (data == null || data.state() != ContextState.ACTIVE) {
            throw reject(
                    DiagnosticCode.INVALID_LIFECYCLE_OPERATION,
                    contextId,
                    "context is not active");
        }
    }

    private RegistrationHandleImpl requireRegistration(
            RuntimeView.Draft draft,
            RegistrationHandle candidate) {
        if (!(candidate instanceof RegistrationHandleImpl handle)
                || handle.runtime != this
                || !draft.registrations.containsKey(handle.registrationId())) {
            throw reject(
                    DiagnosticCode.INVALID_LIFECYCLE_OPERATION,
                    candidate == null ? "unknown" : candidate.registrationId(),
                    "registration handle does not belong to a live registration");
        }
        return handle;
    }

    private ComponentHandleImpl<?> requireComponent(
            RuntimeView.Draft draft,
            ComponentHandle<?> candidate) {
        if (!(candidate instanceof ComponentHandleImpl<?> handle)
                || handle.runtime != this
                || !draft.components.containsKey(handle.handleId())) {
            throw reject(
                    DiagnosticCode.INVALID_LIFECYCLE_OPERATION,
                    candidate == null ? "unknown" : candidate.handleId(),
                    "component handle does not belong to a live component");
        }
        return handle;
    }

    private void commitExecutable(
            RuntimeView next,
            List<Intent> intents,
            ExecutableCommitPlan executable) {
        for (String activationId : executable.staleActivations) {
            ActivationRuntime activation = activations.get(activationId);
            if (activation != null) {
                activation.markStale();
            }
        }
        for (String handleId : executable.resetAutoRestart) {
            ComponentRuntime runtime = components.get(handleId);
            if (runtime != null) {
                runtime.suppressAutoRestart = false;
                runtime.blockedNonConvergent = false;
                runtime.reconcileAttempts = 0;
            }
        }
        for (String handleId : executable.removedComponents) {
            components.remove(handleId);
            componentHandles.remove(handleId);
            terminalComponents.put(handleId, ComponentState.DISPOSED);
        }

        for (MountIntent<?> mount : executable.mounts.values()) {
            String handleId = mount.handle().handleId();
            if (!next.components.containsKey(handleId)) {
                terminalComponents.put(handleId, ComponentState.DISPOSED);
                continue;
            }
            ComponentRuntime runtime = new ComponentRuntime(
                    handleId,
                    mount.context().contextId(),
                    mount.mountId(),
                    mount.prepared());
            components.put(handleId, runtime);
            componentHandles.put(handleId, mount.handle());
        }
        for (Map.Entry<String, ExecutableCommitPlan.ConfigUpdate> entry
                : executable.configs.entrySet()) {
            ComponentRuntime runtime = components.get(entry.getKey());
            if (runtime != null) {
                runtime.updateConfig(
                        entry.getValue().config(),
                        entry.getValue().revision());
            }
        }

        for (Intent intent : intents) {
            switch (intent) {
                case ProvideIntent provide -> {
                    if (next.registrations.containsKey(
                            provide.handle().registrationId())) {
                        registrationHandles.put(
                                provide.handle().registrationId(),
                                provide.handle());
                    }
                }
                case RevokeIntent revoke ->
                        registrationHandles.remove(revoke.handle().registrationId());
                case ChildContextIntent child -> {
                    if (next.contexts.containsKey(child.handle().contextId())) {
                        contextHandles.put(child.handle().contextId(), child.handle());
                    }
                }
                case ContextDisposeIntent dispose -> {
                    if (!next.contexts.containsKey(dispose.handle().contextId())) {
                        contextHandles.remove(dispose.handle().contextId());
                    }
                }
                default -> {
                }
            }
        }
        registrationHandles.keySet().retainAll(next.registrations.keySet());
    }

    private List<CompletableFuture<ComponentState>> schedule(Set<String> dirty) {
        Set<String> stopping = new LinkedHashSet<>();
        Set<String> starting = new LinkedHashSet<>();
        List<ComponentRuntime.Reservation> reservations = new ArrayList<>();
        synchronized (coordinator) {
            for (String handleId : dirty) {
                RuntimeView.ComponentData data = view.components.get(handleId);
                if (data == null) {
                    continue;
                }
                if (data.state() == ComponentState.STOPPING) {
                    stopping.add(handleId);
                } else if ((data.state() == ComponentState.WAITING
                        || data.state() == ComponentState.FAILED)
                        && data.goal() == ComponentGoal.RUNNING) {
                    starting.add(handleId);
                }
            }
            for (String handleId : orderForStop(view, stopping)) {
                reserve(handleId, reservations);
            }
            for (String handleId : starting.stream().sorted().toList()) {
                reserve(handleId, reservations);
            }
        }
        for (ComponentRuntime.Reservation reservation : reservations) {
            if (reservation.created()) {
                reservation.component().executeReserved(
                        this,
                        executor,
                        reservation.future());
            }
        }
        return reservations.stream()
                .map(ComponentRuntime.Reservation::future)
                .collect(Collectors.toList());
    }

    private void reserve(
            String handleId,
            List<ComponentRuntime.Reservation> reservations) {
        ComponentRuntime runtime = components.get(handleId);
        if (runtime != null) {
            reservations.add(runtime.reserveTransition());
        }
    }

    private List<String> orderForStop(RuntimeView current, Set<String> handles) {
        if (handles.isEmpty()) {
            return List.of();
        }
        Map<String, Set<String>> providers = stopProviders(current, handles);
        Map<String, Integer> degree = new TreeMap<>();
        Map<String, Set<String>> dependents = new TreeMap<>();
        for (String handleId : handles) {
            Set<String> targets = providers.getOrDefault(handleId, Set.of());
            degree.put(handleId, targets.size());
            for (String provider : targets) {
                dependents.computeIfAbsent(provider, ignored -> new LinkedHashSet<>())
                        .add(handleId);
            }
        }
        List<String> ready = degree.entrySet().stream()
                .filter(entry -> entry.getValue() == 0)
                .map(Map.Entry::getKey)
                .sorted()
                .collect(Collectors.toCollection(ArrayList::new));
        List<String> ordered = new ArrayList<>();
        while (!ready.isEmpty()) {
            String currentHandle = ready.removeFirst();
            ordered.add(currentHandle);
            for (String dependent : dependents.getOrDefault(
                    currentHandle, Set.of())) {
                int next = degree.merge(dependent, -1, Integer::sum);
                if (next == 0) {
                    ready.add(dependent);
                }
            }
        }
        handles.stream()
                .filter(handleId -> !ordered.contains(handleId))
                .sorted()
                .forEach(ordered::add);
        java.util.Collections.reverse(ordered);
        return ordered;
    }

    private Map<String, Set<String>> stopProviders(
            RuntimeView current,
            Set<String> handles) {
        Map<String, String> activationOwners = new HashMap<>();
        current.activations.values().forEach(activation ->
                activationOwners.put(activation.activationId(), activation.handleId()));
        Map<String, Set<String>> result = new TreeMap<>();
        for (String handleId : handles) {
            RuntimeView.ComponentData component = current.components.get(handleId);
            if (component == null || component.currentActivationId() == null) {
                continue;
            }
            ActivationRuntime activation = activations.get(
                    component.currentActivationId());
            if (activation == null) {
                continue;
            }
            Set<String> providerHandles = new LinkedHashSet<>();
            for (RuntimeView.BindingData binding : activation.bindings.values()) {
                if (!binding.present()) {
                    continue;
                }
                String providerHandle = providerHandleForRegistration(
                        current,
                        activationOwners,
                        binding.registrationId());
                if (providerHandle != null
                        && handles.contains(providerHandle)
                        && !providerHandle.equals(handleId)) {
                    providerHandles.add(providerHandle);
                }
            }
            result.put(handleId, providerHandles);
        }
        return result;
    }

    private String providerHandleForRegistration(
            RuntimeView current,
            Map<String, String> activationOwners,
            String registrationId) {
        RuntimeView.RegistrationData registration =
                current.registrations.get(registrationId);
        String ownerActivationId = null;
        if (registration != null
                && registration.owner() instanceof RuntimeView.OwnerData.Activation owner) {
            ownerActivationId = owner.activationId();
        } else {
            for (ActivationRuntime activation : activations.values()) {
                boolean owns = activation.stagedRegistrations.values().stream()
                        .map(RuntimeView.RegistrationData::registrationId)
                        .anyMatch(registrationId::equals);
                if (owns) {
                    ownerActivationId = activation.activationId;
                    break;
                }
            }
        }
        return ownerActivationId == null ? null : activationOwners.get(ownerActivationId);
    }

    void driveTransition(String handleId, CompletableFuture<ComponentState> future) {
        ComponentRuntime component = components.get(handleId);
        if (component == null) {
            future.complete(ComponentState.DISPOSED);
            return;
        }
        ActivationRuntime activation;
        ComponentState immediateState = null;
        synchronized (coordinator) {
            RuntimeView.ComponentData data = view.components.get(handleId);
            if (data == null) {
                component.finishTransition(future, ComponentState.DISPOSED);
                return;
            }
            if (data.state() == ComponentState.STOPPING
                    || (data.state() == ComponentState.FAILED
                            && (component.current != null
                                    || component.failedCleanup != null))) {
                activation = component.current;
                if (activation == null && component.failedCleanup != null) {
                    activation = component.failedCleanup;
                }
                if (activation == null) {
                    finalizeOrphanedStoppingLocked(component, data, future);
                    return;
                }
            } else if ((data.state() == ComponentState.WAITING
                    || data.state() == ComponentState.FAILED)
                    && data.goal() == ComponentGoal.RUNNING
                    && !component.suppressAutoRestart
                    && requirementsResolvable(view, data)) {
                activation = beginActivationLocked(component);
            } else {
                immediateState = data.state();
                component.finishTransition(future, immediateState);
                return;
            }
        }

        if (activation == null) {
            ComponentState state = immediateState == null
                    ? componentState(handleId)
                    : immediateState;
            component.finishTransition(future, state);
            return;
        }
        if (activation.scope.state() != LifecycleState.OPEN
                || view.components.get(handleId) == null
                || view.components.get(handleId).currentActivationId() == null
                || view.activations.get(activation.activationId) == null
                || view.activations.get(activation.activationId).state()
                        == ActivationState.STOPPING) {
            finishCleanupAfterDependents(component, activation, future);
            return;
        }
        runActivation(component, activation, future);
    }

    private void finalizeOrphanedStoppingLocked(
            ComponentRuntime component,
            RuntimeView.ComponentData data,
            CompletableFuture<ComponentState> future) {
        RuntimeView.Draft draft = new RuntimeView.Draft(view);
        ComponentState state;
        if (data.goal() == ComponentGoal.DISPOSED) {
            removeComponentInView(draft, component.handleId);
            components.remove(component.handleId);
            componentHandles.remove(component.handleId);
            terminalComponents.put(component.handleId, ComponentState.DISPOSED);
            state = ComponentState.DISPOSED;
        } else {
            draft.components.put(
                    component.handleId,
                    data.withState(ComponentState.WAITING).clearActivation());
            state = ComponentState.WAITING;
        }
        refreshDiagnostics(draft);
        view = draft.publishOnce();
        component.finishTransition(future, state);
    }

    private boolean requirementsResolvable(
            RuntimeView view,
            RuntimeView.ComponentData data) {
        return data.descriptor().sortedRequirements().stream()
                .filter(requirement ->
                        requirement.mode() == CapabilityRequirement.Mode.REQUIRED)
                .allMatch(requirement ->
                        view.resolve(data.contextId(), requirement.key()).isPresent());
    }

    private ActivationRuntime beginActivationLocked(ComponentRuntime component) {
        RuntimeView.Draft draft = new RuntimeView.Draft(view);
        RuntimeView.ComponentData data = draft.components.get(component.handleId);
        component.reconcileFingerprint = reconcileFingerprint(draft, data);
        Map<String, RuntimeView.BindingData> bindings =
                draft.effectiveBindings(data, Map.of());
        String activationId = Sequences.activation();
        ActivationRuntime activation = new ActivationRuntime(
                activationId,
                component,
                component.desiredConfig,
                component.desiredRevision,
                bindings,
                List.of());
        for (CapabilityRequirement requirement
                : data.descriptor().sortedRequirements()) {
            RuntimeView.BindingData binding = bindings.get(requirement.key().name());
            if (binding != null && binding.present()) {
                RuntimeView.RegistrationData registration = draft.resolve(
                        data.contextId(),
                        requirement.key(),
                        Map.of()).orElseThrow();
                activation.capturedValues.put(
                        requirement.key().name(),
                        registration.value());
            }
        }
        draft.activations.put(activationId, new RuntimeView.ActivationData(
                activationId,
                component.handleId,
                ActivationState.STARTING,
                component.desiredRevision,
                bindings,
                data.descriptor(),
                activation.scope.scopeId()));
        draft.components.put(
                component.handleId,
                data.withState(ComponentState.STARTING).withActivation(activationId));
        refreshDiagnostics(draft);
        RuntimeView next = draft.publishOnce();
        view = next;
        activations.put(activationId, activation);
        component.current = activation;
        component.pendingStartFailure = false;
        component.blockedNonConvergent = false;
        component.lastStartError = "";
        return activation;
    }

    private void runActivation(
            ComponentRuntime runtime,
            ActivationRuntime activation,
            CompletableFuture<ComponentState> future) {
        List<ChildMountPlan<?>> plans = new ArrayList<>();
        ActivationContext context = new ActivationContextImpl(
                this,
                activation,
                plans);
        Throwable startError = null;
        try {
            runtime.prepared.start(context, activation.config);
        } catch (Throwable error) {
            startError = error;
        }
        activation.closed.set(true);

        PostCommitPlan postCommit;
        CommitDecision decision;
        boolean emergencyRollback = false;
        boolean cleanupRequired = false;
        synchronized (coordinator) {
            RuntimeView previous = view;
            RuntimeView published = null;
            try {
                decision = validateActivation(
                        runtime,
                        activation,
                        plans,
                        startError);
                RuntimeView.Draft draft = new RuntimeView.Draft(view);
                postCommit = publishActivationDecision(
                        draft,
                        runtime,
                        activation,
                        decision,
                        plans);
                refreshDiagnostics(draft);
                RuntimeView next = draft.publishOnce();
                view = next;
                published = next;
                commitActivationExecutable(
                        next,
                        runtime,
                        activation,
                        decision,
                        postCommit);
                cleanupRequired = decisionCleanupRequired(runtime);
                if (!cleanupRequired) {
                    runtime.finishTransition(
                            future,
                            componentState(runtime.handleId));
                }
            } catch (Throwable unexpected) {
                cleanupRequired = true;
                if (published != null) {
                    view = previous;
                }
                discardProvisionalChildren(plans);
                activation.markStale();
                decision = new CommitDecision(
                        false,
                        false,
                        false,
                        "activation commit failed: "
                                + LifecycleScopeImpl.safeError(unexpected));
                try {
                    RuntimeView.Draft rollback = new RuntimeView.Draft(view);
                    postCommit = publishActivationDecision(
                            rollback,
                            runtime,
                            activation,
                            decision,
                            plans);
                    refreshDiagnostics(rollback);
                    RuntimeView next = rollback.publishOnce();
                    view = next;
                    commitActivationExecutable(
                            next,
                            runtime,
                            activation,
                            decision,
                            postCommit);
                } catch (Throwable fatal) {
                    activation.markStale();
                    runtime.pendingStartFailure = true;
                    runtime.lastStartError = decision.message();
                    emergencyRollback = true;
                    try {
                        emergencyRollbackActivation(runtime, activation);
                    } catch (Throwable ignored) {
                        // The transition future below is still completed exceptionally.
                    }
                    postCommit = new PostCommitPlan(
                            List.of(),
                            Set.of(runtime.handleId),
                            new ExecutableCommitPlan());
                }
            }
        }

        if (emergencyRollback) {
            runtime.failTransition(
                    future,
                    new IllegalStateException(decision.message()));
            return;
        }
        scheduleAfterCommit(postCommit.dirty());
        if (cleanupRequired) {
            finishCleanupAfterDependents(
                    runtime,
                    activation,
                    future);
        }
    }

    private void emergencyRollbackActivation(
            ComponentRuntime runtime,
            ActivationRuntime activation) {
        RuntimeView.Draft draft = new RuntimeView.Draft(view);
        RuntimeView.ComponentData data = draft.components.get(runtime.handleId);
        if (data != null) {
            draft.components.put(
                    runtime.handleId,
                    data.withState(ComponentState.STOPPING));
        }
        RuntimeView.ActivationData activationData =
                draft.activations.get(activation.activationId);
        if (activationData != null) {
            draft.activations.put(
                    activation.activationId,
                    activationData.detached());
        }
        refreshDiagnostics(draft);
        view = draft.publishOnce();
    }

    private void discardProvisionalChildren(List<ChildMountPlan<?>> plans) {
        for (ChildMountPlan plan : plans) {
            String handleId = plan.handle().handleId();
            ComponentRuntime child = components.remove(handleId);
            if (child != null) {
                child.current = null;
                child.failedCleanup = null;
            }
            componentHandles.remove(handleId);
            terminalComponents.put(handleId, ComponentState.DISPOSED);
        }
    }

    private CommitDecision validateActivation(
            ComponentRuntime runtime,
            ActivationRuntime activation,
            List<ChildMountPlan<?>> plans,
            Throwable startError) {
        RuntimeView current = view;
        RuntimeView.ComponentData data = current.components.get(runtime.handleId);
        if (data == null || data.goal() != ComponentGoal.RUNNING) {
            return new CommitDecision(false, true, false, "component goal changed");
        }
        RuntimeView.ContextData context = current.contexts.get(data.contextId());
        if (context == null || context.state() != ContextState.ACTIVE) {
            return new CommitDecision(false, true, false, "context changed");
        }
        if (data.configRevision() != activation.configRevision
                || runtime.desiredRevision != activation.configRevision) {
            return new CommitDecision(false, true, false, "configuration changed");
        }
        if (activation.stale.get()) {
            return new CommitDecision(false, true, false, "activation became stale");
        }
        for (CapabilityRequirement requirement
                : data.descriptor().sortedRequirements()) {
            RuntimeView.BindingData captured =
                    activation.bindings.get(requirement.key().name());
            RuntimeView.BindingData effective = current.effectiveBindings(
                            data,
                            activation.stagedRegistrations)
                    .get(requirement.key().name());
            if (!bindingIdentityEqual(captured, effective)) {
                return new CommitDecision(
                        false,
                        true,
                        false,
                        "binding changed: " + requirement.key().name());
            }
        }
        for (RuntimeView.RegistrationData staged
                : activation.stagedRegistrations.values()) {
            Class<?> existing = current.capabilityTypes.get(staged.key().name());
            if (existing != null && existing != staged.key().type()) {
                return new CommitDecision(
                        false,
                        false,
                        false,
                        "staged capability type conflict: " + staged.key().name());
            }
            boolean occupied = current.registrations.values().stream().anyMatch(
                    registration -> registration.contextId()
                                    .equals(staged.contextId())
                            && registration.key().name().equals(staged.key().name()));
            if (occupied) {
                return new CommitDecision(
                        false,
                        false,
                        false,
                        "staged capability slot occupied: " + staged.key().name());
            }
        }
        String childConflict = childPlanConflict(current, data.contextId(), plans);
        if (childConflict != null) {
            return new CommitDecision(false, false, false, childConflict);
        }
        if (RuntimeView.hasCycle(
                current.dependencyGraph(activation.stagedRegistrations))) {
            return new CommitDecision(
                    false,
                    false,
                    true,
                    "binding cycle rejected: " + runtime.handleId);
        }
        if (startError != null) {
            return new CommitDecision(
                    false,
                    false,
                    false,
                    LifecycleScopeImpl.safeError(startError));
        }
        return new CommitDecision(true, false, false, "");
    }

    private String childPlanConflict(
            RuntimeView current,
            String contextId,
            List<ChildMountPlan<?>> plans) {
        Map<String, Class<?>> tentativeTypes = new HashMap<>(current.capabilityTypes);
        for (RuntimeView.RegistrationData staged
                : activationRegistrationsForValidation(current, plans).values()) {
            Class<?> existing = tentativeTypes.putIfAbsent(
                    staged.key().name(), staged.key().type());
            if (existing != null && existing != staged.key().type()) {
                return "staged capability type conflict: " + staged.key().name();
            }
        }

        Set<String> batchIds = new LinkedHashSet<>();
        for (ChildMountPlan plan : plans) {
            String identity = contextId + "/" + plan.mountId();
            if (!batchIds.add(identity)) {
                return "staged child mountId conflicts in transaction: " + plan.mountId();
            }
            boolean occupied = current.components.values().stream().anyMatch(component ->
                    component.contextId().equals(contextId)
                            && component.mountId().equals(plan.mountId()));
            if (occupied) {
                return "staged child mountId conflicts latest view: " + plan.mountId();
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

    private Map<String, RuntimeView.RegistrationData> activationRegistrationsForValidation(
            RuntimeView current,
            List<ChildMountPlan<?>> plans) {
        Map<String, RuntimeView.RegistrationData> registrations = new HashMap<>();
        for (ActivationRuntime activation : activations.values()) {
            if (activation.stale.get()
                    || !current.activations.containsKey(activation.activationId)
                    || current.activations.get(activation.activationId).state()
                            == ActivationState.STOPPING) {
                continue;
            }
            registrations.putAll(activation.stagedRegistrations);
        }
        return registrations;
    }
    private PostCommitPlan publishActivationDecision(
            RuntimeView.Draft draft,
            ComponentRuntime runtime,
            ActivationRuntime activation,
            CommitDecision decision,
            List<ChildMountPlan<?>> plans) {
        RuntimeView.ComponentData data = draft.components.get(runtime.handleId);
        RuntimeView.ActivationData activationData =
                draft.activations.get(activation.activationId);
        if (data == null || activationData == null) {
            return new PostCommitPlan(List.of(), Set.of(), new ExecutableCommitPlan());
        }
        if (decision.success()) {
            for (RuntimeView.RegistrationData staged
                    : activation.stagedRegistrations.values()) {
                draft.registrations.put(
                        staged.registrationId(),
                        staged);
                draft.capabilityTypes.putIfAbsent(
                        staged.key().name(),
                        staged.key().type());
            }
            for (ChildMountPlan plan : plans) {
                for (CapabilityRequirement requirement
                        : plan.prepared().descriptor().sortedRequirements()) {
                    draft.capabilityTypes.putIfAbsent(
                            requirement.key().name(),
                            requirement.key().type());
                }
                String childId = plan.handle().handleId();
                draft.components.put(childId, new RuntimeView.ComponentData(
                        childId,
                        data.contextId(),
                        plan.mountId(),
                        plan.prepared().descriptor().componentId(),
                        plan.prepared().factoryId(),
                        plan.prepared().options().origin(),
                        activation.activationId,
                        runtime.handleId,
                        ComponentState.WAITING,
                        ComponentGoal.RUNNING,
                        1,
                        null,
                        null,
                        plan.prepared().descriptor(),
                        plan.prepared().options()));
            }
            draft.activations.put(
                    activation.activationId,
                    activationData.withState(ActivationState.ACTIVE));
            draft.components.put(
                    runtime.handleId,
                    data.withState(ComponentState.ACTIVE));

            Set<String> changed = new LinkedHashSet<>();
            for (RuntimeView.ComponentData component : draft.components.values()) {
                if (component.currentActivationId() == null
                        || component.handleId().equals(runtime.handleId)) {
                    continue;
                }
                RuntimeView.ActivationData other = draft.activations.get(
                        component.currentActivationId());
                if (other == null
                        || !RuntimeView.activationTracksGraph(other.state())) {
                    continue;
                }
                Map<String, RuntimeView.BindingData> effective =
                        draft.effectiveBindings(
                                component,
                                activation.stagedRegistrations);
                for (CapabilityRequirement requirement
                        : component.descriptor().sortedRequirements()) {
                    RuntimeView.BindingData old =
                            other.bindings().get(requirement.key().name());
                    RuntimeView.BindingData next =
                            effective.get(requirement.key().name());
                    if (!bindingIdentityEqual(old, next)) {
                        changed.add(component.handleId());
                        break;
                    }
                }
            }
            ExecutableCommitPlan executable = new ExecutableCommitPlan();
            Set<String> dirty = new LinkedHashSet<>();
            Set<String> detachTargets = new LinkedHashSet<>();
            for (String handleId : changed) {
                RuntimeView.ComponentData component =
                        draft.components.get(handleId);
                if (component == null || component.currentActivationId() == null) {
                    detachTargets.add(handleId);
                    continue;
                }
                detachTargets.addAll(disposeOwnershipForActivation(
                        draft,
                        handleId,
                        component.currentActivationId(),
                        executable));
            }
            Set<String> closure = draft.dependentsClosure(detachTargets);
            detachInView(draft, closure, dirty, executable);
            return new PostCommitPlan(plans, dirty, executable);
        }

        draft.activations.put(
                activation.activationId,
                activationData.detached());
        draft.components.put(
                runtime.handleId,
                data.withState(ComponentState.STOPPING));
        activation.markStale();
        for (ChildMountPlan plan : plans) {
            terminalComponents.put(plan.handle().handleId(), ComponentState.DISPOSED);
        }
        Set<String> dirty = new LinkedHashSet<>(Set.of(runtime.handleId));
        return new PostCommitPlan(List.of(), dirty, new ExecutableCommitPlan());
    }

    private void commitActivationExecutable(
            RuntimeView next,
            ComponentRuntime runtime,
            ActivationRuntime activation,
            CommitDecision decision,
            PostCommitPlan postCommit) {
        activation.stale.set(!decision.success() || decision.stale());
        runtime.pendingStartFailure = !decision.success() && !decision.stale() && !decision.suppressCycle();
        runtime.suppressAutoRestart = decision.suppressCycle();
        runtime.lastStartError = decision.success() || decision.stale()
                ? ""
                : decision.message();
        for (String activationId : postCommit.executable().staleActivations) {
            ActivationRuntime impacted = activations.get(activationId);
            if (impacted != null) {
                impacted.markStale();
            }
        }
        for (ChildMountPlan plan : postCommit.children()) {
            String handleId = plan.handle().handleId();
            if (!next.components.containsKey(handleId)) {
                terminalComponents.put(handleId, ComponentState.DISPOSED);
                continue;
            }
            ComponentRuntime child = new ComponentRuntime(
                    handleId,
                    next.components.get(handleId).contextId(),
                    plan.mountId(),
                    plan.prepared());
            components.put(handleId, child);
            componentHandles.put(handleId, plan.handle());
        }
    }

    private boolean decisionCleanupRequired(ComponentRuntime runtime) {
        RuntimeView.ComponentData data = view.components.get(runtime.handleId);
        return data != null && data.state() == ComponentState.STOPPING;
    }

    private void finishCleanupAfterDependents(
            ComponentRuntime runtime,
            ActivationRuntime activation,
            CompletableFuture<ComponentState> future) {
        List<ComponentRuntime> dependents;
        synchronized (coordinator) {
            dependents = dependentsForProvider(runtime.handleId);
        }
        List<CompletableFuture<ComponentState>> settlements = dependents.stream()
                .map(dependent -> dependent.enqueue(this, executor))
                .toList();
        CompletableFuture<Void> prerequisite = settlements.isEmpty()
                ? CompletableFuture.completedFuture(null)
                : CompletableFuture.allOf(settlements.toArray(
                        new CompletableFuture[0]));
        prerequisite.whenComplete((ignored, error) ->
                finishCleanup(runtime, activation, future));
    }

    private List<ComponentRuntime> dependentsForProvider(String providerHandleId) {
        RuntimeView current = view;
        Map<String, Set<String>> dependencies = stopProviders(
                current,
                new LinkedHashSet<>(current.components.keySet()));
        Set<String> directAndIndirect = new LinkedHashSet<>();
        Set<String> frontier = new LinkedHashSet<>();
        for (Map.Entry<String, Set<String>> entry : dependencies.entrySet()) {
            if (entry.getValue().contains(providerHandleId)) {
                frontier.add(entry.getKey());
            }
        }
        while (!frontier.isEmpty()) {
            String handleId = frontier.iterator().next();
            frontier.remove(handleId);
            if (!directAndIndirect.add(handleId)) {
                continue;
            }
            for (Map.Entry<String, Set<String>> entry : dependencies.entrySet()) {
                if (entry.getValue().contains(handleId)
                        && !directAndIndirect.contains(entry.getKey())) {
                    frontier.add(entry.getKey());
                }
            }
        }
        for (String descendant : current.ownershipDescendants(providerHandleId)) {
            if (!descendant.equals(providerHandleId)) {
                directAndIndirect.add(descendant);
            }
        }
        return directAndIndirect.stream()
                .filter(handleId -> current.components.containsKey(handleId))
                .filter(handleId -> current.components.get(handleId).state()
                        == ComponentState.STOPPING)
                .map(components::get)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    private void finishCleanup(
            ComponentRuntime runtime,
            ActivationRuntime activation,
            CompletableFuture<ComponentState> future) {
        activation.scope.teardown().whenComplete((ignored, cleanupError) -> {
            ComponentState state;
            boolean restart;
            ComponentRuntime.Reservation restartReservation = null;
            synchronized (coordinator) {
                RuntimeView.Draft draft = new RuntimeView.Draft(view);
                RuntimeView.ComponentData data =
                        draft.components.get(runtime.handleId);
                boolean failed = cleanupError != null
                        || activation.scope.state() == LifecycleState.FAILED;
                if (data == null) {
                    draft.activations.remove(activation.activationId);
                    activations.remove(activation.activationId);
                    state = ComponentState.DISPOSED;
                    restart = false;
                } else {
                    ComponentGoal latestGoal = data.goal();
                    RuntimeView.ActivationData activationData =
                            draft.activations.get(activation.activationId);
                    if (failed) {
                        String cleanupDetail = cleanupError == null
                                ? activation.scope.lastCleanupError()
                                : LifecycleScopeImpl.safeError(cleanupError);
                        runtime.lastCleanupError = cleanupDetail.isBlank()
                                ? "cleanup failed"
                                : "cleanup failed: " + cleanupDetail;
                        runtime.failedCleanup = activation;
                        if (activationData != null) {
                            draft.activations.put(
                                    activation.activationId,
                                    activationData.withState(
                                            ActivationState.FAILED));
                        }
                        draft.components.put(
                                runtime.handleId,
                                data.withState(ComponentState.FAILED));
                        state = ComponentState.FAILED;
                        restart = false;
                    } else {
                        runtime.lastCleanupError = "";
                        runtime.failedCleanup = null;
                        draft.activations.remove(activation.activationId);
                        activations.remove(activation.activationId);
                        if (latestGoal == ComponentGoal.DISPOSED) {
                            removeComponentInView(draft, runtime.handleId);
                            components.remove(runtime.handleId);
                            componentHandles.remove(runtime.handleId);
                            terminalComponents.put(
                                    runtime.handleId,
                                    ComponentState.DISPOSED);
                            state = ComponentState.DISPOSED;
                            restart = false;
                        } else if (runtime.pendingStartFailure) {
                            draft.components.put(
                                    runtime.handleId,
                                    data.withState(ComponentState.FAILED)
                                            .clearActivation());
                            runtime.current = null;
                            state = ComponentState.FAILED;
                            restart = false;
                        } else {
                            draft.components.put(
                                    runtime.handleId,
                                    data.withState(ComponentState.WAITING)
                                            .clearActivation());
                            runtime.current = null;
                            state = ComponentState.WAITING;
                            restart = planReconcile(
                                    draft,
                                    draft.components.get(runtime.handleId),
                                    runtime);
                        }
                    }
                }
                refreshDiagnostics(draft);
                view = draft.publishOnce();
                if (restart) {
                    restartReservation = runtime.replaceTransition();
                } else {
                    runtime.finishTransition(future, state);
                }
            }

            if (restartReservation != null) {
                runtime.finishTransition(future);
                restartReservation.component().executeReserved(
                        this,
                        executor,
                        restartReservation.future());
                restartReservation.future().whenComplete((next, error) -> {
                    if (error != null) {
                        future.completeExceptionally(error);
                    } else {
                        future.complete(next);
                    }
                });
            }
        });
    }

    private boolean planReconcile(
            RuntimeView.Draft draft,
            RuntimeView.ComponentData data,
            ComponentRuntime runtime) {
        String fingerprint = reconcileFingerprint(draft, data);
        if (!fingerprint.equals(runtime.reconcileFingerprint)) {
            runtime.reconcileFingerprint = fingerprint;
            runtime.reconcileAttempts = 0;
            runtime.suppressAutoRestart = false;
            runtime.blockedNonConvergent = false;
        }
        if (runtime.suppressAutoRestart) {
            return false;
        }
        runtime.reconcileAttempts++;
        if (runtime.reconcileAttempts >= configuration.maxReconcileIterations()) {
            runtime.blockedNonConvergent = true;
            return false;
        }
        return true;
    }

    private String reconcileFingerprint(
            RuntimeView.Draft draft,
            RuntimeView.ComponentData data) {
        Map<String, RuntimeView.BindingData> bindings =
                draft.effectiveBindings(data, Map.of());
        return data.contextId() + "|" + data.goal() + "|" + data.configRevision()
                + "|" + data.descriptor().sortedRequirements().stream()
                        .map(requirement -> {
                            RuntimeView.BindingData binding =
                                    bindings.get(requirement.key().name());
                            return requirement.key().name() + ":"
                                    + (binding == null || !binding.present()
                                            ? "-"
                                            : binding.registrationId());
                        })
                        .collect(Collectors.joining(","));
    }

    private void scheduleAfterCommit(Set<String> dirty) {
        schedule(dirty);
        RuntimeView current = view;
        Set<String> waiting = current.components.values().stream()
                .filter(component -> component.state() == ComponentState.WAITING
                        && component.goal() == ComponentGoal.RUNNING)
                .map(RuntimeView.ComponentData::handleId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        schedule(waiting);
    }

    CompletionStage<Void> disposeContextInView(
            ContextHandleImpl handle,
            boolean rootClose) {
        Set<String> dirty;
        Set<String> subtree;
        CompletableFuture<Void> future;
        synchronized (coordinator) {
            RuntimeView.ContextData data = view.contexts.get(handle.contextId());
            if (data == null || data.state() == ContextState.DISPOSED) {
                return CompletableFuture.completedFuture(null);
            }
            synchronized (contextFutures) {
                CompletableFuture<Void> existing = contextFutures.get(handle.contextId());
                if (existing != null && !existing.isCompletedExceptionally()) {
                    return existing;
                }
                future = new CompletableFuture<>();
                contextFutures.put(handle.contextId(), future);
            }

            RuntimeView.Draft draft = new RuntimeView.Draft(view);
            ExecutableCommitPlan executable = new ExecutableCommitPlan();
            dirty = new LinkedHashSet<>();
            subtree = draft.contextSubtree(handle.contextId());
            for (String contextId : subtree) {
                RuntimeView.ContextData child = draft.contexts.get(contextId);
                draft.contexts.put(contextId, child.withState(ContextState.DISPOSING));
            }
            draft.registrations.values().removeIf(registration ->
                    subtree.contains(registration.contextId())
                            && registration.owner()
                                    instanceof RuntimeView.OwnerData.Host);
            Set<String> handles = draft.components.values().stream()
                    .filter(component -> subtree.contains(component.contextId()))
                    .flatMap(component ->
                            draft.ownershipDescendants(component.handleId()).stream())
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            for (String handleId : handles) {
                RuntimeView.ComponentData component = draft.components.get(handleId);
                if (component == null) {
                    continue;
                }
                draft.components.put(
                        handleId,
                        component.withGoal(ComponentGoal.DISPOSED));
            }
            Set<String> live = handles.stream()
                    .filter(handleId -> draft.components.get(handleId) != null
                            && draft.components.get(handleId).currentActivationId() != null)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            Set<String> closure = draft.dependentsClosure(live);
            detachInView(draft, closure, dirty, executable);
            for (String handleId : handles) {
                RuntimeView.ComponentData component = draft.components.get(handleId);
                if (component != null && component.currentActivationId() == null) {
                    removeComponentInView(draft, handleId);
                    executable.removedComponents.add(handleId);
                } else if (component != null) {
                    dirty.add(handleId);
                }
            }
            refreshDiagnostics(draft);
            RuntimeView next = draft.publishOnce();
            view = next;
            commitExecutable(next, List.of(), executable);
        }

        CompletableFuture<Void> settlement = CompletableFuture.allOf(
                schedule(dirty).toArray(new CompletableFuture[0]));
        settlement.whenComplete((ignored, error) ->
                finalizeContext(subtree, future));
        return future;
    }

    private void finalizeContext(
            Set<String> subtree,
            CompletableFuture<Void> future) {
        synchronized (coordinator) {
            RuntimeView.Draft draft = new RuntimeView.Draft(view);
            boolean failed = draft.components.values().stream()
                    .filter(component -> subtree.contains(component.contextId()))
                    .anyMatch(component -> component.state()
                            == ComponentState.FAILED);
            for (String contextId : subtree) {
                RuntimeView.ContextData data = draft.contexts.get(contextId);
                if (data != null) {
                    draft.contexts.put(contextId, data.withState(
                            failed ? ContextState.FAILED : ContextState.DISPOSED));
                }
            }
            if (!failed) {
                for (String contextId : subtree) {
                    if (!contextId.equals("ctx-root")) {
                        draft.contexts.remove(contextId);
                    }
                }
            }
            refreshDiagnostics(draft);
            RuntimeView next = draft.publishOnce();
            view = next;
            for (String contextId : subtree) {
                contextFutures.remove(contextId);
                if (!failed && !contextId.equals("ctx-root")) {
                    contextHandles.remove(contextId);
                }
            }
            registrationHandles.keySet().retainAll(next.registrations.keySet());
            if (failed) {
                future.completeExceptionally(
                        new IllegalStateException("context cleanup failed"));
            } else {
                future.complete(null);
            }
        }
    }

    private Set<String> outermostContextDisposals(Set<String> requested) {
        RuntimeView current = view;
        Set<String> result = new LinkedHashSet<>();
        for (String candidate : requested) {
            boolean covered = requested.stream().anyMatch(ancestor ->
                    !ancestor.equals(candidate)
                            && current.contexts.containsKey(ancestor)
                            && current.contexts.get(candidate) != null
                            && current.isInSubtree(candidate, ancestor));
            if (!covered) {
                result.add(candidate);
            }
        }
        return result;
    }

    private CompletableFuture<Void> settleContextDisposal(
            String contextId,
            CompletableFuture<Void> prerequisite) {
        CompletableFuture<Void> future;
        Set<String> subtree;
        Set<String> components;
        synchronized (coordinator) {
            RuntimeView current = view;
            if (!current.contexts.containsKey(contextId)) {
                return CompletableFuture.completedFuture(null);
            }
            synchronized (contextFutures) {
                CompletableFuture<Void> existing = contextFutures.get(contextId);
                if (existing != null && !existing.isCompletedExceptionally()) {
                    return existing;
                }
                future = new CompletableFuture<>();
                contextFutures.put(contextId, future);
            }
            subtree = current.contextSubtree(contextId);
            components = current.components.values().stream()
                    .filter(component -> subtree.contains(component.contextId()))
                    .map(RuntimeView.ComponentData::handleId)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }

        CompletableFuture<Void> subtreeSettlement = CompletableFuture.allOf(
                schedule(components).toArray(new CompletableFuture[0]));
        CompletableFuture<Void> settlement = prerequisite == null
                ? subtreeSettlement
                : CompletableFuture.allOf(prerequisite, subtreeSettlement);
        settlement.whenComplete((ignored, error) ->
                finalizeContext(subtree, future));
        return future;
    }
    private void refreshDiagnostics(RuntimeView.Draft draft) {
        List<RuntimeDiagnostic> diagnostics = new ArrayList<>();
        for (RuntimeView.ComponentData component : draft.components.values()) {
            if (component.state() == ComponentState.WAITING
                    && component.goal() == ComponentGoal.RUNNING) {
                for (CapabilityRequirement requirement
                        : component.descriptor().sortedRequirements()) {
                    if (requirement.mode() != CapabilityRequirement.Mode.REQUIRED) {
                        continue;
                    }
                    boolean present = draft.resolve(
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
                ComponentRuntime runtime = components.get(component.handleId());
                if (runtime != null && runtime.blockedNonConvergent) {
                    diagnostics.add(new RuntimeDiagnostic(
                            DiagnosticCode.NON_CONVERGENT_RECONCILE,
                            component.handleId(),
                            "reconcile did not converge after "
                                    + configuration.maxReconcileIterations()
                                    + " attempts"));
                }
                if (runtime != null && runtime.suppressAutoRestart
                        && runtime.lastStartError.startsWith("binding cycle")) {
                    diagnostics.add(new RuntimeDiagnostic(
                            DiagnosticCode.BINDING_CYCLE,
                            component.handleId(),
                            runtime.lastStartError));
                }
            }
            if (component.state() == ComponentState.FAILED) {
                ComponentRuntime runtime = components.get(component.handleId());
                String startError = runtime == null ? "" : runtime.lastStartError;
                String cleanupError = runtime == null ? "" : runtime.lastCleanupError;
                if (!startError.isBlank()) {
                    diagnostics.add(new RuntimeDiagnostic(
                            DiagnosticCode.ACTIVATION_FAILED,
                            component.handleId(),
                            startError));
                }
                if (!cleanupError.isBlank()) {
                    diagnostics.add(new RuntimeDiagnostic(
                            DiagnosticCode.CLEANUP_FAILED,
                            component.handleId(),
                            cleanupError));
                }
                if (startError.isBlank() && cleanupError.isBlank()) {
                    diagnostics.add(new RuntimeDiagnostic(
                            DiagnosticCode.ACTIVATION_FAILED,
                            component.handleId(),
                            "component failed"));
                }
            }
        }
        draft.diagnostics.clear();
        draft.diagnostics.addAll(diagnostics.stream().sorted().toList());
    }

    private static Reject reject(
            DiagnosticCode code,
            String target,
            String message) {
        return new Reject(new RuntimeDiagnostic(code, target, message));
    }

    private static <T> CompletableFuture<T> failedFuture(String message) {
        CompletableFuture<T> future = new CompletableFuture<>();
        future.completeExceptionally(new IllegalStateException(message));
        return future;
    }

    private record PostCommitPlan(
            List<ChildMountPlan<?>> children,
            Set<String> dirty,
            ExecutableCommitPlan executable) {
    }

    private record ComponentTerminal(ComponentState state) {
    }

    private record CommitDecision(
            boolean success,
            boolean stale,
            boolean suppressCycle,
            String message) {
    }

    sealed interface Intent permits
            ProvideIntent,
            RevokeIntent,
            ChildContextIntent,
            MountIntent,
            ReconfigureIntent,
            DisposeIntent,
            ContextDisposeIntent {
    }

    private record ProvideIntent(
            RegistrationHandleImpl handle,
            ContextHandleImpl context,
            CapabilityKey<?> key,
            Object value) implements Intent {
    }

    private record RevokeIntent(
            RegistrationHandleImpl handle) implements Intent {
    }

    private record ChildContextIntent(
            ContextHandleImpl parent,
            String name,
            ContextHandleImpl handle) implements Intent {
    }

    record MountIntent<C>(
            ContextHandleImpl context,
            String mountId,
            PreparedComponent<C> prepared,
            ComponentHandleImpl<C> handle) implements Intent {
    }

    private record ReconfigureIntent<C>(
            ComponentHandleImpl<C> handle,
            Object config,
            long expectedRevision,
            boolean equivalent) implements Intent {
    }

    private record ProvisionalConfig(Object config, long revision) {
    }

    private record DisposeIntent(
            ComponentHandleImpl<?> handle) implements Intent {
    }

    private record ContextDisposeIntent(
            ContextHandleImpl handle) implements Intent {
    }

    private static final class Reject extends RuntimeException {
        private final RuntimeDiagnostic diagnostic;

        private Reject(RuntimeDiagnostic diagnostic) {
            super(diagnostic.message(), null, false, false);
            this.diagnostic = diagnostic;
        }

        private RuntimeDiagnostic diagnostic() {
            return diagnostic;
        }
    }

    private final class MutationRecorder implements RuntimeMutation {
        private final List<Intent> intents = new ArrayList<>();
        private final Map<String, ProvisionalConfig> provisionalConfigs = new HashMap<>();
        @Override
        public <T> RegistrationHandle provide(
                ContextHandle context,
                CapabilityKey<T> key,
                T value) {
            Objects.requireNonNull(context, "context");
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(value, "value");
            RegistrationHandleImpl handle = new RegistrationHandleImpl(
                    DefaultKnotraRuntime.this,
                    Sequences.registration());
            intents.add(new ProvideIntent(
                    handle,
                    (ContextHandleImpl) context,
                    key,
                    value));
            return handle;
        }

        @Override
        public void revoke(RegistrationHandle registration) {
            Objects.requireNonNull(registration, "registration");
            if (!(registration instanceof RegistrationHandleImpl handle)
                    || handle.runtime != DefaultKnotraRuntime.this) {
                throw new IllegalArgumentException(
                        "registration handle does not belong to this runtime");
            }
            intents.add(new RevokeIntent(handle));
        }

        @Override
        public ContextHandle childContext(ContextHandle parent, String name) {
            Objects.requireNonNull(parent, "parent");
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("context name must not be blank");
            }
            ContextHandleImpl handle = new ContextHandleImpl(
                    DefaultKnotraRuntime.this,
                    Sequences.context(name));
            intents.add(new ChildContextIntent(
                    (ContextHandleImpl) parent,
                    name,
                    handle));
            return handle;
        }

        @Override
        public <C> ComponentHandle<C> mount(
                ContextHandle context,
                String mountId,
                ComponentFactory<C> factory,
                C config) {
            return mount(context, mountId, factory, config, MountOptions.DEFAULT);
        }

        @Override
        public <C> ComponentHandle<C> mount(
                ContextHandle context,
                String mountId,
                ComponentFactory<C> factory,
                C config,
                MountOptions options) {
            Objects.requireNonNull(context, "context");
            PreparedComponent<C> prepared = PreparedComponent.prepare(
                    factory,
                    config,
                    options == null ? MountOptions.DEFAULT : options);
            ComponentHandleImpl<C> handle = new ComponentHandleImpl<>(
                    DefaultKnotraRuntime.this,
                    Sequences.handle());
            provisionalConfigs.put(
                    handle.handleId(),
                    new ProvisionalConfig(prepared.config(), 1));
            intents.add(new MountIntent<>(
                    (ContextHandleImpl) context,
                    mountId,
                    prepared,
                    handle));
            return handle;
        }

        @Override
        public <C> ComponentHandle<C> reconfigure(
                ComponentHandle<C> handle,
                C config) {
            Objects.requireNonNull(handle, "handle");
            Objects.requireNonNull(
                    config,
                    "config (use NoConfig.INSTANCE for components without configuration)");
            if (!(handle instanceof ComponentHandleImpl<C> typed)
                    || typed.runtime != DefaultKnotraRuntime.this
                    || !ownsOrProvisionallyOwns(typed)) {
                throw new IllegalArgumentException(
                        "component handle does not belong to this runtime");
            }
            PreparedComponent<?> prepared = preparedFor(typed);
            ProvisionalConfig current = provisionalConfigFor(typed, prepared);
            Object normalized = normalizeFor(prepared, config);
            long expectedRevision = current.revision();
            boolean equivalent = Objects.equals(normalized, current.config());
            if (!equivalent) {
                provisionalConfigs.put(
                        typed.handleId(),
                        new ProvisionalConfig(normalized, expectedRevision + 1));
            }
            intents.add(new ReconfigureIntent<>(
                    typed,
                    normalized,
                    expectedRevision,
                    equivalent));
            return typed;
        }

        @Override
        public void dispose(ComponentHandle<?> handle) {
            Objects.requireNonNull(handle, "handle");
            if (!(handle instanceof ComponentHandleImpl<?> typed)
                    || typed.runtime != DefaultKnotraRuntime.this
                    || !ownsOrProvisionallyOwns(typed)) {
                throw new IllegalArgumentException(
                        "component handle does not belong to this runtime");
            }
            intents.add(new DisposeIntent(typed));
        }

        @Override
        public void dispose(ContextHandle context) {
            Objects.requireNonNull(context, "context");
            if (!(context instanceof ContextHandleImpl handle)
                    || handle.runtime != DefaultKnotraRuntime.this) {
                throw new IllegalArgumentException(
                        "context handle does not belong to this runtime");
            }
            intents.add(new ContextDisposeIntent(handle));
        }

        private boolean ownsOrProvisionallyOwns(ComponentHandleImpl<?> handle) {
            for (Intent intent : intents) {
                if (intent instanceof MountIntent<?> mount
                        && mount.handle().handleId().equals(handle.handleId())) {
                    return true;
                }
            }
            return componentHandles.get(handle.handleId()) == handle;
        }

        private PreparedComponent<?> preparedFor(ComponentHandleImpl<?> handle) {
            for (Intent intent : intents) {
                if (intent instanceof MountIntent<?> mount
                        && mount.handle().handleId().equals(handle.handleId())) {
                    return mount.prepared();
                }
            }
            ComponentRuntime runtime = components.get(handle.handleId());
            if (runtime != null) {
                return runtime.prepared;
            }
            throw new IllegalArgumentException(
                    "component handle does not belong to this runtime");
        }

        private ProvisionalConfig provisionalConfigFor(
                ComponentHandleImpl<?> handle,
                PreparedComponent<?> prepared) {
            ProvisionalConfig provisional = provisionalConfigs.get(handle.handleId());
            if (provisional != null) {
                return provisional;
            }
            ComponentRuntime runtime = components.get(handle.handleId());
            return runtime == null
                    ? new ProvisionalConfig(prepared.config(), 1)
                    : new ProvisionalConfig(runtime.desiredConfig, runtime.desiredRevision);
        }


        private Object normalizeFor(PreparedComponent<?> prepared, Object rawConfig) {
            try {
                return prepared.normalize(rawConfig);
            } catch (RuntimeException error) {
                throw error;
            } catch (Exception error) {
                throw new IllegalArgumentException(
                        LifecycleScopeImpl.safeError(error),
                        error);
            }
        }
    }
}
