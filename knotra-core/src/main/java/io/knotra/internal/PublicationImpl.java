package io.knotra.internal;

import io.knotra.CapabilityKey;
import io.knotra.ContextHandle;
import io.knotra.Publication;
import io.knotra.PublicationChange;
import io.knotra.PublicationOperation;
import io.knotra.PublicationState;
import io.knotra.Registration;
import io.knotra.Settlement;
import io.knotra.TransactionRejectedException;
import io.knotra.RuntimeDiagnostic;
import io.knotra.DiagnosticCode;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 稳定发布槽位实现。每个槽位的结构化操作均线性化执行。 */
final class PublicationImpl<T> implements Publication<T> {
    private final DefaultKnotraRuntime runtime;
    private final CapabilityKey<T> key;
    private final ContextHandle context;
    private RegistrationImpl<T> current;
    private PublicationState state = PublicationState.PUBLISHED;
    private PublicationChange<T> unpublishChange;

    PublicationImpl(
            DefaultKnotraRuntime runtime,
            CapabilityKey<T> key,
            ContextHandle context,
            Registration<T> initial) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.key = Objects.requireNonNull(key, "key");
        this.context = Objects.requireNonNull(context, "context");
        if (!(initial instanceof RegistrationImpl<T> registration)
                || registration.runtime() != runtime) {
            throw new IllegalArgumentException("registration does not belong to this runtime");
        }
        this.current = registration;
    }

    static <T> PublicationChange<T> publish(
            DefaultKnotraRuntime runtime,
            ContextHandle context,
            CapabilityKey<T> key,
            T value) {
        Registration<T> registration = runtime.register(context, key, value);
        PublicationImpl<T> publication = new PublicationImpl<>(runtime, key, context, registration);
        PublicationChange<T> change = publication.change(
                PublicationOperation.PUBLISH,
                registration,
                registration);
        return change;
    }

    @Override
    public CapabilityKey<T> key() {
        return key;
    }

    @Override
    public ContextHandle context() {
        return context;
    }

    @Override
    public PublicationState state() {
        synchronized (this) {
            refreshDisplacementLocked();
            return state;
        }
    }

    Registration<T> currentInternal() {
        synchronized (this) {
            refreshDisplacementLocked();
            return state == PublicationState.PUBLISHED ? current : null;
        }
    }

    @Override
    public PublicationChange<T> update(T value) {
        Objects.requireNonNull(value, "value");
        Registration<T> replacement;
        synchronized (this) {
            refreshDisplacementLocked();
            requirePublished("update");
            replacement = runtime.replace(current, value);
            current = (RegistrationImpl<T>) replacement;
            return change(PublicationOperation.UPDATE, replacement, replacement);
        }
    }

    @Override
    public PublicationChange<T> unpublish() {
        synchronized (this) {
            refreshDisplacementLocked();
            if (state == PublicationState.UNPUBLISHED) {
                return unpublishChange;
            }
            if (state != PublicationState.PUBLISHED) {
                throw rejection("unpublish", state);
            }
            Settlement settlement = runtime.revoke(current);
            current.markStale();
            Registration<T> removed = current;
            current = null;
            state = PublicationState.UNPUBLISHED;
            unpublishChange = change(PublicationOperation.UNPUBLISH, removed, settlement);
            return unpublishChange;
        }
    }

    private void requirePublished(String operation) {
        if (state != PublicationState.PUBLISHED) {
            throw rejection(operation, state);
        }
    }

    private TransactionRejectedException rejection(String operation, PublicationState currentState) {
        return new TransactionRejectedException(List.of(new RuntimeDiagnostic(
                DiagnosticCode.INVALID_LIFECYCLE_OPERATION,
                key.name(),
                "publication is " + currentState + "; cannot " + operation)));
    }

    private void refreshDisplacementLocked() {
        if (state == PublicationState.PUBLISHED
                && !runtime.hasLiveRegistration(current.registrationId())) {
            current.markStale();
            current = null;
            state = PublicationState.DISPLACED;
        }
    }

    private PublicationChange<T> change(
            PublicationOperation operation,
            Registration<T> registration,
            Settlement settlement) {
        return new Change<>(operation, this, registration, settlement);
    }

    private static final class Change<T> implements PublicationChange<T> {
        private final PublicationOperation operation;
        private final Publication<T> publication;
        private final Registration<T> registration;
        private final Settlement settlement;

        private Change(
                PublicationOperation operation,
                Publication<T> publication,
                Registration<T> registration,
                Settlement settlement) {
            this.operation = Objects.requireNonNull(operation, "operation");
            this.publication = Objects.requireNonNull(publication, "publication");
            this.registration = registration;
            this.settlement = Objects.requireNonNull(settlement, "settlement");
        }

        @Override
        public PublicationOperation operation() {
            return operation;
        }

        @Override
        public Publication<T> publication() {
            return publication;
        }

        @Override
        public long generation() {
            return settlement.generation();
        }

        @Override
        public java.util.concurrent.CompletionStage<io.knotra.SettlementReport> whenSettled() {
            return settlement.whenSettled();
        }
    }
}
