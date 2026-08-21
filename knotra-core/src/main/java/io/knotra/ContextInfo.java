package io.knotra;

import java.util.Objects;

public record ContextInfo(
        String contextId,
        String parentId,
        String name,
        ContextState state,
        String canonicalPath) {

    public ContextInfo {
        Objects.requireNonNull(contextId, "contextId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(state, "state");
        canonicalPath = canonicalPath == null ? "" : canonicalPath;
    }
}
