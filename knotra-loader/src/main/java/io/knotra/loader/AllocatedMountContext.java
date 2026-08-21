package io.knotra.loader;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;

import io.knotra.ComponentFactory;
import io.knotra.ComponentHandle;
import io.knotra.ContextHandle;
import io.knotra.MountOptions;
import io.knotra.KnotraRuntime;
import io.knotra.MutationResult;

final class AllocatedMountContext implements ControlledMountContext {

    private final KnotraRuntime runtime;
    private final ContextHandle context;
    private final String mountId;
    private final AtomicBoolean used = new AtomicBoolean();

    AllocatedMountContext(
            KnotraRuntime runtime,
            ContextHandle context,
            String mountId) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.context = Objects.requireNonNull(context, "context");
        this.mountId = Objects.requireNonNull(mountId, "mountId");
    }

    @Override
    public ContextHandle context() {
        return context;
    }

    @Override
    public String mountId() {
        return mountId;
    }

    @Override
    public <C> CompletionStage<ComponentHandle<C>> mount(
            ComponentFactory<C> factory,
            C config,
            MountOptions options) {
        Objects.requireNonNull(factory, "factory");
        if (!used.compareAndSet(false, true)) {
            return CompletableFuture.failedFuture(new ControlledMountException(java.util.List.of(
                    new io.knotra.RuntimeDiagnostic(
                            io.knotra.DiagnosticCode.INVALID_MOUNT_ID,
                            mountId,
                            "controlled mount context was already used"))));
        }
        if (context.state() != io.knotra.ContextState.ACTIVE) {
            return CompletableFuture.failedFuture(new ControlledMountException(java.util.List.of(
                    new io.knotra.RuntimeDiagnostic(
                            io.knotra.DiagnosticCode.INVALID_MOUNT_ID,
                            mountId,
                            "allocated mount context is not active"))));
        }
        MutationResult<ComponentHandle<C>> result = runtime.mutate(mutation ->
                mutation.mount(context, mountId, factory, config, options));
        if (!result.committed()) {
            return CompletableFuture.failedFuture(
                    new ControlledMountException(result.diagnostics()));
        }
        return CompletableFuture.completedFuture(result.value());
    }
}
