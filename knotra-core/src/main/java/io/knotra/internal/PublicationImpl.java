package io.knotra.internal;

import io.knotra.CapabilityKey;
import io.knotra.ContextHandle;
import io.knotra.Publication;
import io.knotra.PublicationChange;
import io.knotra.PublicationOperation;
import io.knotra.PublicationState;
import io.knotra.Settlement;
import io.knotra.TransactionRejectedException;
import io.knotra.RuntimeDiagnostic;
import io.knotra.DiagnosticCode;

import java.util.List;
import java.util.Objects;

/**
 * 稳定发布槽位的纯句柄。
 *
 * <p>句柄只保存 runtime、共享 {@link PublicationSlotTerminalRef}、key 与 context；槽位状态
 * 与 current 注册始终通过 runtime 对 published 的单次读取观察：active-only 视图命中即为
 * PUBLISHED，未命中读共享 ref 的终态数据。update/unpublish 以
 * (epoch, currentRegistrationId) 作为乐观期望提交，协调器内验证失败抛内部 Stale 异常，
 * 句柄在锁外重读后重试，因此并发操作按提交总序线性化：并发 update 全部成功、
 * update/unpublish 按先后、并发 unpublish 只有一个真实事务其余返回幂等终态结果。
 * 终态槽位不复活，同名重新发布由 runtime.publish 创建新槽位与新 ref，旧句柄永不观察新槽位。</p>
 */
final class PublicationImpl<T> implements Publication<T> {
    private final DefaultKnotraRuntime runtime;
    private final PublicationSlotTerminalRef slot;
    private final CapabilityKey<T> key;
    // 纯结果缓存：unpublish 的幂等终态返回；不参与事务，也绝不在锁内读写。
    private volatile PublicationChange<T> terminalUnpublishChange;
    private final ContextHandle context;

    PublicationImpl(
            DefaultKnotraRuntime runtime,
            PublicationSlotTerminalRef slot,
            CapabilityKey<T> key,
            ContextHandle context) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.slot = Objects.requireNonNull(slot, "slot");
        this.key = Objects.requireNonNull(key, "key");
        this.context = Objects.requireNonNull(context, "context");
    }

    static <T> PublicationChange<T> publish(
            DefaultKnotraRuntime runtime,
            ContextHandle context,
            CapabilityKey<T> key,
            T value) {
        if (!(context instanceof ContextHandleImpl internal)
                || internal.runtime != runtime) {
            throw new IllegalArgumentException(
                    "context handle does not belong to this runtime");
        }
        DefaultKnotraRuntime.PublicationServiceResult result =
                runtime.createOrUpdatePublication(internal, key, value);
        PublicationProvideOutcome outcome = result.outcome();
        PublicationImpl<T> publication =
                new PublicationImpl<>(runtime, outcome.terminalRef(), key, context);
        return publication.change(outcome.operation(), result.settlement());
    }

    @Override
    public CapabilityKey<T> key() {
        return key;
    }

    @Override
    public ContextHandle context() {
        return context;
    }

    @Override
    public PublicationState state() {
        return runtime.publicationSlotObservation(slot).state();
    }

    String slotId() {
        return slot.slotId;
    }

    PublicationSlotTerminalRef terminalRef() {
        return slot;
    }

    @Override
    public PublicationChange<T> update(T value) {
        Objects.requireNonNull(value, "value");
        while (true) {
            DefaultKnotraRuntime.PublicationSlotObservation observation =
                    runtime.publicationSlotObservation(slot);
            requirePublished("update", observation.state());
            try {
                Settlement settlement = runtime.updatePublication(
                        slot.slotId,
                        observation.epoch(),
                        observation.currentRegistrationId(),
                        contextHandle(),
                        key,
                        value);
                return change(PublicationOperation.UPDATE, settlement);
            } catch (StalePublicationSlotException stale) {
                // 另一个操作先线性化：锁外重读最新槽位后重试。
            }
        }
    }

    @Override
    public PublicationChange<T> unpublish() {
        PublicationChange<T> cached = terminalUnpublishChange;
        if (cached != null) {
            return cached;
        }
        while (true) {
            DefaultKnotraRuntime.PublicationSlotObservation observation =
                    runtime.publicationSlotObservation(slot);
            if (observation.state() == PublicationState.DISPLACED) {
                throw rejection("unpublish", PublicationState.DISPLACED);
            }
            if (observation.state() == PublicationState.UNPUBLISHED) {
                // 并发 unpublish 的幂等分支：没有新的真实事务，返回已收敛的终态结果。
                PublicationChange<T> terminal = change(
                        PublicationOperation.UNPUBLISH,
                        DefaultSettlement.empty(observation.lastChangedGeneration()));
                terminalUnpublishChange = terminal;
                return terminal;
            }
            try {
                Settlement settlement = runtime.unpublishPublication(
                        slot.slotId,
                        observation.epoch(),
                        observation.currentRegistrationId(),
                        contextHandle(),
                        key);
                PublicationChange<T> change = change(
                        PublicationOperation.UNPUBLISH, settlement);
                terminalUnpublishChange = change;
                return change;
            } catch (StalePublicationSlotException stale) {
                // 重读最新槽位：可能已 UNPUBLISHED（幂等返回）或 DISPLACED（拒绝）。
            }
        }
    }

    private ContextHandleImpl contextHandle() {
        return (ContextHandleImpl) context;
    }

    private void requirePublished(String operation, PublicationState state) {
        if (state != PublicationState.PUBLISHED) {
            throw rejection(operation, state);
        }
    }

    private TransactionRejectedException rejection(
            String operation,
            PublicationState currentState) {
        return new TransactionRejectedException(List.of(new RuntimeDiagnostic(
                DiagnosticCode.INVALID_LIFECYCLE_OPERATION,
                key.name(),
                "publication is " + currentState + "; cannot " + operation)));
    }

    private PublicationChange<T> change(
            PublicationOperation operation,
            Settlement settlement) {
        return new Change<>(operation, this, settlement);
    }

    private static final class Change<T> implements PublicationChange<T> {
        private final PublicationOperation operation;
        private final Publication<T> publication;
        private final Settlement settlement;

        private Change(
                PublicationOperation operation,
                Publication<T> publication,
                Settlement settlement) {
            this.operation = Objects.requireNonNull(operation, "operation");
            this.publication = Objects.requireNonNull(publication, "publication");
            this.settlement = Objects.requireNonNull(settlement, "settlement");
        }

        @Override
        public PublicationOperation operation() {
            return operation;
        }

        @Override
        public Publication<T> publication() {
            return publication;
        }

        @Override
        public long generation() {
            return settlement.generation();
        }

        @Override
        public java.util.concurrent.CompletionStage<io.knotra.SettlementReport> whenSettled() {
            return settlement.whenSettled();
        }
    }
}
