package io.knotra;

import java.util.Map;

/**
 * 挂载选项：来源元数据与自由格式的 metadata 标签。
 *
 * <p>记录不可变；紧凑构造函数将缺失来源规格化为宿主来源，并把 metadata 复制为不可变 Map。
 */
public record MountOptions(ComponentOrigin origin, Map<String, String> metadata) {
    public MountOptions {
        origin = origin == null ? ComponentOrigin.host() : origin;
        metadata = Map.copyOf(metadata == null ? Map.of() : metadata);
    }

    public MountOptions(Map<String, String> metadata) {
        this(ComponentOrigin.host(), metadata);
    }

    public MountOptions(ComponentOrigin origin) {
        this(origin, Map.of());
    }

    /** 默认选项：宿主来源，无 metadata。 */
    public static final MountOptions DEFAULT = new MountOptions(ComponentOrigin.host(), Map.of());

    /** 返回指定名称的 metadata 值；不存在时返回 null。 */
    public String metadata(String name) {
        return metadata.get(name);
    }
}
