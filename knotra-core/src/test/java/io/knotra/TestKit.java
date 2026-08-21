package io.knotra;


import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

final class TestKit {
    private TestKit() {
    }

    interface Start<C> {
        void start(ActivationContext context, C config) throws Exception;
    }

    record Scripted<C>(ComponentDescriptor descriptor, Start<C> start) implements Component<C> {
        @Override
        public void start(ActivationContext context, C config) throws Exception {
            this.start.start(context, config);
        }
    }

    static <C> ComponentFactory<C> factory(String id, Component<C> component) {
        return new ComponentFactory<>() {
            @Override
            public String factoryId() {
                return id;
            }

            @Override
            public Component<C> create() {
                return component;
            }
        };
    }

    static ComponentHandle<NoConfig> mount(
            KnotraRuntime runtime,
            ContextHandle context,
            String mountId,
            Start<NoConfig> start,
            CapabilityRequirement... requirements) {
        return mount(runtime, context, mountId, "component-" + mountId, start, requirements);
    }
    static ComponentHandle<NoConfig> mount(
            KnotraRuntime runtime,
            ContextHandle context,
            String mountId,
            String componentId,
            Start<NoConfig> start,
            CapabilityRequirement... requirements) {
        Component<NoConfig> component = new Scripted<>(
                ComponentDescriptor.of(componentId, requirements),
                start);
        ComponentFactory<NoConfig> typedFactory = factory(componentId, component);
        MutationResult<ComponentHandle<NoConfig>> result = runtime.mutate(mutation ->
                mutation.mount(context, mountId, typedFactory, NoConfig.INSTANCE));
        if (!result.committed()) {
            throw new AssertionError(result.diagnostics().toString());
        }
        return result.value();
    }

    static NoConfig noConfig() {
        return NoConfig.INSTANCE;
    }

    static RegistrationHandle provide(
            KnotraRuntime runtime,
            ContextHandle context,
            CapabilityKey<String> key,
            String value) {
        MutationResult<RegistrationHandle> result = runtime.mutate(mutation ->
                mutation.provide(context, key, value));
        if (!result.committed()) {
            throw new AssertionError(result.diagnostics().toString());
        }
        return result.value();
    }

    static ContextHandle child(KnotraRuntime runtime, ContextHandle parent, String name) {
        MutationResult<ContextHandle> result = runtime.mutate(mutation ->
                mutation.childContext(parent, name));
        if (!result.committed()) {
            throw new AssertionError(result.diagnostics().toString());
        }
        return result.value();
    }

    static RuntimeSnapshot.ComponentSnapshot component(KnotraRuntime runtime, ComponentHandle<?> handle) {
        return runtime.snapshot().components().stream()
                .filter(item -> item.handleId().equals(handle.handleId()))
                .findFirst()
                .orElseThrow();
    }

    static Callable<ComponentState> settle(ComponentHandle<?> handle) {
        return () -> handle.whenSettled().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    static void assertCommitted(MutationResult<?> result) {
        if (!result.committed()) {
            throw new AssertionError(result.diagnostics().toString());
        }
    }

    static void assertRejected(MutationResult<?> result, DiagnosticCode code) {
        if (result.committed() || result.diagnostics().getFirst().code() != code) {
            throw new AssertionError(result.diagnostics().toString());
        }
    }
}
