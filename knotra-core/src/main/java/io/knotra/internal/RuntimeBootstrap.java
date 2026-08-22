package io.knotra.internal;

import io.knotra.KnotraConfig;
import io.knotra.KnotraRuntime;

/**
 * Internal bootstrap used by the public runtime facade.
 *
 * <p>Keep this class minimal: widening it would expose kernel implementation details as public API.</p>
 */
public final class RuntimeBootstrap {
    private RuntimeBootstrap() {
    }

    public static KnotraRuntime create(KnotraConfig config) {
        return new DefaultKnotraRuntime(config);
    }
}
