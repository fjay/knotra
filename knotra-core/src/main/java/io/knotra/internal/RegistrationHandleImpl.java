package io.knotra.internal;

import io.knotra.RegistrationHandle;


/**
 * 一个注册身份的内核句柄。
 *
 * <p>Capability 值可以相等，但撤销和重发布会产生新的注册 ID，并因此形成新的绑定代际。
 * 句柄只绑定到所属 Runtime 和注册 ID，实际数据保存在 {@link RuntimeView}。</p>
 */
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
