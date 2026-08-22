package io.knotra.spring;

import io.knotra.CapabilityKey;

import java.util.Objects;
import java.util.Optional;

record SpringOutput<T>(CapabilityKey<T> key, Optional<String> beanName) {

    SpringOutput {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(beanName, "beanName");
        beanName = beanName.map(name -> {
            String trimmed = name.trim();
            if (trimmed.isEmpty()) {
                throw new IllegalArgumentException("output beanName must not be blank");
            }
            return trimmed;
        });
    }

    static <T> SpringOutput<T> byType(CapabilityKey<T> key) {
        return new SpringOutput<>(key, Optional.empty());
    }

    static <T> SpringOutput<T> byName(CapabilityKey<T> key, String beanName) {
        return new SpringOutput<>(key, Optional.of(SpringDependency.requireBeanName(beanName)));
    }
}
