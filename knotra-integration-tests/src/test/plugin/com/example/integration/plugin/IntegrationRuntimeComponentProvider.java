package com.example.integration.plugin;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.example.integration.contract.ContractEvent;
import com.example.integration.contract.IntegrationCoordinator;
import io.knotra.ActivationContext;
import io.knotra.CapabilityKey;
import io.knotra.Component;
import io.knotra.ComponentDescriptor;
import io.knotra.ComponentFactory;
import io.knotra.NoConfig;
import io.knotra.events.EventBus;
import io.knotra.events.EventCapabilities;
import io.knotra.events.EventDefinition;
import io.knotra.events.EventSubscription;
import io.knotra.pf4j.spi.ExportedComponentFactory;
import io.knotra.pf4j.spi.RuntimeComponentProvider;
import org.pf4j.Extension;

/**
 * Real PF4J fixture used only by the cross-module integration suite. It consumes host
 * capabilities (the Knotra event bus) and coordinates with the host exclusively through
 * the shared {@code com.example.integration.contract} package.
 */
@Extension
public final class IntegrationRuntimeComponentProvider implements RuntimeComponentProvider {

    public static final CapabilityKey<String> VALUE =
            CapabilityKey.of("integration.greeting", String.class);

    public IntegrationRuntimeComponentProvider() {
        IntegrationCoordinator.remember(getClass().getClassLoader());
    }

    @Override
    public Collection<ExportedComponentFactory<?>> factories() {
        return List.of(
                ExportedComponentFactory.of(String.class, greeting()),
                ExportedComponentFactory.noConfig(parent()),
                ExportedComponentFactory.noConfig(inFlight()),
                ExportedComponentFactory.noConfig(failingCleanup()),
                ExportedComponentFactory.noConfig(eventConsumer()));
    }

    private static ComponentFactory<String> greeting() {
        return new ComponentFactory<>() {
            @Override
            public String factoryId() {
                return "integration-greeting";
            }

            @Override
            public String normalizeConfig(String config) {
                if (config.isBlank()) {
                    throw new IllegalArgumentException("greeting config must be non-blank");
                }
                return config.trim();
            }

            @Override
            public Component<String> create() {
                return new Component<>() {
                    @Override
                    public ComponentDescriptor descriptor() {
                        return ComponentDescriptor.named("integration-greeting");
                    }

                    @Override
                    public void start(ActivationContext context, String config) {
                        context.provide(VALUE, config);
                    }
                };
            }
        };
    }

    private static ComponentFactory<NoConfig> parent() {
        return new ComponentFactory<>() {
            @Override
            public String factoryId() {
                return "integration-parent";
            }

            @Override
            public Component<NoConfig> create() {
                return new Component<>() {
                    @Override
                    public ComponentDescriptor descriptor() {
                        return ComponentDescriptor.named("integration-parent");
                    }

                    @Override
                    public void start(ActivationContext context, NoConfig config) {
                        context.mountChild("integration-child", childFactory());
                    }

                    private ComponentFactory<NoConfig> childFactory() {
                        return new ComponentFactory<>() {
                            @Override
                            public String factoryId() {
                                return "integration-child";
                            }

                            @Override
                            public Component<NoConfig> create() {
                                return new Component<>() {
                                    @Override
                                    public ComponentDescriptor descriptor() {
                                        return ComponentDescriptor.named("integration-child");
                                    }

                                    @Override
                                    public void start(
                                            ActivationContext childContext,
                                            NoConfig childConfig) {
                                        childContext.lifecycle().onClose("integration-child-cleanup", () -> {
                                                });
                                    }
                                };
                            }
                        };
                    }
                };
            }
        };
    }

    private static ComponentFactory<NoConfig> inFlight() {
        return new ComponentFactory<>() {
            @Override
            public String factoryId() {
                return "integration-in-flight";
            }

            @Override
            public Component<NoConfig> create() {
                IntegrationCoordinator.enterMount();
                IntegrationCoordinator.awaitMountRelease();
                return new Component<>() {
                    @Override
                    public ComponentDescriptor descriptor() {
                        return ComponentDescriptor.named("integration-in-flight");
                    }

                    @Override
                    public void start(ActivationContext context, NoConfig config) {
                    }
                };
            }
        };
    }

    private static ComponentFactory<NoConfig> failingCleanup() {
        return new ComponentFactory<>() {
            @Override
            public String factoryId() {
                return "integration-failing-cleanup";
            }

            @Override
            public Component<NoConfig> create() {
                return new Component<>() {
                    @Override
                    public ComponentDescriptor descriptor() {
                        return ComponentDescriptor.named("integration-failing-cleanup");
                    }

                    @Override
                    public void start(ActivationContext context, NoConfig config) {
                        context.lifecycle().onClose("integration-retryable-cleanup", () -> {
                            if (IntegrationCoordinator.shouldFailAndClearCleanup()) {
                                throw new IllegalStateException(
                                        "intentional integration cleanup failure");
                            }
                        });
                    }
                };
            }
        };
    }

    private static ComponentFactory<NoConfig> eventConsumer() {
        return new ComponentFactory<>() {
            @Override
            public String factoryId() {
                return "integration-event-consumer";
            }

            @Override
            public Component<NoConfig> create() {
                return new Component<>() {
                    @Override
                    public ComponentDescriptor descriptor() {
                        return ComponentDescriptor.named(
                                "integration-event-consumer",
                                io.knotra.CapabilityRequirement.required(
                                        EventCapabilities.EVENT_BUS));
                    }

                    @Override
                    public void start(ActivationContext context, NoConfig config) {
                        EventBus bus = context.require(EventCapabilities.EVENT_BUS);

                        EventSubscription shared = bus.subscribe(
                                EventDefinition.serial(ContractEvent.class),
                                event -> {
                                    IntegrationCoordinator.recordDelivery();
                                    IntegrationCoordinator.enterEvent();
                                    return IntegrationCoordinator.eventGate();
                                });
                        context.lifecycle().manageAsync(
                                "integration-shared-listener", shared);

                        EventSubscription pluginPrivate = bus.subscribe(
                                EventDefinition.serial(PluginEvent.class),
                                event -> CompletableFuture.completedFuture(true));
                        context.lifecycle().manageAsync(
                                "integration-private-listener", pluginPrivate);
                    }
                };
            }
        };
    }
}
