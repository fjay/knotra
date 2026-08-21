package io.knotra;

import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

final class TestKit {
    private TestKit() {
    }

    interface Start<C> {
        void start(ActivationContext context, C config) throws Exception;
    }

    @FunctionalInterface
    interface Attempt {
        void run() throws Exception;
    }

    record Scripted<C>(ComponentDescriptor descriptor, Start<C> start) implements Component<C> {
        @Override
        public void start(ActivationContext context, C config) throws Exception {
            start.start(context, config);
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
                ComponentDescriptor.named(componentId, requirements),
                start);
        ComponentFactory<NoConfig> factory = factory(componentId, component);
        return runtime.transact(transaction ->
                transaction.mount(context, mountId, factory)).value();
    }

    static NoConfig noConfig() {
        return NoConfig.INSTANCE;
    }

    static RegistrationHandle provide(
            KnotraRuntime runtime,
            ContextHandle context,
            CapabilityKey<String> key,
            String value) {
        return runtime.transact(transaction ->
                transaction.provide(context, key, value)).value();
    }

    static ContextHandle child(KnotraRuntime runtime, ContextHandle parent, String name) {
        return runtime.transact(transaction ->
                transaction.childContext(parent, name)).value();
    }

    static RuntimeSnapshot.ComponentSnapshot component(
            KnotraRuntime runtime,
            ComponentHandle<?> handle) {
        return runtime.snapshot().components().stream()
                .filter(item -> item.handleId().equals(handle.handleId()))
                .findFirst()
                .orElseThrow();
    }

    static Callable<ComponentState> settle(ComponentHandle<?> handle) {
        return () -> handle.whenSettled().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    static void assertCommitted(TransactionReceipt<?> receipt) {
        if (receipt == null || receipt.generation() < 0) {
            throw new AssertionError("transaction did not return a valid receipt");
        }
    }

    static TransactionRejectedException assertRejected(Attempt attempt, DiagnosticCode code) {
        try {
            attempt.run();
        } catch (TransactionRejectedException rejection) {
            if (rejection.diagnostics().getFirst().code() != code) {
                throw new AssertionError(rejection.diagnostics().toString());
            }
            return rejection;
        } catch (Exception error) {
            throw new AssertionError("unexpected exception", error);
        }
        throw new AssertionError("transaction was committed");
    }
}
