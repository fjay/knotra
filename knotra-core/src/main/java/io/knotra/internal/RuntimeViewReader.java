package io.knotra.internal;

import java.util.Map;

/**
 * Read-only structural view used by graph algorithms.
 *
 * <p>Implementations remain package-private. A Draft may expose its coordinator-owned
 * maps so algorithms can avoid copying the whole kernel state; callers outside this
 * package must never receive this interface or its maps. Graph algorithms consume only
 * structural identity state; generation lineage and diagnostics stay on
 * {@link RuntimeView} itself.</p>
 */
interface RuntimeViewReader {
    Map<String, RuntimeView.ContextData> contexts();

    Map<String, RuntimeView.RegistrationData> registrations();

    Map<String, RuntimeView.ComponentData> components();

    Map<String, RuntimeView.ActivationData> activations();
}
