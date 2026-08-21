package io.knotra.pf4j;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * 跨 artifact 边界的精确共享包与 Class 身份策略。
 *
 * <p>内置的 Knotra、SPI 与 PF4J 包，以及宿主声明的合约包，都必须解析为宿主
 * ClassLoader 中的同一个 {@code Class} 对象。校验发生在工厂发现、描述符验证、
 * Capability require/find/provide 和子挂载之前，因此插件私有或重复合约类型不会进入
 * Core 类型表，卸载后插件 ClassLoader 才可能弱可达。</p>
 */
public final class KnotraClassLoaderPolicy {

    // Runtime 合约和 PF4J 扩展点必须永远来自宿主，否则适配器自身无法安全调用插件。
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
        // 使用“包或子包”判断而不是字符串前缀，避免把 io.knotraevil 误判为共享合约。
        for (String shared : sharedPackages) {
            if (packageName.equals(shared) || packageName.startsWith(shared + ".")) {
                return true;
            }
        }
        return false;
    }

    /** 确认插件实际看到的扩展点就是宿主版本，再对这个版本执行共享包身份校验。 */
    public void validateInterface(Class<?> actualClass, Class<?> sharedInterface, String artifactId) {
        Class<?> actualView;
        try {
            // 从插件自己的可见性解析接口；这会暴露被插件私有副本遮蔽的扩展点。
            actualView = actualClass.getClassLoader().loadClass(sharedInterface.getName());
        } catch (Throwable failure) {
            throw contractViolation("artifact cannot see shared interface "
                    + sharedInterface.getName() + ": " + safe(failure), artifactId);
        }
        // 名称相同不代表可调用：方法分派按精确 Class 身份进行。
        if (actualView != sharedInterface) {
            throw contractViolation(
                    "shared interface identity mismatch: " + sharedInterface.getName()
                            + ", expected=" + describe(sharedInterface.getClassLoader())
                            + ", actual=" + describe(actualView.getClassLoader()),
                    artifactId);
        }
        validate(actualView, artifactId);
    }

    /** 在 Core 保存类型前拒绝插件私有合约与同名重复合约。 */
    public void validateContractType(Class<?> actualClass, String artifactId) {
        if (actualClass.getClassLoader() == null) {
            return;
        }
        // bootstrap 类型没有插件副本；其余合约必须能由宿主按同一名称解析。
        Class<?> sharedClass;
        try {
            sharedClass = sharedParent.loadClass(actualClass.getName());
        } catch (Throwable failure) {
            throw contractViolation(
                    "plugin-private contract type rejected: " + actualClass.getName(), artifactId);
        }
        // 拒绝“二进制名相同但 ClassLoader 不同”的合约，防止 Capability 代际被分裂。
        if (sharedClass != actualClass) {
            throw contractViolation(
                    "duplicate contract type rejected: " + actualClass.getName()
                            + ", expected=" + describe(sharedParent)
                            + ", actual=" + describe(actualClass.getClassLoader()),
                    artifactId);
        }
    }

    /** 对落在共享包内的类执行宿主身份校验；插件私有实现类不跨边界时无需限制。 */
    void validate(Class<?> actualClass, String artifactId) {
        if (!isShared(actualClass.getName())) {
            return;
        }
        Class<?> sharedClass;
        // 共享 API 不能由插件副本补齐；宿主缺失时应在进入 Core 前失败。
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
