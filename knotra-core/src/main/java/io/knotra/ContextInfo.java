package io.knotra;

import java.util.Objects;

/**
 * Context 的元数据快照，用于只读观察。
 *
 * <p>记录不可变，不暴露组件实例或 Capability 值。父 ID 为 null 表示根 Context；
 * canonicalPath 为空字符串表示无路径信息。
 */
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
