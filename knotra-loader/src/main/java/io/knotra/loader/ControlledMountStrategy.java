package io.knotra.loader;

import java.util.concurrent.CompletionStage;

import io.knotra.ComponentHandle;

/**
 * Performs one structural component mount through an allocated, single-use slot.
 */
@FunctionalInterface
public interface ControlledMountStrategy {

    CompletionStage<ComponentHandle<?>> mount(
            ControlledMountContext context,
            Object normalizedConfig);
}
