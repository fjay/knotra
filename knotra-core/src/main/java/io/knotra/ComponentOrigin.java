package io.knotra;

public record ComponentOrigin(
        Kind kind,
        String sourceId,
        String version,
        String description) {

    public enum Kind {
        HOST,
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
