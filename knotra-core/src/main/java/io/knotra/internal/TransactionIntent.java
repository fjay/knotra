package io.knotra.internal;

import io.knotra.CapabilityKey;
/** 宿主事务提交前积累的结构变更意图。 */
sealed interface Intent permits
        ProvideIntent,
        RevokeIntent,
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
