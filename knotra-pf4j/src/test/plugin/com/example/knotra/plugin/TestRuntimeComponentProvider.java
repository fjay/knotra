package com.example.knotra.plugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import com.example.knotra.contract.CleanupCoordinator;
import com.example.knotra.contract.ControlledGate;
import com.example.knotra.contract.MountCoordinator;
import com.example.knotra.contract.ReferenceVault;
import io.knotra.CapabilityKey;
import io.knotra.Component;
import io.knotra.ComponentDescriptor;
import io.knotra.ComponentFactory;
import io.knotra.ConfigDecoder;
import java.util.Map;
import io.knotra.NoConfig;
import io.knotra.pf4j.spi.ExportedComponentFactory;
import io.knotra.pf4j.spi.RuntimeComponentProvider;
import org.pf4j.Extension;

@Extension
public final class TestRuntimeComponentProvider implements RuntimeComponentProvider {

    public static final CapabilityKey<String> VALUE =
            CapabilityKey.of("knotra-pf4j-test-value", String.class);
    public static final CapabilityKey<ControlledGate> GATE =
            CapabilityKey.of("knotra-pf4j-test-gate", ControlledGate.class);

    public TestRuntimeComponentProvider() {
        ReferenceVault.remember(getClass().getClassLoader());
    }

    @Override
    public Collection<ExportedComponentFactory<?>> factories() {
        List<ExportedComponentFactory<?>> exported = List.of(
                ExportedComponentFactory.of(
                        String.class, alphaDecoder(), configured("alpha")),
                ExportedComponentFactory.of(String.class, configured("beta")),
                ExportedComponentFactory.noConfig(new AsyncCleanupFactory()),
                ExportedComponentFactory.noConfig(new InFlightFactory()),
                ExportedComponentFactory.noConfig(new LostRaceFactory()),
                ExportedComponentFactory.noConfig(
                        new PrivateContractFactory("private-descriptor", Mode.DESCRIPTOR)),
                ExportedComponentFactory.noConfig(
                        new PrivateContractFactory("private-provide", Mode.PROVIDE)),
                ExportedComponentFactory.noConfig(
                        new PrivateContractFactory("private-child", Mode.CHILD)),
                ExportedComponentFactory.noConfig(new ChildFactory()),
                ExportedComponentFactory.noConfig(new FailingCleanupFactory()));
        List<ExportedComponentFactory<?>> result = new ArrayList<>(exported);
        if (Boolean.getBoolean("knotra.pf4j.test.exportPrivateConfig")) {
            result.add(ExportedComponentFactory.of(
                    PrivateConfig.class, privateConfigFactory()));
        }
        return List.copyOf(result);
    }

    private record PrivateConfig(String value) {
    }

    private static ComponentFactory<PrivateConfig> privateConfigFactory() {
        return new ComponentFactory<>() {
            @Override
            public String factoryId() {
                return "private-config";
            }

            @Override
            public Component<PrivateConfig> create() {
                return new Component<>() {
                    @Override
                    public ComponentDescriptor descriptor() {
                        return ComponentDescriptor.named("private-config");
                    }

                    @Override
                    public void start(
                            io.knotra.ActivationContext context,
                            PrivateConfig config) {
                    }
                };
            }
        };
    }

    private static ConfigDecoder<String> alphaDecoder() {
        return raw -> {
            if (raw instanceof Map<?, ?> map
                    && map.containsKey("value")
                    && map.get("value") instanceof String value) {
                return value;
            }
            if (raw instanceof String value) {
                return value;
            }
            throw new IllegalArgumentException(
                    "alpha config must be a value string or a map with a string value");
        };
    }

    private static ComponentFactory<String> configured(String id) {
        return new ComponentFactory<>() {
            @Override
            public String factoryId() {
                return id;
            }

            @Override
            public Component<String> create() {
                return new Component<>() {
                    @Override
                    public ComponentDescriptor descriptor() {
                        return ComponentDescriptor.named(id);
                    }

                    @Override
                    public void start(
                            io.knotra.ActivationContext context,
                            String config) {
                        context.provide(VALUE, config);
                    }
                };
            }

            @Override
            public String normalizeConfig(String config) {
                if (config == null || config.isBlank()) {
                    throw new IllegalArgumentException("config must be non-blank");
                }
                return config.trim();
            }
        };
    }

    private enum Mode {
        DESCRIPTOR,
        PROVIDE,
        CHILD
    }

    private static final class PrivateContractFactory implements ComponentFactory<NoConfig> {
        private static final CapabilityKey<PrivateContract> PRIVATE =
                CapabilityKey.of("plugin-private-contract", PrivateContract.class);

        private final String id;
        private final Mode mode;

        private PrivateContractFactory(String id, Mode mode) {
            this.id = id;
            this.mode = mode;
        }

        @Override
        public String factoryId() {
            return id;
        }

        @Override
        public Component<NoConfig> create() {
            return new Component<>() {
                @Override
                public ComponentDescriptor descriptor() {
                    return mode == Mode.DESCRIPTOR
                            ? ComponentDescriptor.named(id, requirement())
                            : ComponentDescriptor.named(id);
                }

                private io.knotra.CapabilityRequirement requirement() {
                    return io.knotra.CapabilityRequirement.required(PRIVATE);
                }

                @Override
                public void start(
                        io.knotra.ActivationContext context,
                        NoConfig config) {
                    if (mode == Mode.PROVIDE) {
                        context.provide(PRIVATE, new PrivateContract());
                    } else if (mode == Mode.CHILD) {
                        context.mountChild(
                                "private-child",
                                privateChildFactory(),
                                NoConfig.INSTANCE,
                                io.knotra.MountOptions.DEFAULT);
                    }
                }

                private ComponentFactory<NoConfig> privateChildFactory() {
                    return new ComponentFactory<>() {
                        @Override
                        public String factoryId() {
                            return "private-child";
                        }

                        @Override
                        public Component<NoConfig> create() {
                            return new Component<>() {
                                @Override
                                public ComponentDescriptor descriptor() {
                                    return ComponentDescriptor.named(
                                            "private-child", requirement());
                                }

                                @Override
                                public void start(
                                        io.knotra.ActivationContext childContext,
                                        NoConfig childConfig) {
                                }
                            };
                        }
                    };
                }
            };
        }
    }

    private static final class AsyncCleanupFactory implements ComponentFactory<NoConfig> {
        @Override
        public String factoryId() {
            return "async-cleanup";
        }

        @Override
        public Component<NoConfig> create() {
            return new Component<>() {
                @Override
                public ComponentDescriptor descriptor() {
                    return ComponentDescriptor.named("async-cleanup");
                }

                @Override
                public void start(
                        io.knotra.ActivationContext context,
                        NoConfig config) {
                    AsyncGate gate = new AsyncGate();
                    context.provide(GATE, gate);
                    context.lifecycle().manageAsync(
                            "gate", () -> gate.released().thenRun(gate::markDisposed));
                }
            };
        }
    }

    private static final class InFlightFactory implements ComponentFactory<NoConfig> {
        @Override
        public String factoryId() {
            return "in-flight";
        }

        @Override
        public Component<NoConfig> create() {
            try {
                MountCoordinator.enterCreate();
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("factory create was interrupted", failure);
            }
            return new Component<>() {
                @Override
                public ComponentDescriptor descriptor() {
                    return ComponentDescriptor.named("in-flight");
                }

                @Override
                public void start(
                        io.knotra.ActivationContext context,
                        NoConfig config) {
                }
            };
        }
    }

    private static final class ChildFactory implements ComponentFactory<NoConfig> {
        @Override
        public String factoryId() {
            return "parent";
        }

        @Override
        public Component<NoConfig> create() {
            return new Component<>() {
                @Override
                public ComponentDescriptor descriptor() {
                    return ComponentDescriptor.named("parent");
                }

                @Override
                public void start(
                        io.knotra.ActivationContext context,
                        NoConfig config) {
                    context.mountChild(
                            "artifact-child",
                            new ComponentFactory<NoConfig>() {
                                @Override
                                public String factoryId() {
                                    return "artifact-child";
                                }

                                @Override
                                public Component<NoConfig> create() {
                                    return new Component<>() {
                                        @Override
                                        public ComponentDescriptor descriptor() {
                                            return ComponentDescriptor.named("artifact-child");
                                        }

                                        @Override
                                        public void start(
                                                io.knotra.ActivationContext childContext,
                                                NoConfig childConfig) {
                                            childContext.lifecycle().onClose("child-cleanup", () -> { });
                                        }
                                    };
                                }
                            },
                            NoConfig.INSTANCE,
                            io.knotra.MountOptions.DEFAULT);
                }
            };
        }
    }

    private static final class LostRaceFactory implements ComponentFactory<NoConfig> {
        @Override
        public String factoryId() {
            return "lost-race";
        }

        @Override
        public Component<NoConfig> create() {
            return new Component<>() {
                @Override
                public ComponentDescriptor descriptor() {
                    return ComponentDescriptor.named("lost-race");
                }

                @Override
                public void start(
                        io.knotra.ActivationContext context,
                        NoConfig config) {
                    try {
                        MountCoordinator.enterCreate();
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("lost-race start was interrupted", failure);
                    }
                    context.lifecycle().onClose("lost-race-cleanup", () -> {
                        if (CleanupCoordinator.shouldFailAndClear()) {
                            throw new IllegalStateException("lost-race cleanup failure");
                        }
                    });
                }
            };
        }
    }

    private static final class FailingCleanupFactory implements ComponentFactory<NoConfig> {
        @Override
        public String factoryId() {
            return "failing-cleanup";
        }

        @Override
        public Component<NoConfig> create() {
            return new Component<>() {
                @Override
                public ComponentDescriptor descriptor() {
                    return ComponentDescriptor.named("failing-cleanup");
                }

                @Override
                public void start(
                        io.knotra.ActivationContext context,
                        NoConfig config) {
                    context.lifecycle().onClose("retryable-cleanup", () -> {
                        if (CleanupCoordinator.shouldFailAndClear()) {
                            throw new IllegalStateException("intentional cleanup failure");
                        }
                    });
                }
            };
        }
    }
}
