package com.example.knotra.dependent;

import java.util.Collection;
import java.util.List;

import io.knotra.Component;
import io.knotra.ComponentDescriptor;
import io.knotra.ComponentFactory;
import io.knotra.NoConfig;
import io.knotra.pf4j.spi.ExportedComponentFactory;
import io.knotra.pf4j.spi.RuntimeComponentProvider;
import org.pf4j.Extension;

@Extension
public final class DependentRuntimeComponentProvider implements RuntimeComponentProvider {

    @Override
    public Collection<ExportedComponentFactory<?>> factories() {
        return List.of(ExportedComponentFactory.noConfig(
                new ComponentFactory<NoConfig>() {
            @Override
            public String factoryId() {
                return "dependent";
            }

            @Override
            public Component<NoConfig> create() {
                return new Component<>() {
                    @Override
                    public ComponentDescriptor descriptor() {
                        return ComponentDescriptor.named("dependent");
                    }

                    @Override
                    public void start(
                            io.knotra.ActivationContext context,
                            NoConfig config) {
                    }
                };
            }
        }));
    }
}
