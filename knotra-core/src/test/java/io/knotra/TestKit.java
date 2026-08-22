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

    static MountFactory mountFactory(String id, Component<NoConfig> component) {
        return new MountFactory() {
            @Override
            public String factoryId() {
                return id;
            }

            @Override
            public Component<NoConfig> create() {
                return component;
            }
        };
    }

    static MountHandle mount(
            KnotraRuntime runtime,
            ContextHandle context,
            String mountId,
            Start<NoConfig> start,
            CapabilityRequirement... requirements) {
        return mount(runtime, context, mountId, "component-" + mountId, start, requirements);
    }

    static MountHandle mount(
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
        return runtime.advanced().transact(transaction ->
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
        return runtime.advanced().transact(transaction ->
                transaction.provide(context, key, value)).value();
    }

    static ContextHandle child(KnotraRuntime runtime, ContextHandle parent, String name) {
        return runtime.advanced().transact(transaction ->
                transaction.childContext(parent, name)).value();
    }

    static RuntimeSnapshot.MountSnapshot component(
            KnotraRuntime runtime,
            MountHandle handle) {
        return runtime.advanced().snapshot().mounts().stream()
                .filter(item -> item.handleId().equals(handle.handleId()))
                .findFirst()
                .orElseThrow();
    }

    static Callable<ComponentState> settle(MountHandle handle) {
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
