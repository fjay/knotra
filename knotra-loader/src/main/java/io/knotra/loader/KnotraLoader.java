package io.knotra.loader;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import io.knotra.ComponentHandle;
import io.knotra.ComponentState;
import io.knotra.ContextHandle;
import io.knotra.ContextState;
import io.knotra.DiagnosticCode;
import io.knotra.KnotraRuntime;
import io.knotra.MutationResult;
import io.knotra.NoConfig;
import io.knotra.RuntimeDiagnostic;
import io.knotra.RuntimeSnapshot;

public final class KnotraLoader implements AutoCloseable {

    private static final AtomicLong NEXT_ID = new AtomicLong();

    private final String loaderId;
    private final boolean owned;
    private final KnotraRuntime runtime;
    private final ContextHandle baseContext;
    private final ComponentFactoryResolver resolver;
    private final ExecutorService coordinator;
    private final AtomicReference<Thread> coordinatorThread = new AtomicReference<>();
    private final Object closeGate = new Object();

    private final TreeMap<String, ManagedEntry> current = new TreeMap<>();
    private final TreeMap<String, ContextHandle> contexts = new TreeMap<>();
    private volatile LoaderView view = LoaderView.EMPTY;
    private volatile List<LoaderDiagnostic> latestDiagnostics = List.of();
    private volatile boolean closed;
    private CompletableFuture<Void> closeAttempt;

    private KnotraLoader(
            KnotraRuntime runtime,
            ContextHandle baseContext,
            ComponentFactoryResolver resolver,
            boolean owned) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.loaderId = "loader-" + NEXT_ID.incrementAndGet();
        this.owned = owned;
        if (owned) {
            MutationResult<ContextHandle> result = runtime.mutate(mutation ->
                    mutation.childContext(runtime.rootContext(), loaderId));
            if (!result.committed()) {
                throw new IllegalArgumentException("owned base context creation was rejected");
            }
            this.baseContext = result.value();
        } else {
            this.baseContext = Objects.requireNonNull(baseContext, "baseContext");
        }
        this.coordinator = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, loaderId + "-coordinator");
            thread.setDaemon(true);
            return thread;
        });
        publish(List.of());
    }

    public static KnotraLoader owned(
            KnotraRuntime runtime,
            ComponentFactoryResolver resolver) {
        return new KnotraLoader(runtime, null, resolver, true);
    }

    public static KnotraLoader over(
            KnotraRuntime runtime,
            ContextHandle context,
            ComponentFactoryResolver resolver) {
        return new KnotraLoader(runtime, context, resolver, false);
    }

    public String loaderId() {
        return loaderId;
    }

    public boolean owned() {
        return owned;
    }

    public ContextHandle baseContext() {
        return baseContext;
    }

    public CompletionStage<ReconcileResult> reconcileAsync(ComponentTree desired) {
        Objects.requireNonNull(desired, "desired");
        if (closed) {
            return CompletableFuture.completedFuture(finish(
                    false,
                    List.of(),
                    List.of(LoaderDiagnostic.of(
                            LoaderDiagnosticCode.CLOSED,
                            "",
                            "loader is closed"))));
        }
        return enqueue(() -> performReconcile(desired));
    }

    public ReconcileResult reconcile(ComponentTree desired) {
        try {
            return reconcileAsync(desired).toCompletableFuture().get();
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof RuntimeException runtimeError) {
                throw runtimeError;
            }
            throw new IllegalStateException(cause == null ? error.getMessage() : cause.getMessage(), cause);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("reconcile was interrupted", error);
        }
    }

    public CompletionStage<ReconcileResult> retryAsync(String path) {
        String normalized = normalizePath(path, "");
        if (closed) {
            return CompletableFuture.completedFuture(finish(
                    false,
                    List.of(),
                    List.of(LoaderDiagnostic.of(
                            LoaderDiagnosticCode.CLOSED,
                            normalized,
                            "loader is closed"))));
        }
        return enqueue(() -> performRetry(normalized));
    }

    public ReconcileResult retry(String path) {
        try {
            return retryAsync(path).toCompletableFuture().get();
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof RuntimeException runtimeError) {
                throw runtimeError;
            }
            throw new IllegalStateException(cause == null ? error.getMessage() : cause.getMessage(), cause);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("retry was interrupted", error);
        }
    }

    public LoaderSnapshot snapshot() {
        LoaderView local = view;
        List<LoaderSnapshot.EntrySnapshot> entries = local.entries().values().stream()
                .map(entry -> new LoaderSnapshot.EntrySnapshot(
                        entry.path(),
                        entry.context().contextId(),
                        entry.context().contextInfo().canonicalPath(),
                        entry.handle().handleId(),
                        entry.handle().mountId(),
                        entry.handle().componentId(),
                        entry.definition().identity(),
                        entry.handle().configRevision(),
                        entry.handle().state(),
                        entry.handle().goal()))
                .toList();
        return new LoaderSnapshot(
                loaderId,
                owned,
                baseContext.contextId(),
                local.closed(),
                entries,
                latestDiagnostics);
    }

    public CompletionStage<Void> closeAsync() {
        rejectReentrant("close");
        synchronized (closeGate) {
            if (closeAttempt != null
                    && (!closeAttempt.isDone() || !closeAttempt.isCompletedExceptionally())) {
                return closeAttempt;
            }
            closed = true;
            closeAttempt = enqueue(this::performClose);
            return closeAttempt;
        }
    }

    @Override
    public void close() throws Exception {
        closeAsync().toCompletableFuture().get();
    }

    private <T> CompletableFuture<T> enqueue(Callable<T> operation) {
        rejectReentrant("coordinator operation");
        CompletableFuture<T> outcome = new CompletableFuture<>();
        try {
            coordinator.execute(() -> {
                Thread thread = Thread.currentThread();
                Thread previous = coordinatorThread.getAndSet(thread);
                if (previous != null && previous != thread) {
                    outcome.completeExceptionally(new IllegalStateException(
                            "loader coordinator thread changed"));
                    return;
                }
                try {
                    outcome.complete(operation.call());
                } catch (Throwable error) {
                    outcome.completeExceptionally(error);
                }
            });
        } catch (RuntimeException error) {
            outcome.completeExceptionally(error);
        }
        return outcome;
    }

    private void rejectReentrant(String operation) {
        if (coordinatorThread.get() == Thread.currentThread()) {
            throw new IllegalStateException(
                    "reentrant " + operation + " on the loader coordinator thread is not allowed");
        }
    }

    private ReconcileResult performReconcile(ComponentTree desired) {
        List<LoaderDiagnostic> diagnostics = new ArrayList<>();
        List<ReconcileResult.Change> changes = new ArrayList<>();
        boolean proposed = true;
        try {
            PreparedTree prepared = prepare(desired, diagnostics);
            if (!diagnostics.isEmpty()) {
                return finish(false, changes, diagnostics);
            }

            pruneExternallyDisposed();
            if (!settleFailedDesiredContexts(prepared, diagnostics)) {
                return finish(false, changes, diagnostics);
            }
            if (!removeMissing(prepared, changes, diagnostics)) {
                return finish(false, changes, diagnostics);
            }
            if (!replaceChangedFactories(prepared, changes, diagnostics)) {
                return finish(false, changes, diagnostics);
            }
            if (!addMissing(prepared, changes, diagnostics)) {
                return finish(false, changes, diagnostics);
            }
            updateConfigs(prepared, changes, diagnostics);
            addFailedDiagnostics(prepared, diagnostics);
        } catch (Throwable error) {
            proposed = false;
            diagnostics.add(LoaderDiagnostic.of(
                    LoaderDiagnosticCode.STRUCTURE_REJECTED,
                    "",
                    safeError(error)));
        }
        return finish(proposed && diagnostics.isEmpty(), changes, diagnostics);
    }

    private ReconcileResult performRetry(String path) {
        List<LoaderDiagnostic> diagnostics = new ArrayList<>();
        List<ReconcileResult.Change> changes = new ArrayList<>();
        ManagedEntry entry = current.get(path);
        if (entry == null) {
            diagnostics.add(LoaderDiagnostic.of(
                    LoaderDiagnosticCode.INVALID_TREE,
                    path,
                    "path is not managed or has no retryable activation"));
            return finish(false, changes, diagnostics);
        }

        if (entry.handle().state() == ComponentState.FAILED) {
            try {
                ComponentState state = entry.handle().retry()
                        .toCompletableFuture().get();
                current.put(path, entry.withHandle(entry.handle()));
                changes.add(ReconcileResult.Change.of(
                        ReconcileResult.ChangeType.RETRIED,
                        path));
                if (state == ComponentState.FAILED) {
                    diagnostics.add(LoaderDiagnostic.of(
                            LoaderDiagnosticCode.ACTIVATION_FAILED,
                            path,
                            "component retry did not activate"));
                }
            } catch (Exception error) {
                diagnostics.add(LoaderDiagnostic.of(
                        LoaderDiagnosticCode.ACTIVATION_FAILED,
                        path,
                        safeError(error)));
            }
            return finish(diagnostics.isEmpty(), changes, diagnostics);
        }

        if (entry.context().state() == ContextState.FAILED) {
            if (disposeContext(path, diagnostics)) {
                changes.add(ReconcileResult.Change.of(
                        ReconcileResult.ChangeType.REMOVED,
                        path));
            }
            return finish(diagnostics.isEmpty(), changes, diagnostics);
        }

        diagnostics.add(LoaderDiagnostic.of(
                LoaderDiagnosticCode.INVALID_TREE,
                path,
                "component is not FAILED"));
        return finish(false, changes, diagnostics);
    }

    private Void performClose() throws Exception {
        List<LoaderDiagnostic> diagnostics = new ArrayList<>();
        pruneExternallyDisposed();
        if (isRuntimeClosingBase()) {
            current.clear();
            contexts.clear();
            publish(diagnostics);
            coordinator.shutdown();
            return null;
        }

        boolean converged = true;
        if (owned) {
            converged &= disposeContext("", diagnostics);
        } else {
            for (String path : topLevelPaths()) {
                converged &= disposeContext(path, diagnostics);
            }
        }
        if (converged) {
            current.clear();
            contexts.clear();
            publish(diagnostics);
            coordinator.shutdown();
            return null;
        }

        publish(diagnostics);
        throw new IllegalStateException("loader close did not settle");
    }

    private boolean isRuntimeClosingBase() {
        ContextState baseState = baseContext.state();
        if (baseState != ContextState.DISPOSING && baseState != ContextState.DISPOSED) {
            return false;
        }
        return runtime.snapshot().contexts().stream()
                .filter(context -> context.contextId().equals(runtime.rootContext().contextId()))
                .findFirst()
                .map(context -> context.state() == ContextState.DISPOSING
                        || context.state() == ContextState.DISPOSED)
                .orElseGet(() -> runtime.rootContext().state() == ContextState.DISPOSING
                        || runtime.rootContext().state() == ContextState.DISPOSED);
    }

    /** True when an in-flight runtime close is already responsible for this teardown. */
    private boolean runtimeOwnsDisposalNow() {
        ContextState baseState = baseContext.state();
        if (baseState != ContextState.DISPOSING && baseState != ContextState.DISPOSED) {
            return false;
        }
        String rootId = runtime.rootContext().contextId();
        return runtime.snapshot().contexts().stream()
                .filter(context -> context.contextId().equals(rootId))
                .findFirst()
                .map(context -> context.state() == ContextState.DISPOSING
                        || context.state() == ContextState.DISPOSED)
                .orElse(true);
    }

    private boolean awaitRuntimeOwnedHandleDisposal(ComponentHandle<?> handle) {
        try {
            ComponentState state = handle.whenSettled()
                    .toCompletableFuture().get(30, TimeUnit.SECONDS);
            return state == ComponentState.DISPOSED;
        } catch (Exception error) {
            return false;
        }
    }

    private boolean awaitRuntimeOwnedContextDisposal(ContextHandle context) {
        CompletableFuture<Void> settled = new CompletableFuture<>();
        pollContextDisposal(context, settled, 3_000);
        try {
            settled.get(30, TimeUnit.SECONDS);
            return true;
        } catch (Exception error) {
            return false;
        }
    }

    private void pollContextDisposal(
            ContextHandle context,
            CompletableFuture<Void> settled,
            int remainingTicks) {
        if (context.state() == ContextState.DISPOSED) {
            settled.complete(null);
            return;
        }
        if (remainingTicks <= 0) {
            settled.completeExceptionally(new IllegalStateException(
                    "runtime-owned context disposal did not settle"));
            return;
        }
        CompletableFuture.delayedExecutor(10, TimeUnit.MILLISECONDS)
                .execute(() -> pollContextDisposal(context, settled, remainingTicks - 1));
    }

    private PreparedTree prepare(
            ComponentTree desired,
            List<LoaderDiagnostic> diagnostics) {
        Map<String, PreparedEntry> raw = new LinkedHashMap<>();
        collectEntries(desired.entries(), "", raw, diagnostics);
        if (!diagnostics.isEmpty()) {
            return new PreparedTree(Map.of());
        }
        for (String path : raw.keySet()) {
            String parent = parentPath(path);
            if (!parent.isEmpty() && !raw.containsKey(parent)) {
                diagnostics.add(LoaderDiagnostic.of(
                        LoaderDiagnosticCode.INVALID_TREE,
                        path,
                        "parent entry is missing: " + parent));
            }
        }
        if (!diagnostics.isEmpty()) {
            return new PreparedTree(Map.of());
        }

        preflight(raw.keySet(), diagnostics);
        if (!diagnostics.isEmpty()) {
            return new PreparedTree(Map.of());
        }

        Map<FactoryRef, ResolvedComponentDefinition> definitions = new LinkedHashMap<>();
        for (PreparedEntry entry : raw.values()) {
            if (definitions.containsKey(entry.ref())) {
                continue;
            }
            try {
                Optional<ResolvedComponentDefinition> definition = resolver.resolve(entry.ref());
                if (definition.isPresent()) {
                    definitions.put(entry.ref(), definition.get());
                } else {
                    diagnostics.add(LoaderDiagnostic.of(
                            LoaderDiagnosticCode.RESOLUTION_FAILED,
                            entry.path(),
                            "resolver returned no implementation"));
                }
            } catch (RuntimeException error) {
                diagnostics.add(LoaderDiagnostic.of(
                        LoaderDiagnosticCode.RESOLUTION_FAILED,
                        entry.path(),
                        safeError(error)));
            }
        }
        if (!diagnostics.isEmpty()) {
            return new PreparedTree(Map.of());
        }

        Map<String, PreparedEntry> prepared = new LinkedHashMap<>();
        for (PreparedEntry candidate : raw.values()) {
            ResolvedComponentDefinition definition = definitions.get(candidate.ref());
            Object config;
            try {
                config = definition.normalizeConfig(candidate.config());
            } catch (Exception error) {
                diagnostics.add(LoaderDiagnostic.of(
                        LoaderDiagnosticCode.CONFIG_INVALID,
                        candidate.path(),
                        safeError(error)));
                continue;
            }
            prepared.put(candidate.path(), new PreparedEntry(
                    candidate.path(),
                    candidate.name(),
                    candidate.ref(),
                    definition,
                    config));
        }
        return new PreparedTree(prepared);
    }

    private void preflight(
            Set<String> paths,
            List<LoaderDiagnostic> diagnostics) {
        RuntimeSnapshot snapshot = runtime.snapshot();
        RuntimeSnapshot.ContextSnapshot base = snapshot.contexts().stream()
                .filter(context -> context.contextId().equals(baseContext.contextId()))
                .findFirst()
                .orElse(null);
        if (base == null) {
            diagnostics.add(LoaderDiagnostic.of(
                    LoaderDiagnosticCode.BASE_UNAVAILABLE,
                    "",
                    "base context does not belong to the runtime"));
            return;
        }
        if (base.state() != ContextState.ACTIVE) {
            diagnostics.add(LoaderDiagnostic.of(
                    LoaderDiagnosticCode.BASE_UNAVAILABLE,
                    "",
                    "base context state is " + base.state()));
            return;
        }

        Map<String, RuntimeSnapshot.ContextSnapshot> byPath = new LinkedHashMap<>();
        for (RuntimeSnapshot.ContextSnapshot context : snapshot.contexts()) {
            byPath.put(context.canonicalPath(), context);
        }
        Map<String, RuntimeSnapshot.ComponentSnapshot> mounts = new LinkedHashMap<>();
        for (RuntimeSnapshot.ComponentSnapshot component : snapshot.components()) {
            mounts.put(component.contextId() + "/" + component.mountId(), component);
        }
        String baseCanonical = base.canonicalPath();
        for (String path : paths) {
            String canonical = canonical(baseCanonical, path);
            RuntimeSnapshot.ContextSnapshot existing = byPath.get(canonical);
            ContextHandle local = contexts.get(path);
            if (existing != null && (local == null || !existing.contextId().equals(local.contextId()))) {
                diagnostics.add(LoaderDiagnostic.of(
                        LoaderDiagnosticCode.CONTEXT_CONFLICT,
                        path,
                        "canonical context already belongs to another owner: " + canonical));
                continue;
            }
            if (local != null && existing == null) {
                diagnostics.add(LoaderDiagnostic.of(
                        LoaderDiagnosticCode.CONTEXT_CONFLICT,
                        path,
                        "managed context is no longer present in the runtime"));
                continue;
            }

            ManagedEntry entry = current.get(path);
            RuntimeSnapshot.ComponentSnapshot mounted = existing == null
                    ? null
                    : mounts.get(existing.contextId() + "/" + path);
            if (mounted != null
                    && (entry == null || !mounted.handleId().equals(entry.handle().handleId()))) {
                diagnostics.add(LoaderDiagnostic.of(
                        LoaderDiagnosticCode.CONTEXT_CONFLICT,
                        path,
                        "mount id is already occupied by another component"));
            }
        }
    }

    private void pruneExternallyDisposed() {
        RuntimeSnapshot snapshot = runtime.snapshot();
        Set<String> liveContextIds = snapshot.contexts().stream()
                .filter(context -> context.state() == ContextState.ACTIVE
                        || context.state() == ContextState.FAILED)
                .map(RuntimeSnapshot.ContextSnapshot::contextId)
                .collect(LinkedHashSet::new, LinkedHashSet::add, LinkedHashSet::addAll);
        Set<String> liveHandleIds = snapshot.components().stream()
                .filter(component -> component.state() != ComponentState.DISPOSED)
                .map(RuntimeSnapshot.ComponentSnapshot::handleId)
                .collect(LinkedHashSet::new, LinkedHashSet::add, LinkedHashSet::addAll);

        contexts.keySet().removeIf(path -> !liveContextIds.contains(contexts.get(path).contextId()));
        current.keySet().removeIf(path -> {
            ManagedEntry entry = current.get(path);
            return !liveHandleIds.contains(entry.handle().handleId());
        });
        publish(latestDiagnostics);
    }

    private boolean settleFailedDesiredContexts(
            PreparedTree desired,
            List<LoaderDiagnostic> diagnostics) {
        for (String path : desired.paths()) {
            if (contexts.get(path) != null
                    && contexts.get(path).state() == ContextState.FAILED) {
                if (!disposeContext(path, diagnostics)) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean removeMissing(
            PreparedTree desired,
            List<ReconcileResult.Change> changes,
            List<LoaderDiagnostic> diagnostics) {
        List<String> missing = contexts.keySet().stream()
                .filter(path -> !desired.paths().contains(path))
                .filter(this::isRemovalRoot)
                .sorted()
                .toList();
        for (String path : missing) {
            if (disposeContext(path, diagnostics)) {
                changes.add(ReconcileResult.Change.of(
                        ReconcileResult.ChangeType.REMOVED,
                        path));
            } else {
                changes.add(ReconcileResult.Change.of(
                        ReconcileResult.ChangeType.BLOCKED,
                        path));
                return false;
            }
        }
        return true;
    }

    private boolean isRemovalRoot(String path) {
        String parent = parentPath(path);
        return parent.isEmpty()
                || (contexts.containsKey(parent) && !contexts.get(parent).state().equals(ContextState.DISPOSED)
                && !isPathDesiredRemovalCandidate(parent));
    }

    private boolean isPathDesiredRemovalCandidate(String parent) {
        return !current.containsKey(parent) && !contexts.containsKey(parent);
    }

    private boolean replaceChangedFactories(
            PreparedTree desired,
            List<ReconcileResult.Change> changes,
            List<LoaderDiagnostic> diagnostics) {
        for (String path : desired.paths()) {
            ManagedEntry old = current.get(path);
            PreparedEntry next = desired.entry(path);
            if (old == null || old.definition().identity().equals(next.definition().identity())) {
                continue;
            }
            if (!replaceEntry(old, next, diagnostics)) {
                changes.add(ReconcileResult.Change.of(
                        ReconcileResult.ChangeType.BLOCKED,
                        path));
                return false;
            }
            changes.add(ReconcileResult.Change.of(
                    ReconcileResult.ChangeType.REPLACED,
                    path));
        }
        return true;
    }

    private boolean replaceEntry(
            ManagedEntry old,
            PreparedEntry next,
            List<LoaderDiagnostic> diagnostics) {
        if (!disposeHandle(old.path(), old.handle(), diagnostics)) {
            return false;
        }
        current.remove(old.path());

        MountAttempt mounted = mountOne(next, old.context(), diagnostics);
        if (mounted != null) {
            register(mounted);
            if (mounted.handle().state() == ComponentState.FAILED) {
                diagnostics.add(LoaderDiagnostic.of(
                        LoaderDiagnosticCode.ACTIVATION_FAILED,
                        next.path(),
                        "replacement implementation failed to activate"));
            }
            return true;
        }

        PreparedEntry fallback = new PreparedEntry(
                old.path(),
                old.name(),
                null,
                old.definition(),
                old.config());
        List<LoaderDiagnostic> compensation = new ArrayList<>();
        MountAttempt restored = mountOne(fallback, old.context(), compensation);
        if (restored != null) {
            register(restored);
            diagnostics.add(LoaderDiagnostic.of(
                    LoaderDiagnosticCode.REPLACEMENT_BLOCKED,
                    old.path(),
                    "replacement was rejected and the previous implementation was restored"));
            return false;
        }
        diagnostics.add(LoaderDiagnostic.of(
                LoaderDiagnosticCode.COMPENSATION_FAILED,
                old.path(),
                "replacement was rejected and compensation also failed"));
        diagnostics.addAll(compensation);
        return false;
    }

    private boolean addMissing(
            PreparedTree desired,
            List<ReconcileResult.Change> changes,
            List<LoaderDiagnostic> diagnostics) {
        List<String> missing = desired.paths().stream()
                .filter(path -> !current.containsKey(path))
                .toList();
        if (missing.isEmpty()) {
            return true;
        }

        Map<String, ContextHandle> created = new LinkedHashMap<>();
        if (!createMissingContexts(missing, created, diagnostics)) {
            return false;
        }

        List<ManagedEntry> mounted = new ArrayList<>();
        try {
            for (String path : missing) {
                PreparedEntry entry = desired.entry(path);
                ContextHandle context = contexts.get(path);
                MountAttempt attempt = mountOne(entry, context, diagnostics);
                if (attempt == null) {
                    rollbackAdd(mounted, created, diagnostics);
                    return false;
                }
                ManagedEntry managed = register(attempt);
                mounted.add(managed);
                changes.add(ReconcileResult.Change.of(
                        ReconcileResult.ChangeType.MOUNTED,
                        path));
            }
            return true;
        } catch (RuntimeException error) {
            diagnostics.add(LoaderDiagnostic.of(
                    LoaderDiagnosticCode.STRUCTURE_REJECTED,
                    "",
                    safeError(error)));
            rollbackAdd(mounted, created, diagnostics);
            return false;
        } finally {
            publish(latestDiagnostics);
        }
    }

    private boolean createMissingContexts(
            List<String> missing,
            Map<String, ContextHandle> created,
            List<LoaderDiagnostic> diagnostics) {
        Map<String, ContextHandle> provisional = new LinkedHashMap<>();
        MutationResult<Void> result = runtime.mutate(mutation -> {
            for (String path : missing) {
                ContextHandle reusable = contexts.get(path);
                if (reusable != null) {
                    if (reusable.state() != ContextState.ACTIVE) {
                        throw new IllegalStateException("managed context is not active: " + path);
                    }
                    provisional.put(path, reusable);
                    continue;
                }
                ContextHandle parent = parentContext(path, provisional);
                ContextHandle child = mutation.childContext(parent, lastSegment(path));
                provisional.put(path, child);
                created.put(path, child);
            }
            return null;
        });
        if (!result.committed()) {
            addCoreDiagnostics(LoaderDiagnosticCode.STRUCTURE_REJECTED, missing.getFirst(), diagnostics,
                    result.diagnostics());
            created.clear();
            return false;
        }
        if (!await(result.settlement())) {
            diagnostics.add(LoaderDiagnostic.of(
                    LoaderDiagnosticCode.STRUCTURE_REJECTED,
                    missing.getFirst(),
                    "one or more contexts failed to settle"));
            List<LoaderDiagnostic> compensation = new ArrayList<>();
            rollbackContexts(created, compensation);
            diagnostics.addAll(compensation);
            created.clear();
            return false;
        }
        contexts.putAll(provisional);
        publish(latestDiagnostics);
        return true;
    }

    private ContextHandle parentContext(
            String path,
            Map<String, ContextHandle> provisional) {
        String parentPath = parentPath(path);
        if (parentPath.isEmpty()) {
            return baseContext;
        }
        ContextHandle provisionalParent = provisional.get(parentPath);
        if (provisionalParent != null) {
            return provisionalParent;
        }
        ContextHandle parent = contexts.get(parentPath);
        if (parent == null || parent.state() != ContextState.ACTIVE) {
            throw new IllegalStateException("parent context is unavailable: " + parentPath);
        }
        return parent;
    }

    private MountAttempt mountOne(
            PreparedEntry entry,
            ContextHandle context,
            List<LoaderDiagnostic> diagnostics) {
        if (context == null || context.state() != ContextState.ACTIVE) {
            diagnostics.add(LoaderDiagnostic.of(
                    LoaderDiagnosticCode.STRUCTURE_REJECTED,
                    entry.path(),
                    "mount context is not active"));
            return null;
        }
        try {
            ControlledMountContext mountContext = new AllocatedMountContext(
                    runtime, context, entry.path());
            ComponentHandle<?> handle = entry.definition()
                    .mountStrategy()
                    .mount(mountContext, entry.config())
                    .toCompletableFuture()
                    .get();
            if (handle == null) {
                diagnostics.add(LoaderDiagnostic.of(
                        LoaderDiagnosticCode.STRUCTURE_REJECTED,
                        entry.path(),
                        "controlled mount returned no component handle"));
                return null;
            }
            if (!context.contextId().equals(handle.contextId())
                    || !entry.path().equals(handle.mountId())) {
                diagnostics.add(LoaderDiagnostic.of(
                        LoaderDiagnosticCode.STRUCTURE_REJECTED,
                        entry.path(),
                        "controlled mount returned a handle outside its allocated slot"));
                disposeHandle(entry.path(), handle, diagnostics);
                return null;
            }
            handle.whenSettled().toCompletableFuture().get();
            return new MountAttempt(
                    entry.path(),
                    entry.name(),
                    context,
                    handle,
                    entry.definition(),
                    entry.config());
        } catch (ControlledMountException error) {
            addCoreDiagnostics(
                    LoaderDiagnosticCode.STRUCTURE_REJECTED,
                    entry.path(),
                    diagnostics,
                    error.diagnostics());
        } catch (Exception error) {
            diagnostics.add(LoaderDiagnostic.of(
                    LoaderDiagnosticCode.STRUCTURE_REJECTED,
                    entry.path(),
                    safeError(error)));
        }
        return null;
    }

    private void rollbackAdd(
            List<ManagedEntry> mounted,
            Map<String, ContextHandle> created,
            List<LoaderDiagnostic> diagnostics) {
        List<LoaderDiagnostic> compensation = new ArrayList<>();
        for (int index = mounted.size() - 1; index >= 0; index--) {
            ManagedEntry entry = mounted.get(index);
            disposeHandle(entry.path(), entry.handle(), compensation);
        }
        rollbackContexts(created, compensation);
        if (!compensation.isEmpty()) {
            diagnostics.add(LoaderDiagnostic.of(
                    LoaderDiagnosticCode.COMPENSATION_FAILED,
                    "",
                    "add rollback left unresolved resources"));
            diagnostics.addAll(compensation);
        }
        for (ManagedEntry entry : mounted) {
            current.remove(entry.path());
        }
        for (String path : created.keySet()) {
            contexts.remove(path);
        }
        publish(latestDiagnostics);
    }

    private void rollbackContexts(
            Map<String, ContextHandle> created,
            List<LoaderDiagnostic> compensation) {
        List<String> roots = created.keySet().stream()
                .filter(path -> {
                    String parent = parentPath(path);
                    return parent.isEmpty() || !created.containsKey(parent);
                })
                .sorted(Comparator.reverseOrder())
                .toList();
        for (String path : roots) {
            ContextHandle context = created.get(path);
            if (context != null) {
                disposeHandlelessContext(path, context, compensation);
            }
        }
    }

    private void updateConfigs(
            PreparedTree desired,
            List<ReconcileResult.Change> changes,
            List<LoaderDiagnostic> diagnostics) {
        for (String path : desired.paths()) {
            ManagedEntry managed = current.get(path);
            PreparedEntry desiredEntry = desired.entry(path);
            if (managed == null
                    || !managed.definition().identity().equals(
                            desiredEntry.definition().identity())) {
                continue;
            }
            if (Objects.equals(managed.config(), desiredEntry.config())) {
                continue;
            }

            ManagedEntry latest = managed.withDefinitionAndConfig(
                    desiredEntry.definition(),
                    desiredEntry.config());
            current.put(path, latest);
            if (managed.handle().state() == ComponentState.FAILED) {
                diagnostics.add(LoaderDiagnostic.of(
                        LoaderDiagnosticCode.ACTIVATION_FAILED,
                        path,
                        "configuration is stored but the failed implementation was not retried"));
                continue;
            }
            try {
                ComponentState state = desiredEntry.definition()
                        .reconfigureStrategy()
                        .reconfigure(managed.handle(), desiredEntry.config())
                        .toCompletableFuture()
                        .get();
                changes.add(ReconcileResult.Change.of(
                        ReconcileResult.ChangeType.UPDATED,
                        path));
                if (state == ComponentState.FAILED) {
                    diagnostics.add(LoaderDiagnostic.of(
                            LoaderDiagnosticCode.ACTIVATION_FAILED,
                            path,
                            "implementation failed with the latest configuration"));
                }
            } catch (Exception error) {
                diagnostics.add(LoaderDiagnostic.of(
                        LoaderDiagnosticCode.ACTIVATION_FAILED,
                        path,
                        "configuration update was rejected: " + safeError(error)));
            }
        }
    }

    private void addFailedDiagnostics(
            PreparedTree desired,
            List<LoaderDiagnostic> diagnostics) {
        for (String path : desired.paths()) {
            ManagedEntry entry = current.get(path);
            if (entry != null && entry.handle().state() == ComponentState.FAILED) {
                diagnostics.add(LoaderDiagnostic.of(
                        LoaderDiagnosticCode.ACTIVATION_FAILED,
                        path,
                        "desired component is FAILED; call retry(path) explicitly"));
            }
        }
    }

    private boolean disposeHandle(
            String path,
            ComponentHandle<?> handle,
            List<LoaderDiagnostic> diagnostics) {
        if (handle.state() == ComponentState.DISPOSED) {
            return true;
        }
        MutationResult<Void> result = runtime.mutate(mutation -> {
            mutation.dispose(handle);
            return null;
        });
        if (!result.committed()) {
            if (runtimeOwnsDisposalNow()
                    && awaitRuntimeOwnedHandleDisposal(handle)) {
                return true;
            }
            addCoreDiagnostics(LoaderDiagnosticCode.TEARDOWN_FAILED, path, diagnostics,
                    result.diagnostics());
            return false;
        }
        try {
            result.settlement().toCompletableFuture().get();
        } catch (Exception error) {
            if (runtimeOwnsDisposalNow()
                    && awaitRuntimeOwnedHandleDisposal(handle)) {
                return true;
            }
            diagnostics.add(LoaderDiagnostic.of(
                    LoaderDiagnosticCode.TEARDOWN_FAILED,
                    path,
                    safeError(error)));
            return false;
        }
        if (handle.state() != ComponentState.DISPOSED) {
            diagnostics.add(LoaderDiagnostic.of(
                    LoaderDiagnosticCode.TEARDOWN_FAILED,
                    path,
                    "component cleanup did not reach DISPOSED"));
            return false;
        }
        return true;
    }

    private boolean disposeContext(
            String path,
            List<LoaderDiagnostic> diagnostics) {
        ContextHandle context = path.isEmpty()
                ? baseContext
                : contexts.get(path);
        return disposeHandlelessContext(path, context, diagnostics);
    }

    private boolean disposeHandlelessContext(
            String path,
            ContextHandle context,
            List<LoaderDiagnostic> diagnostics) {
        if (context == null) {
            return true;
        }
        if (context.state() == ContextState.DISPOSED) {
            prune(path);
            publish(latestDiagnostics);
            return true;
        }
        MutationResult<Void> result = runtime.mutate(mutation -> {
            mutation.dispose(context);
            return null;
        });
        if (!result.committed()) {
            if (runtimeOwnsDisposalNow()
                    && awaitRuntimeOwnedContextDisposal(context)) {
                prune(path);
                publish(latestDiagnostics);
                return true;
            }
            addCoreDiagnostics(LoaderDiagnosticCode.TEARDOWN_FAILED, path, diagnostics,
                    result.diagnostics());
            return false;
        }
        try {
            result.settlement().toCompletableFuture().get();
        } catch (Exception error) {
            if (runtimeOwnsDisposalNow()
                    && awaitRuntimeOwnedContextDisposal(context)) {
                prune(path);
                publish(latestDiagnostics);
                return true;
            }
            diagnostics.add(LoaderDiagnostic.of(
                    LoaderDiagnosticCode.TEARDOWN_FAILED,
                    path,
                    safeError(error)));
            return false;
        }
        if (context.state() != ContextState.DISPOSED) {
            diagnostics.add(LoaderDiagnostic.of(
                    LoaderDiagnosticCode.TEARDOWN_FAILED,
                    path,
                    "context cleanup reached " + context.state()));
            return false;
        }
        prune(path);
        publish(latestDiagnostics);
        return true;
    }

    private void prune(String rootPath) {
        if (rootPath.isEmpty()) {
            current.clear();
            contexts.clear();
            return;
        }
        String prefix = rootPath + "/";
        current.keySet().removeIf(path -> path.equals(rootPath) || path.startsWith(prefix));
        contexts.keySet().removeIf(path -> path.equals(rootPath) || path.startsWith(prefix));
    }

    private List<String> topLevelPaths() {
        return contexts.keySet().stream()
                .filter(path -> parentPath(path).isEmpty())
                .sorted()
                .toList();
    }

    private ManagedEntry register(MountAttempt entry) {
        contexts.put(entry.path(), entry.context());
        ManagedEntry managed = new ManagedEntry(
                entry.path(),
                entry.name(),
                entry.context(),
                entry.handle(),
                entry.definition(),
                entry.config());
        current.put(entry.path(), managed);
        publish(latestDiagnostics);
        return managed;
    }

    private ReconcileResult finish(
            boolean proposed,
            List<ReconcileResult.Change> changes,
            List<LoaderDiagnostic> diagnostics) {
        List<LoaderDiagnostic> copied = List.copyOf(diagnostics).stream().sorted().toList();
        latestDiagnostics = copied;
        publish(copied);
        return new ReconcileResult(proposed && copied.isEmpty(), changes, copied);
    }

    private void publish(List<LoaderDiagnostic> diagnostics) {
        view = new LoaderView(
                closed,
                Map.copyOf(current),
                Map.copyOf(contexts));
        latestDiagnostics = List.copyOf(diagnostics).stream().sorted().toList();
    }

    private boolean await(CompletionStage<Void> settlement) {
        try {
            settlement.toCompletableFuture().get();
            return true;
        } catch (Exception error) {
            return false;
        }
    }

    private void addCoreDiagnostics(
            LoaderDiagnosticCode fallback,
            String path,
            List<LoaderDiagnostic> diagnostics,
            List<RuntimeDiagnostic> values) {
        if (values.isEmpty()) {
            diagnostics.add(LoaderDiagnostic.of(fallback, path, "runtime mutation was rejected"));
            return;
        }
        for (RuntimeDiagnostic value : values) {
            diagnostics.add(LoaderDiagnostic.of(
                    mapCode(value.code(), fallback),
                    path,
                    value.message()));
        }
    }

    private LoaderDiagnosticCode mapCode(
            DiagnosticCode code,
            LoaderDiagnosticCode fallback) {
        return switch (code) {
            case ACTIVATION_FAILED -> LoaderDiagnosticCode.ACTIVATION_FAILED;
            case CLEANUP_FAILED, ROLLBACK_FAILED -> LoaderDiagnosticCode.TEARDOWN_FAILED;
            case INVALID_CONFIG, MISSING_CAPABILITY, CAPABILITY_SLOT_OCCUPIED,
                    CAPABILITY_TYPE_CONFLICT, BINDING_CYCLE, NON_CONVERGENT_RECONCILE,
                    INVALID_LIFECYCLE_OPERATION, INVALID_MOUNT_ID -> LoaderDiagnosticCode.STRUCTURE_REJECTED;
        };
    }

    private void collectEntries(
            List<ComponentEntry> entries,
            String parentPath,
            Map<String, PreparedEntry> flattened,
            List<LoaderDiagnostic> diagnostics) {
        for (ComponentEntry entry : entries) {
            String path;
            try {
                path = normalizePath(entry.path(), parentPath);
            } catch (RuntimeException error) {
                diagnostics.add(LoaderDiagnostic.of(
                        LoaderDiagnosticCode.INVALID_TREE,
                        entry.path(),
                        safeError(error)));
                continue;
            }
            if (path.isEmpty()) {
                diagnostics.add(LoaderDiagnostic.of(
                        LoaderDiagnosticCode.INVALID_TREE,
                        parentPath,
                        "entry path is empty"));
                continue;
            }
            if (!parentPath.isEmpty() && !path.startsWith(parentPath + "/")) {
                diagnostics.add(LoaderDiagnostic.of(
                        LoaderDiagnosticCode.INVALID_TREE,
                        path,
                        "child path is outside parent: " + parentPath));
                continue;
            }
            if (flattened.containsKey(path)) {
                diagnostics.add(LoaderDiagnostic.of(
                        LoaderDiagnosticCode.INVALID_TREE,
                        path,
                        "duplicate normalized entry path"));
                continue;
            }
            flattened.put(path, new PreparedEntry(
                    path,
                    lastSegment(path),
                    entry.factoryRef(),
                    null,
                    entry.config() == null ? NoConfig.INSTANCE : entry.config()));
            collectEntries(entry.children(), path, flattened, diagnostics);
        }
    }

    private static String canonical(String baseCanonical, String path) {
        if (baseCanonical.endsWith("/")) {
            return baseCanonical + path;
        }
        return baseCanonical + "/" + path;
    }

    private static String parentPath(String path) {
        int index = path.lastIndexOf('/');
        return index < 0 ? "" : path.substring(0, index);
    }

    private static String lastSegment(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private static String normalizePath(String raw, String parentPath) {
        if (raw == null) {
            return "";
        }
        String value = raw.trim().replace('\\', '/');
        if (value.isEmpty()) {
            return "";
        }
        boolean absolute = value.startsWith("/");
        String[] parts = value.split("/");
        List<String> segments = new ArrayList<>();
        for (String part : parts) {
            String segment = part == null ? "" : part.trim();
            if (segment.isEmpty() || segment.equals(".")) {
                continue;
            }
            if (segment.equals("..")) {
                throw new IllegalArgumentException("relative parent segments are not supported");
            }
            segments.add(segment);
        }
        if (segments.isEmpty()) {
            return "";
        }
        String normalized = String.join("/", segments);
        if (!absolute && !parentPath.isEmpty() && !normalized.contains("/")) {
            return parentPath + "/" + normalized;
        }
        return normalized;
    }

    private static String safeError(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank()
                ? current.getClass().getName()
                : message;
    }

    private record PreparedEntry(
            String path,
            String name,
            FactoryRef ref,
            ResolvedComponentDefinition definition,
            Object config) {
    }

    private record PreparedTree(Map<String, PreparedEntry> entries) {

        List<String> paths() {
            return entries.keySet().stream()
                    .sorted(Comparator.comparingInt((String path) -> path.split("/").length)
                            .thenComparing(Function.identity()))
                    .toList();
        }

        PreparedEntry entry(String path) {
            return entries.get(path);
        }
    }

    private record MountAttempt(
            String path,
            String name,
            ContextHandle context,
            ComponentHandle<?> handle,
            ResolvedComponentDefinition definition,
            Object config) {
    }

    private static final class ManagedEntry {
        private final String path;
        private final String name;
        private final ContextHandle context;
        private final ComponentHandle<?> handle;
        private final ResolvedComponentDefinition definition;
        private final Object config;

        private ManagedEntry(
                String path,
                String name,
                ContextHandle context,
                ComponentHandle<?> handle,
                ResolvedComponentDefinition definition,
                Object config) {
            this.path = path;
            this.name = name;
            this.context = context;
            this.handle = handle;
            this.definition = definition;
            this.config = config;
        }

        private String path() {
            return path;
        }

        private String name() {
            return name;
        }

        private ContextHandle context() {
            return context;
        }

        private ComponentHandle<?> handle() {
            return handle;
        }

        private ResolvedComponentDefinition definition() {
            return definition;
        }

        private Object config() {
            return config;
        }

        private ManagedEntry withHandle(ComponentHandle<?> replacement) {
            return new ManagedEntry(path, name, context, replacement, definition, config);
        }

        private ManagedEntry withDefinitionAndConfig(
                ResolvedComponentDefinition replacementDefinition,
                Object replacementConfig) {
            return new ManagedEntry(path, name, context, handle, replacementDefinition,
                    replacementConfig);
        }
    }

    private record LoaderView(
            boolean closed,
            Map<String, ManagedEntry> entries,
            Map<String, ContextHandle> contexts) {

        private static final LoaderView EMPTY = new LoaderView(false, Map.of(), Map.of());
    }
}
