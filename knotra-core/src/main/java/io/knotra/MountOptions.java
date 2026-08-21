package io.knotra;

import java.util.Map;

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

    public static final MountOptions DEFAULT = new MountOptions(ComponentOrigin.host(), Map.of());

    public String metadata(String name) {
        return metadata.get(name);
    }
}
