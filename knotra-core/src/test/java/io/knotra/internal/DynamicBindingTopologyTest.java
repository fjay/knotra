package io.knotra.internal;

import io.knotra.CapabilityKey;
import io.knotra.CapabilityRequirement;
import io.knotra.Component;
import io.knotra.ComponentDescriptor;
import io.knotra.ComponentFactory;
import io.knotra.ComponentState;
import io.knotra.ConfiguredMountHandle;
import io.knotra.KnotraRuntime;
import io.knotra.MountFactory;
import io.knotra.MountHandle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class DynamicBindingTopologyTest {
    private static final CapabilityKey<String> KEY =
            CapabilityKey.of("dynamic-binding-topology", String.class);

    private final KnotraRuntime runtime = KnotraRuntime.create();

    @AfterEach
    void tearDown() {
        runtime.close();
    }

    @Test
    void activeRequiredDynamicConsumerDoesNotRestartForSameOwnerProviderReplacement()
            throws Exception {
        assertSameOwnerReplacementDoesNotRestart(CapabilityRequirement.dynamicRequired(KEY));
    }

    @Test
    void activeOptionalDynamicConsumerDoesNotRestartForSameOwnerProviderReplacement()
            throws Exception {
        assertSameOwnerReplacementDoesNotRestart(CapabilityRequirement.dynamicOptional(KEY));
    }

    private void assertSameOwnerReplacementDoesNotRestart(
            CapabilityRequirement requirement) throws Exception {
        ConfiguredMountHandle<String> provider = mountProvider("one");
        assertEquals(ComponentState.ACTIVE, settled(provider));

        AtomicInteger consumerStarts = new AtomicInteger();
        MountHandle consumer = mount("consumer", context -> {
            consumerStarts.incrementAndGet();
            context.subscribe(KEY);
        }, requirement);
        assertEquals(ComponentState.ACTIVE, settled(consumer));
        String consumerActivation = ((DefaultKnotraRuntime) runtime).publishedState()
                .view.components.get(consumer.handleId()).currentActivationId();

        assertEquals(ComponentState.ACTIVE, provider.reconfigureAsync("two")
                .toCompletableFuture().get(10, TimeUnit.SECONDS));

        assertEquals(ComponentState.ACTIVE, settled(consumer));
        assertEquals(consumerActivation, ((DefaultKnotraRuntime) runtime).publishedState()
                .view.components.get(consumer.handleId()).currentActivationId());
        assertEquals(1, consumerStarts.get());
    }

    private ComponentState settled(MountHandle handle) throws Exception {
        return handle.whenSettled().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    private MountHandle mount(
            String mountId,
            MountFactory.Start start,
            CapabilityRequirement requirement) {
        return runtime.advanced().transact(transaction -> transaction.mount(
                runtime.root(),
                mountId,
                MountFactory.of(mountId,
                        ComponentDescriptor.named(mountId, requirement),
                        start))).value();
    }

    private ConfiguredMountHandle<String> mountProvider(String value) {
        ComponentFactory<String> factory = new ComponentFactory<>() {
            @Override
            public String factoryId() {
                return "configured-provider";
            }

            @Override
            public Component<String> create() {
                return new Component<>() {
                    @Override
                    public ComponentDescriptor descriptor() {
                        return ComponentDescriptor.named("configured-provider");
                    }

                    @Override
                    public void start(
                            io.knotra.ActivationContext context,
                            String config) {
                        context.provide(KEY, config);
                    }
                };
            }
        };
        return runtime.advanced().transact(transaction -> transaction.mount(
                runtime.root(), "configured-provider", factory, value)).value();
    }
}
