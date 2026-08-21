package io.knotra.pf4j;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/** Exact package identity policy for classes crossing the artifact boundary. */
public final class KnotraClassLoaderPolicy {

    private static final Set<String> REQUIRED_SHARED = Set.of(
            "io.knotra",
            "io.knotra.pf4j.spi",
            "org.pf4j"
    );

    private final ClassLoader sharedParent;
    private final Set<String> sharedPackages;

    public KnotraClassLoaderPolicy(ClassLoader sharedParent, Set<String> sharedContractPackages) {
        this.sharedParent = Objects.requireNonNull(sharedParent, "sharedParent");
        this.sharedPackages = normalize(sharedContractPackages);
    }

    public static KnotraClassLoaderPolicy forHost(Set<String> sharedContractPackages) {
        Set<String> packages = new LinkedHashSet<>(REQUIRED_SHARED);
        if (sharedContractPackages != null) {
            packages.addAll(sharedContractPackages);
        }
        return new KnotraClassLoaderPolicy(
                KnotraClassLoaderPolicy.class.getClassLoader(),
                packages);
    }

    ClassLoader sharedParent() {
        return sharedParent;
    }

    public Set<String> sharedPackages() {
        return Set.copyOf(sharedPackages);
    }

    public boolean isShared(String className) {
        int index = className.lastIndexOf('.');
        String packageName = index < 0 ? "" : className.substring(0, index);
        for (String shared : sharedPackages) {
            if (packageName.equals(shared) || packageName.startsWith(shared + ".")) {
                return true;
            }
        }
        return false;
    }

    public void validateInterface(Class<?> actualClass, Class<?> sharedInterface, String artifactId) {
        Class<?> actualView;
        try {
            actualView = actualClass.getClassLoader().loadClass(sharedInterface.getName());
        } catch (Throwable failure) {
            throw contractViolation("artifact cannot see shared interface "
                    + sharedInterface.getName() + ": " + safe(failure), artifactId);
        }
        if (actualView != sharedInterface) {
            throw contractViolation(
                    "shared interface identity mismatch: " + sharedInterface.getName()
                            + ", expected=" + describe(sharedInterface.getClassLoader())
                            + ", actual=" + describe(actualView.getClassLoader()),
                    artifactId);
        }
        validate(actualView, artifactId);
    }

    /** Rejects plugin-private and duplicate capability contract types before Core stores them. */
    public void validateContractType(Class<?> actualClass, String artifactId) {
        if (actualClass.getClassLoader() == null) {
            return;
        }
        Class<?> sharedClass;
        try {
            sharedClass = sharedParent.loadClass(actualClass.getName());
        } catch (Throwable failure) {
            throw contractViolation(
                    "plugin-private contract type rejected: " + actualClass.getName(), artifactId);
        }
        if (sharedClass != actualClass) {
            throw contractViolation(
                    "duplicate contract type rejected: " + actualClass.getName()
                            + ", expected=" + describe(sharedParent)
                            + ", actual=" + describe(actualClass.getClassLoader()),
                    artifactId);
        }
    }

    void validate(Class<?> actualClass, String artifactId) {
        if (!isShared(actualClass.getName())) {
            return;
        }
        Class<?> sharedClass;
        try {
            sharedClass = sharedParent.loadClass(actualClass.getName());
        } catch (Throwable failure) {
            throw contractViolation(
                    "shared class is absent from parent: " + actualClass.getName(), artifactId);
        }
        if (sharedClass != actualClass) {
            throw contractViolation(
                    "shared class identity mismatch: " + actualClass.getName()
                            + ", expected=" + describe(sharedParent)
                            + ", actual=" + describe(actualClass.getClassLoader()),
                    artifactId);
        }
    }

    private SharedContractViolationException contractViolation(String message, String artifactId) {
        return new SharedContractViolationException(message + ", artifactId=" + artifactId);
    }

    private static String safe(Throwable failure) {
        String message = failure.getMessage();
        return message == null ? failure.getClass().getName() : message;
    }

    static String describe(ClassLoader loader) {
        return loader == null
                ? "bootstrap"
                : (loader.getName() == null
                        ? loader.getClass().getName()
                        : loader.getName()) + "@" + System.identityHashCode(loader);
    }

    private static Set<String> normalize(Set<String> values) {
        Set<String> result = new LinkedHashSet<>();
        for (String value : values == null ? Set.<String>of() : values) {
            Objects.requireNonNull(value, "shared package");
            String normalized = value.strip();
            if (normalized.isBlank()
                    || normalized.startsWith(".")
                    || normalized.endsWith(".")) {
                throw new IllegalArgumentException("invalid shared package: " + normalized);
            }
            result.add(normalized);
        }
        return Set.copyOf(result);
    }
}
