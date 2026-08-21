package io.knotra;

import java.util.List;

public record RuntimeSnapshot(
        long generation,
        List<ContextSnapshot> contexts,
        List<ComponentSnapshot> components,
        List<ActivationSnapshot> activations,
        List<RegistrationSnapshot> registrations,
        List<LifecycleScopeSnapshot> lifecycleScopes,
        List<RuntimeDiagnostic> diagnostics) {

    public RuntimeSnapshot {
        contexts = List.copyOf(contexts);
        components = List.copyOf(components);
        activations = List.copyOf(activations);
        registrations = sorted(registrations);
        lifecycleScopes = List.copyOf(lifecycleScopes);
        diagnostics = List.copyOf(diagnostics);
    }

    public record ContextSnapshot(
            String contextId,
            String parentId,
            String name,
            ContextState state,
            String canonicalPath) {
    }

    public record ComponentSnapshot(
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

    public record CapabilitySnapshot(
            String name,
            String typeName) {
    }

    public record RequirementSnapshot(
            CapabilitySnapshot capability,
            CapabilityRequirement.Mode mode) {
    }

    public record BindingSnapshot(
            CapabilitySnapshot capability,
            String registrationId,
            boolean present,
            CapabilityRequirement.Mode mode) {
    }

    public record ActivationSnapshot(
            String activationId,
            String handleId,
            ActivationState state,
            long configRevision,
            List<BindingSnapshot> bindings,
            String lifecycleScopeId) {
    }

    public enum RegistrationOwnerKind {
        HOST,
        ACTIVATION
    }

    public record RegistrationOwnerSnapshot(
            RegistrationOwnerKind kind,
            String ownerId) {
    }

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

    public record ManagedEntrySnapshot(
            String entryId,
            String description,
            CleanupState state,
            int attempts,
            String lastError) {
    }

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
