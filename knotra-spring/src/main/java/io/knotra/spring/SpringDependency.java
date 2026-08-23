package io.knotra.spring;

import io.knotra.CapabilityKey;
import io.knotra.CapabilityRequirement;

import java.util.Objects;

record SpringDependency<T>(CapabilityKey<T> key, String beanName, Binding binding) {

    enum Binding {
        REQUIRED,
        OPTIONAL_VALUE,
        OPTIONAL_OPTIONAL,
        DYNAMIC_CAPABILITY_REQUIRED,
        DYNAMIC_CAPABILITY_OPTIONAL,
        DYNAMIC_PROXY_REQUIRED,
        DYNAMIC_PROXY_OPTIONAL
    }

    SpringDependency {
        Objects.requireNonNull(key, "key");
        beanName = requireBeanName(beanName);
        Objects.requireNonNull(binding, "binding");
    }

    CapabilityRequirement requirement() {
        return switch (binding) {
            case REQUIRED -> CapabilityRequirement.required(key);
            case OPTIONAL_VALUE, OPTIONAL_OPTIONAL ->
                    CapabilityRequirement.optional(key);
            case DYNAMIC_CAPABILITY_REQUIRED, DYNAMIC_PROXY_REQUIRED ->
                    CapabilityRequirement.dynamicRequired(key);
            case DYNAMIC_CAPABILITY_OPTIONAL, DYNAMIC_PROXY_OPTIONAL ->
                    CapabilityRequirement.dynamicOptional(key);
        };
    }

    static String requireBeanName(String beanName) {
        Objects.requireNonNull(beanName, "beanName");
        String trimmed = beanName.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("beanName must not be blank");
        }
        return trimmed;
    }
}
