package io.knotra.internal;

import io.knotra.RegistrationHandle;

final class RegistrationHandleImpl implements RegistrationHandle {
    final DefaultKnotraRuntime runtime;
    final String id;

    RegistrationHandleImpl(DefaultKnotraRuntime runtime, String id) {
        this.runtime = runtime;
        this.id = id;
    }

    @Override
    public String registrationId() {
        return id;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (other == null || other.getClass() != getClass()) {
            return false;
        }
        RegistrationHandleImpl handle = (RegistrationHandleImpl) other;
        return runtime == handle.runtime && id.equals(handle.id);
    }

    @Override
    public int hashCode() {
        return 31 * System.identityHashCode(runtime) + id.hashCode();
    }

    @Override
    public String toString() {
        return "RegistrationHandle[" + id + "]";
    }
}
