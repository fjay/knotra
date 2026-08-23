package io.knotra.beans;

import java.util.Objects;

record BeanDisposal<T>(
        Beans.LifecycleMode mode,
        Beans.Disposer<? super T> syncDisposer,
        Beans.AsyncDisposer<? super T> asyncDisposer) {

    BeanDisposal {
        Objects.requireNonNull(mode, "mode");
        boolean argumentsMatchMode = switch (mode) {
            case AUTO, UNMANAGED -> syncDisposer == null && asyncDisposer == null;
            case CUSTOM_SYNC -> syncDisposer != null && asyncDisposer == null;
            case CUSTOM_ASYNC -> syncDisposer == null && asyncDisposer != null;
        };
        if (!argumentsMatchMode) {
            throw new IllegalArgumentException("disposer arguments do not match mode: " + mode);
        }
    }

    static <T> BeanDisposal<T> auto() {
        return new BeanDisposal<>(Beans.LifecycleMode.AUTO, null, null);
    }

    static <T> BeanDisposal<T> unmanaged() {
        return new BeanDisposal<>(Beans.LifecycleMode.UNMANAGED, null, null);
    }

    static <T> BeanDisposal<T> sync(Beans.Disposer<? super T> disposer) {
        Objects.requireNonNull(disposer, "disposer");
        return new BeanDisposal<>(Beans.LifecycleMode.CUSTOM_SYNC, disposer, null);
    }

    static <T> BeanDisposal<T> async(Beans.AsyncDisposer<? super T> disposer) {
        Objects.requireNonNull(disposer, "disposer");
        return new BeanDisposal<>(Beans.LifecycleMode.CUSTOM_ASYNC, null, disposer);
    }
}
