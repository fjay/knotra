package io.knotra.loader;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 期望树引用工厂时使用的稳定标识，由工厂 ID、版本与元数据组成。
 *
 * <p>引用只表达“想要什么”，不决定“最终执行哪个实现”：执行身份由解析器产出
 * 的 {@link FactoryIdentity} 确定。引用按值比较，字段会先做归一化：
 * 版本为 null 时折算为空字符串，空白键或 null 值的元数据条目会被丢弃。
 *
 * @param factoryId 工厂 ID，非空白，前后空白会被去除
 * @param version 可选版本
 * @param metadata 可选元数据
 */
public record FactoryRef(
        String factoryId,
        String version,
        Map<String, String> metadata) {

    public FactoryRef {
        factoryId = requireText(factoryId, "factoryId");
        version = normalize(version);
        Map<String, String> safeMetadata = new LinkedHashMap<>();
        if (metadata != null) {
            metadata.forEach((name, value) -> {
                if (name != null && !name.isBlank() && value != null) {
                    safeMetadata.put(name.trim(), value.trim());
                }
            });
        }
        metadata = Map.copyOf(safeMetadata);
    }

    /** 无版本、无元数据的引用。 */
    public static FactoryRef of(String factoryId) {
        return new FactoryRef(factoryId, "", Map.of());
    }

    /** 带版本的引用。 */
    public static FactoryRef of(String factoryId, String version) {
        return new FactoryRef(factoryId, version, Map.of());
    }

    /** 读取指定元数据；不存在时返回 null。键不做空白归一化，需与写入时一致。 */
    public String metadata(String name) {
        Objects.requireNonNull(name, "name");
        return metadata.get(name);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
