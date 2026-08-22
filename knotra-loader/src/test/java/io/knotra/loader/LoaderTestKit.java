package io.knotra.loader;

import java.util.List;

import io.knotra.Component;
import io.knotra.ComponentDescriptor;
import io.knotra.ComponentFactory;
import io.knotra.ConfigDecoder;
import io.knotra.MountOptions;
import io.knotra.NoConfig;

final class LoaderTestKit {

    interface Start<C> {
        void start(io.knotra.ActivationContext context, C config);
    }

    record Scripted<C>(ComponentDescriptor descriptor, Start<C> start) implements Component<C> {
        @Override
        public void start(io.knotra.ActivationContext context, C config) {
            start.start(context, config);
        }
    }

    record RecordingFactory<C>(String id, List<C> configs, Start<C> start)
            implements ComponentFactory<C> {
        @Override
        public String factoryId() {
            return id;
        }

        @Override
        public Component<C> create() {
            return new Scripted<>(ComponentDescriptor.named(id), (context, config) -> {
                configs.add(config);
                start.start(context, config);
            });
        }
    }

    private LoaderTestKit() {
    }

    static <C> ComponentFactory<C> factory(
            String id,
            Start<C> start,
            io.knotra.CapabilityRequirement... requirements) {
        ComponentDescriptor descriptor = ComponentDescriptor.named(id, requirements);
        return new ComponentFactory<>() {
            @Override
            public String factoryId() {
                return id;
            }

            @Override
            public Component<C> create() {
                return new Scripted<>(descriptor, start);
            }
        };
    }

    static <C> ComponentFactoryResolver resolver(
            FactoryRef ref,
            ComponentFactory<C> factory,
            ConfigDecoder<C> decoder) {
        return ClasspathFactoryResolver.builder()
                .add(ref, factory, decoder)
                .build();
    }

    static ComponentFactoryResolver resolver(
            FactoryRef ref,
            ComponentFactory<NoConfig> factory) {
        return ClasspathFactoryResolver.builder()
                .add(ref, factory)
                .build();
    }

    static ComponentEntry entry(String path, FactoryRef ref, Object config) {
        return config == null || config == NoConfig.INSTANCE
                ? ComponentEntry.of(path, ref)
                : ComponentEntry.configured(path, ref, config);
    }

    static void assertAccepted(ReconcileResult result) {
        if (!result.converged() || !result.diagnostics().isEmpty()) {
            throw new AssertionError(result.diagnostics().toString());
        }
    }

    static void assertRejected(ReconcileResult result, LoaderDiagnosticCode code) {
        if (result.converged()
                || result.diagnostics().stream().noneMatch(item -> item.code() == code)) {
            throw new AssertionError(result.diagnostics().toString());
        }
    }

    static MountOptions options(String source) {
        return new MountOptions(io.knotra.ComponentOrigin.artifact(source, "1", source));
    }
}
