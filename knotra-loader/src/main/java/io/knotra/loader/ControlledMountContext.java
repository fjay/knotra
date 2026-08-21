package io.knotra.loader;

import java.util.concurrent.CompletionStage;

import io.knotra.ComponentFactory;
import io.knotra.ComponentHandle;
import io.knotra.ContextHandle;
import io.knotra.MountOptions;

/** The single mount slot allocated by the loader for one desired entry. */
public interface ControlledMountContext {

    ContextHandle context();

    String mountId();

    <C> CompletionStage<ComponentHandle<C>> mount(
            ComponentFactory<C> factory,
            C config,
            MountOptions options);
}
