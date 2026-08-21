package io.knotra.loader;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;

import io.knotra.ComponentFactory;
import io.knotra.ComponentHandle;
import io.knotra.ContextHandle;
import io.knotra.DiagnosticCode;
import io.knotra.KnotraRuntime;
import io.knotra.MountOptions;
import io.knotra.RuntimeDiagnostic;
import io.knotra.TransactionRejectedException;

/**
 * {@link ControlledMountContext} 的 Loader 内部实现：绑定一次受控挂载所需的
 * 运行时、分配的 Context 与挂载 ID。
 *
 * <p>该实现维护受控边界的两条硬约束：挂载槽位单次使用（原子标记，重复挂载
 * 直接拒绝），以及分配的 Context 必须处于 ACTIVE。挂载本身通过宿主事务提交
 * 给 Core，事务被拒绝时把核心诊断包装为 {@link ControlledMountException}
 * 传回策略，保留结构化原因。</p>
 */
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
    public <C> CompletionStage<ComponentHandle<C>> mountAsync(
            ComponentFactory<C> factory,
            C config,
            MountOptions options) {
        Objects.requireNonNull(factory, "factory");
        if (!used.compareAndSet(false, true)) {
            return CompletableFuture.failedFuture(new ControlledMountException(java.util.List.of(
                    new RuntimeDiagnostic(
                            DiagnosticCode.INVALID_MOUNT_ID,
                            mountId,
                            "controlled mount context was already used"))));
        }
        if (context.state() != io.knotra.ContextState.ACTIVE) {
            return CompletableFuture.failedFuture(new ControlledMountException(java.util.List.of(
                    new RuntimeDiagnostic(
                            DiagnosticCode.INVALID_MOUNT_ID,
                            mountId,
                            "allocated mount context is not active"))));
        }
        try {
            return CompletableFuture.completedFuture(runtime.transact(transaction ->
                    transaction.mount(context, mountId, factory, config, options)).value());
        } catch (TransactionRejectedException rejection) {
            return CompletableFuture.failedFuture(
                    new ControlledMountException(rejection.diagnostics()));
        }
    }
}
