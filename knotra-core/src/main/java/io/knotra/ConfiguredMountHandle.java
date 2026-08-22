package io.knotra;

import java.util.concurrent.CompletionStage;

/** Stable handle to a mount whose configuration type is part of its public contract. */
public interface ConfiguredMountHandle<C> extends MountHandle {
    CompletionStage<ComponentState> reconfigureAsync(C config);
}
