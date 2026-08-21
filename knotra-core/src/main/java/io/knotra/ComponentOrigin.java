package io.knotra;

/**
 * 组件来源的元数据，用于快照展示与审计。
 *
 * <p>记录不可变；紧凑构造函数将缺失的 kind 规格化为 HOST，并把文本字段裁剪到 240 个字符。
 */
public record ComponentOrigin(
        Kind kind,
        String sourceId,
        String version,
        String description) {

    /** 来源类别。 */
    public enum Kind {
        /** 宿主直接挂载的组件。 */
        HOST,
        /** 由外部 artifact（如插件）提供的组件。 */
        ARTIFACT
    }

    public ComponentOrigin {
        kind = kind == null ? Kind.HOST : kind;
        sourceId = safe(sourceId);
        version = safe(version);
        description = safe(description);
    }

    public static ComponentOrigin host() {
        return new ComponentOrigin(Kind.HOST, "host", "", "mounted by host");
    }
    public static ComponentOrigin artifact(String sourceId, String version, String description) {
        return new ComponentOrigin(Kind.ARTIFACT, sourceId, version, description);
    }

    private static String safe(String value) {
        if (value == null) {
            return "";
        }
        String text = value.trim();
        return text.length() <= 240 ? text : text.substring(0, 240);
    }
}
