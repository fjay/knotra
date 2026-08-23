package io.knotra.it;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

final class RetainedGraphScanner {

    enum ClassPolicy {
        DENY_ALL,
        ALLOW_NON_PLUGIN
    }

    private final String pluginPackage;
    private final ClassPolicy classPolicy;

    private RetainedGraphScanner(String pluginPackage, ClassPolicy classPolicy) {
        this.pluginPackage = pluginPackage;
        this.classPolicy = classPolicy;
    }

    static RetainedGraphScanner denyingClasses(String pluginPackage) {
        return new RetainedGraphScanner(pluginPackage, ClassPolicy.DENY_ALL);
    }

    static RetainedGraphScanner allowingNonPluginClasses(String pluginPackage) {
        return new RetainedGraphScanner(pluginPackage, ClassPolicy.ALLOW_NON_PLUGIN);
    }

    void assertPure(Object... roots) {
        IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
        for (Object root : roots) {
            scan(root, seen);
        }
    }

    private void scan(Object value, IdentityHashMap<Object, Boolean> seen) {
        if (value == null || seen.put(value, Boolean.TRUE) != null) {
            return;
        }

        Class<?> type = value.getClass();
        assertFalse(value instanceof Throwable,
                "retained graph must not expose Throwable: " + type.getName());
        assertFalse(value instanceof ClassLoader,
                "retained graph must not expose ClassLoader: " + type.getName());
        assertFalse(type.getName().startsWith(pluginPackage),
                "retained graph must not expose a plugin instance: " + type.getName());

        if (value instanceof Class<?> retainedClass) {
            assertFalse(retainedClass.getName().startsWith(pluginPackage),
                    "active publication graph must not expose a plugin-private Class");
            if (classPolicy == ClassPolicy.DENY_ALL) {
                fail("pure DTO graph must not expose Class: " + retainedClass.getName());
            }
            return;
        }

        if (isScalar(type, value)) {
            return;
        }
        if (value instanceof Optional<?> optional) {
            scan(optional.orElse(null), seen);
            return;
        }
        if (type.isArray()) {
            scanArray(value, seen);
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                scan(item, seen);
            }
            return;
        }
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                scan(entry, seen);
            }
            return;
        }
        if (value instanceof Map.Entry<?, ?> entry) {
            scan(entry.getKey(), seen);
            scan(entry.getValue(), seen);
            return;
        }
        if (type.getName().startsWith("java.")) {
            return;
        }
        scanFields(value, type, seen);
    }

    private void scanArray(Object value, IdentityHashMap<Object, Boolean> seen) {
        int length = Array.getLength(value);
        for (int index = 0; index < length; index++) {
            scan(Array.get(value, index), seen);
        }
    }

    private void scanFields(
            Object value,
            Class<?> type,
            IdentityHashMap<Object, Boolean> seen) {
        for (Class<?> current = type; current != null && current != Object.class;
                current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (field.isSynthetic()) {
                    continue;
                }
                field.setAccessible(true);
                try {
                    scan(field.get(value), seen);
                } catch (ReflectiveOperationException error) {
                    throw new AssertionError(
                            "cannot inspect retained graph field " + field, error);
                }
            }
        }
    }

    private static boolean isScalar(Class<?> type, Object value) {
        return type.isPrimitive()
                || value instanceof String
                || value instanceof Number
                || value instanceof Boolean
                || value instanceof Character
                || type.isEnum()
                || value instanceof java.time.temporal.TemporalAccessor;
    }
}
