package io.knotra.beans;

import io.knotra.CapabilityKey;

import java.util.Objects;

record BeanOutput<T, P>(
        CapabilityKey<P> key,
        Beans.OutputMapper<? super T, ? extends P> mapper) {

    BeanOutput {
        Objects.requireNonNull(key, "key");
    }

    P value(T bean) throws Exception {
        P value = mapper == null
                ? key.type().cast(bean)
                : mapper.map(bean);
        if (value == null) {
            throw new IllegalStateException("output '" + key.name() + "' produced null");
        }
        return value;
    }
}
