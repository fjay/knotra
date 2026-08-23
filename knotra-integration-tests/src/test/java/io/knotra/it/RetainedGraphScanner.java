package io.knotra.it;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

final class RetainedGraphScanner {

    private static final Set<Class<?>> SCALAR_TYPES = Set.of(
            String.class,
            Boolean.class,
            Byte.class,
            Short.class,
            Integer.class,
            Long.class,
            Float.class,
            Double.class,
            Character.class,
            Void.class,
            Duration.class,
            Instant.class,
            LocalDate.class,
            LocalTime.class,
            LocalDateTime.class,
            OffsetDateTime.class,
            ZonedDateTime.class,
            UUID.class);

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

        if (isScalar(value, type)) {
            return;
        }
        if (value instanceof Optional<?> optional) {
            scan(optional.orElse(null), seen);
            return;
        }
        if (value instanceof AtomicReference<?> atomicReference) {
            scan(atomicReference.get(), seen);
            return;
        }
        if (value instanceof AtomicBoolean atomicBoolean) {
            scan(atomicBoolean.get(), seen);
            return;
        }
        if (value instanceof AtomicInteger atomicInteger) {
            scan(atomicInteger.get(), seen);
            return;
        }
        if (value instanceof AtomicLong atomicLong) {
            scan(atomicLong.get(), seen);
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
        if (isJdkRuntimeType(type)) {
            fail("unsupported JDK value in retained graph: " + type.getName()
                    + "; expose its safe semantic value explicitly");
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
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    scan(field.get(value), seen);
                } catch (ReflectiveOperationException | RuntimeException error) {
                    throw new AssertionError(
                            "cannot inspect retained graph field " + field
                                    + " of type " + field.getType().getName(),
                            error);
                }
            }
        }
    }

    private static boolean isScalar(Object value, Class<?> type) {
        return SCALAR_TYPES.contains(type) || value instanceof Enum<?>;
    }

    private static boolean isJdkRuntimeType(Class<?> type) {
        String name = type.getName();
        return name.startsWith("java.") || name.startsWith("javax.")
                || name.startsWith("jdk.") || name.startsWith("sun.");
    }
}
