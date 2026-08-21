package io.knotra.loader;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import io.knotra.Component;
import io.knotra.ComponentDescriptor;
import io.knotra.ComponentFactory;
import io.knotra.ConfigSchema;
import io.knotra.MountOptions;
import io.knotra.NoConfig;

final class LoaderTestKit {

    interface Start<C> {
        void start(io.knotra.ActivationContext context, C config);
    }

    record Scripted<C>(ComponentDescriptor descriptor, Start<C> start) implements Component<C> {
        @Override
        public void start(io.knotra.ActivationContext context, C config) throws Exception {
            this.start.start(context, config);
        }
    }

    record RecordingFactory<C>(String id, List<C> configs, Start<C> start) implements ComponentFactory<C> {
        @Override
        public String factoryId() {
            return id;
        }

        @Override
        public Component<C> create() {
            return new Scripted<>(ComponentDescriptor.of(id), (context, config) -> {
                configs.add(config);
                this.start.start(context, config);
            });
        }
    }

    private LoaderTestKit() {
    }

    static <C> ComponentFactory<C> factory(
            String id,
            Start<C> start,
            io.knotra.CapabilityRequirement... requirements) {
        io.knotra.ComponentDescriptor descriptor =
                io.knotra.ComponentDescriptor.of(id, requirements);
        return new ComponentFactory<>() {
            @Override
            public String factoryId() {
                return id;
            }

            @Override
            public Component<C> create() {
                return new Scripted<>(descriptor, (context, config) ->
                        start.start(context, config));
            }
        };
    }


    static <C> ComponentFactoryResolver resolver(
            FactoryRef ref,
            ComponentFactory<C> factory,
            ConfigSchema<C> schema) {
        return ClasspathComponentFactoryResolver.builder()
                .add(ref, factory, schema)
                .build();
    }

    static ComponentFactoryResolver resolver(FactoryRef ref, ComponentFactory<?> factory) {
        return ClasspathComponentFactoryResolver.builder()
                .add(ref, factory)
                .build();
    }

    static ComponentEntry entry(
            String path,
            FactoryRef ref,
            Object config) {
        return ComponentEntry.of(path, ref, config == null ? NoConfig.INSTANCE : config);
    }

    static void assertAccepted(ReconcileResult result) {
        if (!result.converged() || !result.diagnostics().isEmpty()) {
            throw new AssertionError(result.diagnostics().toString());
        }
    }

    static void assertRejected(ReconcileResult result, LoaderDiagnosticCode code) {
        if (result.converged() || result.diagnostics().stream().noneMatch(item -> item.code() == code)) {
            throw new AssertionError(result.diagnostics().toString());
        }
    }

    static MountOptions options(String source) {
        return new MountOptions(io.knotra.ComponentOrigin.artifact(source, "1", source));
    }
}
