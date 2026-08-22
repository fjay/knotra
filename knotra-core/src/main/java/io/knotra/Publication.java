package io.knotra;

import java.util.Optional;

/**
 * A stable capability publication slot. The slot tracks registrations; it never stores their value.
 *
 * <p>DISPLACED is terminal: an external replacement, context disposal, or runtime close removes the
 * current registration without silently creating a new one. A publication can only be updated while
 * PUBLISHED, and UNPUBLISHED is idempotent for later unpublish calls.</p>
 */
public interface Publication<T> {
    CapabilityKey<T> key();

    ContextHandle context();

    PublicationState state();

    Optional<Registration<T>> currentRegistration();

    PublicationChange<T> update(T value);

    PublicationChange<T> unpublish();
}
