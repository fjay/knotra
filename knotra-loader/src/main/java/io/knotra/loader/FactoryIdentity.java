package io.knotra.loader;

import java.util.Objects;

/**
 * 解析器选定的可执行实现身份：工厂 ID、版本与实现指纹。
 *
 * <p>同一个 {@link FactoryRef} 在不同时刻可能解析到不同实现（classpath 工厂
 * 实例被替换、artifact 升级等）。Loader 用身份而不是引用判断收敛方式：
 * 身份变化必须先完整释放旧挂载、再挂载新实现；身份不变时配置变化走
 * 重配置路径，复用既有 ComponentHandle。
 *
 * @param factoryId 工厂 ID
 * @param version 版本；null 折算为空字符串
 * @param fingerprint 解析器提供的实现指纹。classpath 解析器使用工厂类名与
 *                   实例身份哈希，artifact 桥接通常使用 artifact 坐标加工厂 ID
 */
public record FactoryIdentity(
        String factoryId,
        String version,
        String fingerprint) {

    public FactoryIdentity {
        factoryId = requireText(factoryId, "factoryId");
        version = version == null ? "" : version.trim();
        fingerprint = requireText(fingerprint, "fingerprint");
    }

    /** 直接以三元组构造身份。 */
    public static FactoryIdentity of(
            String factoryId,
            String version,
            String fingerprint) {
        return new FactoryIdentity(factoryId, version, fingerprint);
    }

    /** 沿用引用中的工厂 ID 与版本，加上解析器给出的实现指纹构造身份。 */
    public static FactoryIdentity fromRef(FactoryRef ref, String fingerprint) {
        Objects.requireNonNull(ref, "ref");
        return new FactoryIdentity(ref.factoryId(), ref.version(), fingerprint);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
