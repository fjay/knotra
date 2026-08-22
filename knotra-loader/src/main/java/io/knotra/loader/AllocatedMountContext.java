package io.knotra.loader;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import io.knotra.ComponentFactory;
import io.knotra.ComponentGoal;
import io.knotra.ComponentState;
import io.knotra.ConfiguredMountHandle;
import io.knotra.ContextHandle;
import io.knotra.ContextState;
import io.knotra.DiagnosticCode;
import io.knotra.KnotraRuntime;
import io.knotra.MountHandle;
import io.knotra.MountOptions;
import io.knotra.NoConfig;
import io.knotra.RuntimeDiagnostic;
import io.knotra.SettlementReport;
import io.knotra.TransactionReceipt;
import io.knotra.TransactionRejectedException;

/**
 * {@link ControlledMountContext} 的 Loader 内部实现：绑定一次受控挂载所需的
 * 运行时、分配的 Context 与挂载 ID。
 *
 * <p>该实现维护受控边界的两条硬约束：挂载槽位单次使用（原子标记，重复挂载
 * 直接拒绝），以及分配的 Context 必须处于 ACTIVE。挂载本身通过宿主事务提交
 * 给 Core，并等待 settlement 报告；报告中的 FAILED 挂载会以结构化诊断传回
 * Loader，WAITING 则保持为挂载句柄的当前状态。</p>
 *
 * <p>事务提交后句柄绝不会被静默丢弃：settlement 超时或异常结算时，先对已提交
 * 挂载发起释放并有界等待；释放收敛则以失败收尾且不保留句柄，释放无法收敛则
 * 把句柄保留在 {@link #committedHandle()} 中交给 Loader 记账，避免 runtime 中
 * 出现无人跟踪的 STARTING 挂载。</p>
 */
final class AllocatedMountContext implements ControlledMountContext {
    static final Duration DEFAULT_SETTLEMENT_TIMEOUT = Duration.ofSeconds(30);
    static final Duration DEFAULT_RECOVERY_TIMEOUT = Duration.ofSeconds(30);

    private final KnotraRuntime runtime;
    private final ContextHandle context;
    private final String mountId;
    private final Duration settlementTimeout;
    private final Duration recoveryTimeout;
    private final AtomicBoolean used = new AtomicBoolean();
    private volatile SettlementReport lastReport;
    private volatile MountHandle committed;

    AllocatedMountContext(
            KnotraRuntime runtime,
            ContextHandle context,
            String mountId) {
        this(runtime, context, mountId, DEFAULT_SETTLEMENT_TIMEOUT, DEFAULT_RECOVERY_TIMEOUT);
    }

    AllocatedMountContext(
            KnotraRuntime runtime,
            ContextHandle context,
            String mountId,
            Duration settlementTimeout,
            Duration recoveryTimeout) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.context = Objects.requireNonNull(context, "context");
        this.mountId = Objects.requireNonNull(mountId, "mountId");
        this.settlementTimeout = requirePositive(settlementTimeout, "settlementTimeout");
        this.recoveryTimeout = requirePositive(recoveryTimeout, "recoveryTimeout");
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    @Override
    public ContextHandle context() {
        return context;
    }

    @Override
    public String mountId() {
        return mountId;
    }

    SettlementReport lastReport() {
        return lastReport;
    }

    /**
     * 已提交但 settlement 未收敛、且有界释放未完成的挂载句柄。
     * 调用方必须把它纳入记账；该字段只在无法可靠释放时非空。
     */
    MountHandle committedHandle() {
        return committed;
    }

    @Override
    public CompletionStage<MountHandle> mountAsync(
            ComponentFactory<NoConfig> factory,
            MountOptions options) {
        Objects.requireNonNull(factory, "factory");
        if (!used.compareAndSet(false, true)) {
            return CompletableFuture.failedFuture(rejected(
                    "controlled mount context was already used"));
        }
        if (context.state() != ContextState.ACTIVE) {
            return CompletableFuture.failedFuture(rejected(
                    "allocated mount context is not active"));
        }
        TransactionReceipt<MountHandle> receipt;
        try {
            receipt = runtime.advanced().transact(transaction ->
                    transaction.mount(context, mountId, factory, options));
        } catch (TransactionRejectedException rejection) {
            return CompletableFuture.failedFuture(
                    new ControlledMountException(rejection.diagnostics()));
        }
        return awaitAsFuture(receipt);
    }

    @Override
    public <C> CompletionStage<ConfiguredMountHandle<C>> mountAsync(
            ComponentFactory<C> factory,
            C config,
            MountOptions options) {
        Objects.requireNonNull(factory, "factory");
        Objects.requireNonNull(config, "config");
        if (!used.compareAndSet(false, true)) {
            return CompletableFuture.failedFuture(rejected(
                    "controlled mount context was already used"));
        }
        if (context.state() != ContextState.ACTIVE) {
            return CompletableFuture.failedFuture(rejected(
                    "allocated mount context is not active"));
        }
        TransactionReceipt<ConfiguredMountHandle<C>> receipt;
        try {
            receipt = runtime.advanced().transact(transaction ->
                    transaction.mount(context, mountId, factory, config, options));
        } catch (TransactionRejectedException rejection) {
            return CompletableFuture.failedFuture(
                    new ControlledMountException(rejection.diagnostics()));
        }
        return awaitAsFuture(receipt);
    }

    private ControlledMountException rejected(String message) {
        return new ControlledMountException(List.of(new RuntimeDiagnostic(
                DiagnosticCode.INVALID_MOUNT_ID,
                mountId,
                message)));
    }

    private <H extends MountHandle> CompletionStage<H> awaitAsFuture(
            TransactionReceipt<H> receipt) {
        try {
            return CompletableFuture.completedFuture(awaitMount(receipt));
        } catch (RuntimeException error) {
            return CompletableFuture.failedFuture(error);
        }
    }
    private <H extends MountHandle> H awaitMount(TransactionReceipt<H> receipt) {
        SettlementReport report;
        try {
            report = receipt.awaitSettled(settlementTimeout);
        } catch (RuntimeException error) {
            throw unsettled(receipt.value(), error);
        }
        lastReport = report;
        return receipt.value();
    }

    /**
     * Settlement 未按时收敛时不能丢弃已提交句柄：先请求释放并有界等待；
     * 释放失败时保留句柄供 Loader 记账，同时以结构化失败返回，绝不谎报已补偿。
     */
    private ControlledMountException unsettled(MountHandle handle, Throwable reason) {
        String detail = LoaderErrors.safe(reason);
        if (disposeCommitted(handle)) {
            return new ControlledMountException(List.of(new RuntimeDiagnostic(
                    DiagnosticCode.INVALID_LIFECYCLE_OPERATION,
                    mountId,
                    "mount settlement did not converge (" + detail
                            + "); the committed mount was disposed during recovery")));
        }
        committed = handle;
        return new ControlledMountException(List.of(new RuntimeDiagnostic(
                DiagnosticCode.INVALID_LIFECYCLE_OPERATION,
                mountId,
                "mount settlement did not converge (" + detail
                        + "); the committed mount could not be disposed"
                        + " and is retained for loader bookkeeping")));
    }

    private boolean disposeCommitted(MountHandle handle) {
        try {
            ComponentState state = handle.state();
            if (state == ComponentState.DISPOSED) {
                return true;
            }
            CompletionStage<ComponentState> cleanup = state == ComponentState.FAILED
                    && handle.goal() == ComponentGoal.DISPOSED
                    ? handle.retryAsync()
                    : handle.disposeAsync();
            ComponentState settled = cleanup.toCompletableFuture()
                    .get(recoveryTimeout.toMillis(), TimeUnit.MILLISECONDS);
            return settled == ComponentState.DISPOSED;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception error) {
            return false;
        }
    }
}
