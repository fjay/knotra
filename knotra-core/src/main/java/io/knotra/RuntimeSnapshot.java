package io.knotra;

import java.util.List;

/**
 * 运行时的不可变只读快照。
 *
 * <p>快照报告当前代际、Context、组件挂载、带 BindingSet 的 Activation、注册、
 * LifecycleScope 与受管条目的清理状态，以及稳定诊断。快照是纯数据：不引用存活的组件实例、
 * 资源、释放器、Throwable、Class 或 ClassLoader，因此持有快照不会阻止已卸载 artifact 的
 * ClassLoader 回收。紧凑构造函数将所有列表复制为不可变副本，注册按 ID 排序。
 */
public record RuntimeSnapshot(
        long generation,
        List<ContextSnapshot> contexts,
        List<MountSnapshot> mounts,
        List<ActivationSnapshot> activations,
        List<RegistrationSnapshot> registrations,
        List<LifecycleScopeSnapshot> lifecycleScopes,
        List<RuntimeDiagnostic> diagnostics) {

    public RuntimeSnapshot {
        contexts = List.copyOf(contexts);
        mounts = List.copyOf(mounts);
        activations = List.copyOf(activations);
        registrations = sorted(registrations);
        lifecycleScopes = List.copyOf(lifecycleScopes);
        diagnostics = List.copyOf(diagnostics);
    }

    /** Context 节点的快照。 */
    public record ContextSnapshot(
            String contextId,
            String parentId,
            String name,
            ContextState state,
            String canonicalPath) {
    }

    /** 组件挂载点的快照，含目标、当前激活与配置代际。 */
    public record MountSnapshot(
            String handleId,
            String contextId,
            String mountId,
            String componentId,
            String factoryId,
            ComponentOrigin origin,
            String ownerActivationId,
            String parentHandleId,
            ComponentState state,
            ComponentGoal goal,
            long configRevision,
            String currentActivationId,
            String lastActivationId,
            MountOptions mountOptions,
            List<RequirementSnapshot> requirements) {
    }

    /** Capability 合约的文本标识。 */
    public record CapabilitySnapshot(
            String name,
            String typeName) {
    }

    /** 组件声明的单项依赖需求。 */
    public record RequirementSnapshot(
            CapabilitySnapshot capability,
            CapabilityRequirement.Mode mode,
            CapabilityRequirement.CapabilityBinding binding) {
    }

    /**
     * Activation BindingSet 中的单个绑定。PINNED 绑定记录当前代际的注册身份；
     * DYNAMIC 绑定是固定占位，present 为 false 且不记录当前 provider。
     */
    public record BindingSnapshot(
            CapabilitySnapshot capability,
            String registrationId,
            boolean present,
            CapabilityRequirement.Mode mode,
            CapabilityRequirement.CapabilityBinding binding) {
    }

    /** 单次 Activation 的快照，含激活开始时固定的 BindingSet。 */
    public record ActivationSnapshot(
            String activationId,
            String handleId,
            ActivationState state,
            long configRevision,
            List<BindingSnapshot> bindings,
            String lifecycleScopeId) {
    }

    /** 注册所有者类别。 */
    public enum RegistrationOwnerKind {
        /** 宿主通过事务发布的注册。 */
        HOST,
        /** 组件 Activation 拥有并随其生命周期撤销的注册。 */
        ACTIVATION
    }

    /** 注册所有者的标识。 */
    public record RegistrationOwnerSnapshot(
            RegistrationOwnerKind kind,
            String ownerId) {
    }

    /** 单个 Capability 注册的快照，按 registrationId 稳定排序。 */
    public record RegistrationSnapshot(
            String registrationId,
            CapabilitySnapshot capability,
            String contextId,
            RegistrationOwnerSnapshot owner)
            implements Comparable<RegistrationSnapshot> {

        @Override
        public int compareTo(RegistrationSnapshot other) {
            return registrationId.compareTo(other.registrationId);
        }
    }

    /** LifecycleScope 受管条目的快照。 */
    public record ManagedEntrySnapshot(
            String entryId,
            String description,
            CleanupState state,
            int attempts,
            String lastError) {
    }

    /**
     * LifecycleScope 的快照，描述 Scope 树与并行组；parentScopeId 为 null 表示
     * 激活的根 Scope。
     */
    public record LifecycleScopeSnapshot(
            String scopeId,
            String parentScopeId,
            String activationId,
            String description,
            boolean parallel,
            LifecycleState state,
            List<ManagedEntrySnapshot> entries) {
    }

    private static List<RegistrationSnapshot> sorted(List<RegistrationSnapshot> value) {
        return value.stream().sorted().toList();
    }
}
