package io.knotra.internal;

import io.knotra.CapabilityKey;

import java.util.concurrent.atomic.AtomicReference;
/** 宿主事务提交前积累的结构变更意图。 */
sealed interface Intent permits
        ProvideIntent,
        RevokeIntent,
        PublicationProvideIntent,
        PublicationUnpublishIntent,
        ChildContextIntent,
        MountIntent,
        ReconfigureIntent,
        DisposeIntent,
        ContextDisposeIntent {
}

record ProvideIntent(
        RegistrationHandleImpl handle,
        ContextHandleImpl context,
        CapabilityKey<?> key,
        Object value) implements Intent {
}

/**
 * Publication 发布/更新意图。slotId 为 null 表示 create-or-attach：活跃同名槽位被
 * 线性化为 UPDATE，否则创建新槽位；resolvedSlotId 由协调器在提交前回填，调用方据此
 * 构造纯句柄，避免提交后按坐标反查与新槽位产生竞态。
 */
record PublicationProvideIntent(
        String slotId,
        ContextHandleImpl context,
        CapabilityKey<?> key,
        Object value,
        long expectedEpoch,
        String expectedRegistrationId,
        RegistrationHandleImpl handle,
        AtomicReference<PublicationProvideOutcome> outcome) implements Intent {
}

/** 协调器回填的发布结果：槽位 ID、共享终态 ref 与线性化操作（新建 PUBLISH / 挂接 UPDATE）。 */
record PublicationProvideOutcome(
        String slotId,
        io.knotra.PublicationOperation operation,
        PublicationSlotTerminalRef terminalRef) {
}

/** Publication 主动撤销意图；期望失效时由句柄重读最新槽位后重试。 */
record PublicationUnpublishIntent(
        String slotId,
        ContextHandleImpl context,
        CapabilityKey<?> key,
        long expectedEpoch,
        String expectedRegistrationId) implements Intent {
}

record RevokeIntent(
        RegistrationHandleImpl handle) implements Intent {
}

record ChildContextIntent(
        ContextHandleImpl parent,
        String name,
        ContextHandleImpl handle) implements Intent {
}

record MountIntent(
        ContextHandleImpl context,
        String mountId,
        PreparedComponent<?> prepared,
        MountHandleImpl handle) implements Intent {
}

record ReconfigureIntent<C>(
        ConfiguredMountHandleImpl<C> handle,
        Object config,
        long expectedRevision,
        boolean equivalent) implements Intent {
}

record DisposeIntent(
        MountHandleImpl handle) implements Intent {
}

record ContextDisposeIntent(
        ContextHandleImpl handle) implements Intent {
}
