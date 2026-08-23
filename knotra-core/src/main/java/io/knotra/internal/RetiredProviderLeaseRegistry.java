package io.knotra.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registrations removed from the live index while their provider leases are still draining.
 *
 * <p>The registry intentionally stores only the registration ID and lease runtime. It never
 * retains capability keys, Java types, values, factories, or class loaders. Calls run outside the
 * runtime coordinator; lease completion callbacks only remove registry identities.</p>
 */
final class RetiredProviderLeaseRegistry {
    private final Map<String, ProviderLeaseRuntime> leases = new ConcurrentHashMap<>();

    CompletableFuture<Void> retire(
            String registrationId,
            ProviderLeaseRuntime lease) {
        ProviderLeaseRuntime existing = leases.putIfAbsent(registrationId, lease);
        if (existing != null && existing != lease) {
            throw new IllegalStateException(
                    "retired provider lease identity mismatch: " + registrationId);
        }
        CompletableFuture<Void> drain = lease.retire();
        drain.whenComplete((ignored, error) -> leases.remove(registrationId, lease));
        return drain;
    }

    List<ProviderLeaseRuntime> pending() {
        return new ArrayList<>(leases.values());
    }
}
